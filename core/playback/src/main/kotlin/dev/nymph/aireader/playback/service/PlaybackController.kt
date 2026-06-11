package dev.nymph.aireader.playback.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.cache.ChunkCache
import dev.nymph.aireader.playback.cache.ChunkStore
import dev.nymph.aireader.playback.cache.playbackVoiceKey
import dev.nymph.aireader.playback.synth.SynthState
import dev.nymph.aireader.playback.synth.SynthesisConfig
import dev.nymph.aireader.playback.synth.SynthesisScheduler
import dev.nymph.aireader.tts.TtsEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin Media3 wrapper that plays cached sentence chunks while a
 * [SynthesisScheduler] fills the cache ahead of the playhead.
 *
 * Playlist model: a contiguous run of sentence chunks starting at
 * [Session.firstIndexInPlaylist]; each `MediaItem.mediaId` is the sentence
 * index. New chunks are appended as the scheduler's `cachedThrough` advances.
 * Seeking outside the current run rebuilds the playlist from the target.
 *
 * Speed: chunks are always synthesized at voice speed 1.0; the listening
 * speed is applied with `Player.setPlaybackSpeed` (PlaybackParameters), so
 * speed changes never invalidate the cache (see [playbackVoiceKey]).
 *
 * Not unit-tested: all logic here is glue over the tested scheduler/cache;
 * keep it thin. All methods must be called on the main thread (ExoPlayer's
 * application thread); [mainScope] must be a main-dispatcher scope.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val cache: ChunkCache,
    private val engineFor: (VoiceSpec) -> Outcome<TtsEngine, TtsError>,
    private val mainScope: CoroutineScope,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val synthesisConfig: SynthesisConfig = SynthesisConfig(),
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val synthScope = CoroutineScope(SupervisorJob() + workDispatcher)

    private val _currentSentenceIndex = MutableStateFlow(0)

    /** Sentence index of the chunk currently playing; the UI's highlight key. */
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _synthState = MutableStateFlow(SynthState())

    /** Mirror of the active scheduler's state (cache progress, errors). */
    val synthState: StateFlow<SynthState> = _synthState.asStateFlow()

    private var session: Session? = null

    private class Session(
        val docId: Long,
        val sentences: List<SpeakableSentence>,
        val store: ChunkStore,
        val scheduler: SynthesisScheduler,
        val engine: TtsEngine,
        var firstIndexInPlaylist: Int,
        var appendedThrough: Int,
        var collectJob: Job? = null,
    )

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = mediaItem?.mediaId?.toIntOrNull() ?: return
                _currentSentenceIndex.value = index
                session?.scheduler?.setPlayhead(index)
            }
        })
    }

    /**
     * Start a playback session for one document. Evicts cached chunks of other
     * voices for this document, starts the synthesize-ahead scheduler at
     * [startIndex], and queues whatever is already cached from there.
     */
    fun load(
        docId: Long,
        sentences: List<SpeakableSentence>,
        voice: VoiceSpec,
        startIndex: Int,
    ): Outcome<Unit, TtsError> {
        unloadSession()
        val engine = when (val created = engineFor(voice)) {
            is Outcome.Ok -> created.value
            is Outcome.Err -> return created
        }
        val voiceKey = playbackVoiceKey(voice)
        cache.evictOthers(docId, voiceKey)
        val store = cache.forDocument(docId, voiceKey)
        val scheduler = SynthesisScheduler(sentences, engine, voice, store, synthScope, synthesisConfig)
        val start = if (sentences.isEmpty()) 0 else startIndex.coerceIn(0, sentences.lastIndex)
        val newSession = Session(docId, sentences, store, scheduler, engine, start, start - 1)
        session = newSession
        scheduler.start(start)
        _currentSentenceIndex.value = start
        rebuildPlaylistFrom(newSession, start)
        newSession.collectJob = mainScope.launch {
            scheduler.state.collect { st ->
                _synthState.value = st
                appendNewlyCached(newSession, st)
            }
        }
        player.setPlaybackSpeed(voice.speed)
        player.prepare()
        return Outcome.Ok(Unit)
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Jump to a sentence; rebuilds the playlist when the target is outside the current run. */
    fun seekToSentence(index: Int) {
        val s = session ?: return
        val target = if (s.sentences.isEmpty()) 0 else index.coerceIn(0, s.sentences.lastIndex)
        s.scheduler.setPlayhead(target)
        if (target >= s.firstIndexInPlaylist && target <= s.appendedThrough) {
            player.seekTo(target - s.firstIndexInPlaylist, 0L)
        } else {
            rebuildPlaylistFrom(s, target)
            player.prepare()
        }
        _currentSentenceIndex.value = target
    }

    fun skipNext() {
        seekToSentence(_currentSentenceIndex.value + 1)
    }

    fun skipPrevious() {
        seekToSentence(_currentSentenceIndex.value - 1)
    }

    /**
     * Change the listening speed. Pure player-side rate shift over the
     * speed-1.0 cached audio; the chunk cache is untouched.
     */
    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    /** Tear down the session, scheduler, engine, and player. */
    fun release() {
        unloadSession()
        synthScope.cancel()
        player.release()
    }

    private fun unloadSession() {
        val s = session ?: return
        session = null
        s.collectJob?.cancel()
        player.clearMediaItems()
        mainScope.launch {
            // Join the worker before releasing the native engine: release()
            // must never race an in-flight synthesize call.
            s.scheduler.stop()
            s.engine.release()
        }
    }

    private fun rebuildPlaylistFrom(s: Session, start: Int) {
        player.clearMediaItems()
        s.firstIndexInPlaylist = start
        s.appendedThrough = start - 1
        var next = start
        while (next < s.sentences.size && s.store.has(next)) {
            player.addMediaItem(mediaItemFor(s, next))
            s.appendedThrough = next
            next++
        }
    }

    private fun appendNewlyCached(s: Session, st: SynthState) {
        if (session !== s) return
        var firstAppendedWindow = -1
        while (s.appendedThrough < st.cachedThrough && s.appendedThrough + 1 < s.sentences.size) {
            val next = s.appendedThrough + 1
            // cachedThrough is contiguous from the scheduler's playhead; guard
            // anyway so a stale state update can never enqueue a missing file.
            if (next < s.firstIndexInPlaylist || !s.store.has(next)) break
            if (firstAppendedWindow < 0) firstAppendedWindow = next - s.firstIndexInPlaylist
            player.addMediaItem(mediaItemFor(s, next))
            s.appendedThrough = next
        }
        // ENDED means the player drained the playlist before synthesis caught up
        // (or a seek landed in a still-uncached region and the rebuild was empty).
        // Appending items never leaves ENDED on its own — kick the player onto
        // the first new chunk. playWhenReady is untouched, so this resumes
        // audibly only if the user was listening when the playlist ran dry.
        if (firstAppendedWindow >= 0 && player.playbackState == Player.STATE_ENDED) {
            player.seekTo(firstAppendedWindow, 0L)
        }
    }

    private fun mediaItemFor(s: Session, index: Int): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(s.store.fileFor(index)))
            .setMediaId(index.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.sentences[index].displayText)
                    .build(),
            )
            .build()
}
