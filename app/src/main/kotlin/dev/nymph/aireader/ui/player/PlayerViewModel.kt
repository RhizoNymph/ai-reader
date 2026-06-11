package dev.nymph.aireader.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dev.nymph.aireader.data.db.DocumentStatus
import dev.nymph.aireader.data.db.LibraryRepository
import dev.nymph.aireader.data.settings.AppSettings
import dev.nymph.aireader.data.settings.SettingsRepository
import dev.nymph.aireader.di.ActivePlayback
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.service.PlaybackController
import dev.nymph.aireader.playback.synth.SynthState
import dev.nymph.aireader.tts.models.ModelStore
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import dev.nymph.aireader.tts.models.VoiceModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val SPEED_OPTIONS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** One pickable voice: the engine's catalog descriptor plus a concrete voice id. */
data class VoiceChoice(
    val descriptor: VoiceModelDescriptor,
    val voiceId: String,
    val displayName: String,
)

sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    /** The document's engine model is not installed; steer to the models screen. */
    data class ModelMissing(val engineName: String) : PlayerUiState

    data class LoadFailed(val message: String) : PlayerUiState

    data class Ready(val title: String, val sentences: List<SpeakableSentence>) : PlayerUiState
}

/**
 * Binds the app-scoped [PlaybackController] to one document. If the controller
 * already has this document loaded (returning to an active background session)
 * it re-attaches without reloading; otherwise it loads at the persisted
 * position. The session is deliberately NOT torn down in onCleared — playback
 * continues in the background under the media service.
 */
