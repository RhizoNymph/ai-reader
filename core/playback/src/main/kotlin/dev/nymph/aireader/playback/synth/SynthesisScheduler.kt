package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.cache.ChunkStore
import dev.nymph.aireader.tts.TtsEngine
import java.util.BitSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tuning for the synthesis scheduler. */
data class SynthesisConfig(
    /** Sentences ahead of the playhead that must be cached before opportunistic fill starts. */
    val lookaheadSentences: Int = 8,
    /** Byte budget for filling beyond the window (the window itself is exempt). */
    val maxAheadBytes: Long = 256L * 1024 * 1024,
    /** Sample rate for silence-fallback chunks before any sentence has synthesized successfully. */
    val fallbackSampleRate: Int = 24_000,
    /** Length of the silence chunk substituted for a sentence that failed to synthesize. */
    val fallbackSilenceMs: Int = 300,
    /** Back-off before retrying after a cache write failure (e.g. disk full). */
    val writeRetryDelayMs: Long = 1_000,
) {
    init {
        require(lookaheadSentences > 0) { "lookaheadSentences must be positive" }
        require(maxAheadBytes >= 0) { "maxAheadBytes must be non-negative" }
        require(fallbackSampleRate > 0) { "fallbackSampleRate must be positive" }
        require(fallbackSilenceMs > 0) { "fallbackSilenceMs must be positive" }
        require(writeRetryDelayMs >= 0) { "writeRetryDelayMs must be non-negative" }
    }
}

/** Observable scheduler state; what [dev.nymph.aireader.playback.service.PlaybackController] consumes. */
data class SynthState(
    val playhead: Int = 0,
    /**
     * Largest index `j` such that every chunk in `[playhead, j]` is cached;
     * `playhead - 1` when the playhead chunk itself is not cached yet.
     * Playback may safely enqueue chunk files up to and including this index.
     */
    val cachedThrough: Int = -1,
    val isSynthesizing: Boolean = false,
    /** Every sentence in the document is cached; the worker has exited. */
    val isComplete: Boolean = false,
    /** Most recent failure (synthesis or cache write); sticky, playback continued past it. */
    val lastError: TtsError? = null,
)

/**
 * The synthesize-ahead engine of the playback layer.
 *
 * A single worker coroutine repeatedly picks the first uncached chunk in the
 * playhead window (then, within the byte budget, beyond it), renders it via
 * [renderChunk], and writes it to the cache. When there is nothing to do it
 * suspends on playhead changes — no polling. Because there is exactly one
 * worker and every target is checked against the cache first, no index is
 * ever synthesized twice concurrently (or at all, once cached).
 *
 * [setPlayhead] re-targets the worker immediately when it is idle-waiting; an
 * in-flight synthesis is never cancelled (its result is still useful and
 * native synthesis is not interruptible anyway) — the new window applies from
 * the next iteration.
 *
 * Chunks are always rendered at voice speed 1.0 (the player rate-shifts), so
 * the constructor pins [voice] to speed 1.0 regardless of what is passed in.
 */
class SynthesisScheduler(
    private val sentences: List<SpeakableSentence>,
    private val engine: TtsEngine,
    voice: VoiceSpec,
    private val cache: ChunkStore,
    private val scope: CoroutineScope,
    private val config: SynthesisConfig = SynthesisConfig(),
) {
    private val voice: VoiceSpec = voice.copy(speed = 1.0f)

    private val playheadFlow = MutableStateFlow(0)
    private val stateFlow = MutableStateFlow(SynthState())

    /** Current playhead as seen by the scheduler. */
    val playhead: StateFlow<Int> = playheadFlow.asStateFlow()
    val state: StateFlow<SynthState> = stateFlow.asStateFlow()

    // Memo of indices known cached: the cache only grows during a session, so
    // a true answer never goes stale. Guarded by a monitor because the worker
    // and setPlayhead callers both consult it.
    private val memoLock = Any()
    private val cachedMemo = BitSet(sentences.size)

    private var worker: Job? = null

    /** Launch the worker. Call exactly once; [initialIndex] is clamped to the document. */
    fun start(initialIndex: Int = 0) {
        check(worker == null) { "scheduler already started" }
        playheadFlow.value = clampIndex(initialIndex)
        if (sentences.isEmpty()) {
            stateFlow.value = SynthState(playhead = 0, cachedThrough = -1, isComplete = true)
            return
        }
        refresh(isSynthesizing = false)
        worker = scope.launch { runLoop() }
    }

    /**
     * Move the synthesis window. Interrupts an idle wait immediately; an
     * in-flight synthesis finishes first. Index is clamped to the document.
     */
    fun setPlayhead(index: Int) {
        playheadFlow.value = clampIndex(index)
        refresh(isSynthesizing = stateFlow.value.isSynthesizing)
    }

    /** Fire-and-forget cancellation of the worker. */
    fun cancel() {
        worker?.cancel()
    }

    /** Cancel the worker and wait for it to finish; safe to call [TtsEngine.release] afterwards. */
    suspend fun stop() {
        worker?.cancelAndJoin()
        worker = null
    }

    private suspend fun runLoop() {
        var lastKnownSampleRate = config.fallbackSampleRate
        while (currentCoroutineContext().isActive) {
            val observedPlayhead = playheadFlow.value
            val target = nextTarget(
                playhead = observedPlayhead,
                size = sentences.size,
                lookahead = config.lookaheadSentences,
                usedBytes = cache.totalBytes(),
                maxAheadBytes = config.maxAheadBytes,
                isCached = ::isCached,
            )
            if (target == null) {
                val complete = cachedThrough(0, sentences.size, ::isCached) == sentences.lastIndex
                refresh(isSynthesizing = false, complete = complete)
                if (complete) return
                // Idle until the playhead moves; setPlayhead resumes this immediately.
                playheadFlow.first { it != observedPlayhead }
                continue
            }

            refresh(isSynthesizing = true)
            val render = renderChunk(engine, sentences[target], voice, lastKnownSampleRate, config.fallbackSilenceMs)
            lastKnownSampleRate = render.sampleRate
            when (val written = cache.write(target, render.wavBytes)) {
                is Outcome.Ok -> refresh(isSynthesizing = true, error = render.error)
                is Outcome.Err -> {
                    refresh(
                        isSynthesizing = false,
                        error = TtsError.SynthesisFailed(target, "cache write failed: ${written.error.message}"),
                    )
                    // The chunk is still uncached and would be re-picked at
                    // once; back off so a full disk does not busy-loop.
                    delay(config.writeRetryDelayMs)
                }
            }
        }
    }

    private fun refresh(isSynthesizing: Boolean, complete: Boolean = false, error: TtsError? = null) {
        stateFlow.update { current ->
            val ph = playheadFlow.value
            current.copy(
                playhead = ph,
                cachedThrough = cachedThrough(ph, sentences.size, ::isCached),
                isSynthesizing = isSynthesizing,
                isComplete = current.isComplete || complete,
                lastError = error ?: current.lastError,
            )
        }
    }

    private fun clampIndex(index: Int): Int =
        if (sentences.isEmpty()) 0 else index.coerceIn(0, sentences.lastIndex)

    private fun isCached(index: Int): Boolean {
        synchronized(memoLock) {
            if (cachedMemo.get(index)) return true
        }
        if (!cache.has(index)) return false
        synchronized(memoLock) { cachedMemo.set(index) }
        return true
    }
}
