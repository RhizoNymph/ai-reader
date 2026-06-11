package dev.nymph.aireader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.aireader.data.settings.AppSettings
import dev.nymph.aireader.data.settings.SettingsRepository
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.tts.models.ModelRepository
import dev.nymph.aireader.tts.models.ModelState
import dev.nymph.aireader.tts.models.VoiceModelCatalog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Bounds for the synthesis lookahead slider. */
val LOOKAHEAD_RANGE: IntRange = 4..32

class SettingsViewModel(
    private val settings: SettingsRepository,
    modelRepository: ModelRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val installedEngines: StateFlow<Set<TtsEngineId>> =
        combine(VoiceModelCatalog.all.map { modelRepository.observe(it.engineId) }) { states ->
            VoiceModelCatalog.all
                .filterIndexed { i, _ -> states[i] is ModelState.Installed }
                .map { it.engineId }
                .toSet()
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VoiceModelCatalog.all
                .filter { modelRepository.observe(it.engineId).value is ModelState.Installed }
                .map { it.engineId }
                .toSet(),
        )

    /** Switching engines also re-homes the default voice into the new engine's catalog. */
    fun setDefaultEngine(engineId: TtsEngineId) {
        viewModelScope.launch {
            settings.setDefaultEngineId(engineId)
            val descriptor = VoiceModelCatalog.forEngine(engineId)
            if (descriptor.voices.none { it.id == state.value.defaultVoiceId }) {
                settings.setDefaultVoiceId(descriptor.voices.first().id)
            }
        }
    }

    fun setDefaultVoice(voiceId: String) {
        viewModelScope.launch { settings.setDefaultVoiceId(voiceId) }
    }

    fun setDefaultSpeed(speed: Float) {
        viewModelScope.launch { settings.setDefaultSpeed(speed) }
    }

    fun setLookahead(sentences: Int) {
        viewModelScope.launch {
            settings.setSynthesisLookaheadSentences(
                sentences.coerceIn(LOOKAHEAD_RANGE.first, LOOKAHEAD_RANGE.last),
            )
        }
    }
}