class PlayerViewModel(
    private val docId: Long,
    private val controller: PlaybackController,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val modelStore: ModelStore,
    private val activePlayback: MutableStateFlow<ActivePlayback?>,
    private val startPlaybackService: () -> Unit,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _voice = MutableStateFlow<VoiceSpec?>(null)
    val voice: StateFlow<VoiceSpec?> = _voice.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)

    /** Whether the player is actually emitting audio right now. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val currentSentenceIndex: StateFlow<Int> = controller.currentSentenceIndex
    val synthState: StateFlow<SynthState> = controller.synthState

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }
    }

    init {
        controller.player.addListener(playerListener)
        _isPlaying.value = controller.player.isPlaying
        viewModelScope.launch { loadDocument() }
        viewModelScope.launch { persistPositionLoop() }
    }

    /** Re-runs the load, e.g. after installing the missing model. */
    fun reload() {
        _uiState.value = PlayerUiState.Loading
        viewModelScope.launch { loadDocument() }
    }

    private suspend fun loadDocument() {
        val item = library.observeLibrary().first().firstOrNull { it.id == docId }
        if (item == null || item.status != DocumentStatus.READY) {
            _uiState.value = PlayerUiState.LoadFailed("This document is not ready to play.")
            return
        }
        val speakable = library.getSpeakable(docId)
        if (speakable == null || speakable.sentences.isEmpty()) {
            _uiState.value = PlayerUiState.LoadFailed("This document has no readable sentences.")
            return
        }

        val active = activePlayback.value
        if (active?.docId == docId) {
            // Re-attach to the live background session: no reload, no seek.
            _voice.value = active.voice
            _uiState.value = PlayerUiState.Ready(item.title, speakable.sentences)
            return
        }

        val voice = item.voice ?: defaultVoice(settings.settings.first())
        if (!isInstalled(voice.engineId)) {
            _uiState.value = PlayerUiState.ModelMissing(engineDisplayName(voice.engineId))
            return
        }
        when (val loaded = controller.load(docId, speakable.sentences, voice, item.currentSentenceIndex)) {
            is Outcome.Ok -> {
                _voice.value = voice
                activePlayback.value = ActivePlayback(docId, voice)
                _uiState.value = PlayerUiState.Ready(item.title, speakable.sentences)
            }
            is Outcome.Err -> _uiState.value = loadErrorState(loaded.error)
        }
    }

    /** Debounced playhead persistence while this document owns the controller. */
    private suspend fun persistPositionLoop() {
        controller.currentSentenceIndex.collectLatest { index ->
            if (activePlayback.value?.docId == docId && _uiState.value is PlayerUiState.Ready) {
                delay(1_000)
                library.updatePosition(docId, index)
            }
        }
    }

    fun play() {
        // The service must be up before audio starts so playback survives the UI
        // (the screen requests the notification permission before calling this).
        startPlaybackService()
        controller.play()
    }

    fun pause() {
        controller.pause()
    }

    fun skipNext() {
        controller.skipNext()
    }

    fun skipPrevious() {
        controller.skipPrevious()
    }

    /** "Back 30 seconds"-equivalent: three sentences. */
    fun seekBack() {
        controller.seekToSentence(currentSentenceIndex.value - 3)
    }

    fun seekToSentence(index: Int) {
        controller.seekToSentence(index)
    }

    fun setSpeed(speed: Float) {
        val current = _voice.value ?: return
        val updated = current.copy(speed = speed)
        _voice.value = updated
        controller.setSpeed(speed)
        activePlayback.update { if (it?.docId == docId) it.copy(voice = updated) else it }
        // Persist per document; speed never invalidates cached audio.
        appScope.launch { library.updateVoice(docId, updated) }
    }

    /** Voices of all installed engines, from the catalog. */
    fun installedVoices(): List<VoiceChoice> =
        VoiceModelCatalog.all
            .filter { modelStore.isInstalled(it.modelId) }
            .flatMap { descriptor ->
                descriptor.voices.map { VoiceChoice(descriptor, it.id, it.displayName) }
            }

    /**
     * Switch voice: persist, then reload the controller session with the new
     * voiceKey (stale-voice chunks are evicted by the controller on load).
     */
    fun selectVoice(choice: VoiceChoice) {
        val state = _uiState.value as? PlayerUiState.Ready ?: return
        val speed = _voice.value?.speed ?: 1.0f
        val newVoice = VoiceSpec(choice.descriptor.engineId, choice.voiceId, speed)
        if (newVoice == _voice.value) return
        val wasPlaying = _isPlaying.value
        viewModelScope.launch {
            library.updateVoice(docId, newVoice)
            val startIndex = currentSentenceIndex.value
            when (val loaded = controller.load(docId, state.sentences, newVoice, startIndex)) {
                is Outcome.Ok -> {
                    _voice.value = newVoice
                    activePlayback.value = ActivePlayback(docId, newVoice)
                    if (wasPlaying) play()
                }
                is Outcome.Err -> _uiState.value = loadErrorState(loaded.error)
            }
        }
    }

    override fun onCleared() {
        controller.player.removeListener(playerListener)
        // Playback continues in the background; just flush the final position.
        if (activePlayback.value?.docId == docId) {
            val index = currentSentenceIndex.value
            appScope.launch { library.updatePosition(docId, index) }
        }
    }

    private fun defaultVoice(appSettings: AppSettings): VoiceSpec {
        val descriptor = VoiceModelCatalog.forEngine(appSettings.defaultEngineId)
        val voiceId = descriptor.voices
            .firstOrNull { it.id == appSettings.defaultVoiceId }?.id
            ?: descriptor.voices.first().id
        return VoiceSpec(appSettings.defaultEngineId, voiceId, appSettings.defaultSpeed)
    }

    private fun isInstalled(engineId: TtsEngineId): Boolean =
        modelStore.isInstalled(VoiceModelCatalog.forEngine(engineId).modelId)

    private fun loadErrorState(error: TtsError): PlayerUiState = when (error) {
        is TtsError.ModelNotInstalled -> PlayerUiState.ModelMissing(engineDisplayName(error.engineId))
        else -> PlayerUiState.LoadFailed(error.message)
    }

    private fun engineDisplayName(engineId: TtsEngineId): String =
        VoiceModelCatalog.forEngine(engineId).displayName
}
