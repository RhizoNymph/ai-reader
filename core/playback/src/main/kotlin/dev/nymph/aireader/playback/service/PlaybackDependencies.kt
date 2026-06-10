package dev.nymph.aireader.playback.service

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.cache.ChunkCache
import dev.nymph.aireader.tts.TtsEngine

/**
 * Minimal service locator for the playback components, since the app uses
 * manual DI and Android instantiates [AiReaderPlaybackService] and
 * [FullBookRenderWorker] itself.
 *
 * The app must assign [AiReaderPlaybackService.dependencies] in
 * `Application.onCreate` (before any service start or WorkManager run; the
 * Application is always created first in the process, so this is safe).
 */
interface PlaybackDependencies {
    /** The app-scoped [PlaybackController] the media session is built around. */
    fun controller(): PlaybackController

    /** The app-scoped on-disk chunk cache. */
    fun chunkCache(): ChunkCache

    /**
     * A ready engine for [voice]'s engine id (typically via TtsEngineFactory).
     * Callers own the returned engine and must `release()` it.
     */
    fun engineFor(voice: VoiceSpec): Outcome<TtsEngine, TtsError>

    /** All sentences of a document, ordered by index (backed by core:data). */
    suspend fun sentencesFor(docId: Long): List<SpeakableSentence>
}
