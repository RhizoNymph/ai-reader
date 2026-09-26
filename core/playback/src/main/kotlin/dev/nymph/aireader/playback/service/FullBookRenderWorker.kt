package dev.nymph.aireader.playback.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.cache.playbackVoiceKey
import dev.nymph.aireader.playback.synth.SynthesisConfig
import dev.nymph.aireader.playback.synth.renderMissingChunks

/**
 * Background "render the whole book" job: walks every sentence of a document
 * start-to-end and synthesizes any chunk not already in the cache, so later
 * playback is instant and offline-complete.
 *
 * Thin glue over the same primitives the scheduler uses ([renderChunk] +
 * [dev.nymph.aireader.playback.cache.ChunkStore]), so worker-rendered chunks
 * are byte-identical to playback-rendered ones and the two never conflict —
 * both check `has(index)` before writing and writes are atomic.
 *
 * Cancellation is cooperative: `renderChunk`/`setProgress` are suspension
 * points, so WorkManager cancellation stops the walk promptly; the engine is
 * always released.
 */
class FullBookRenderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DOC_ID = "docId"
        const val KEY_ENGINE_ID = "engineId"
        const val KEY_VOICE_ID = "voiceId"
        const val KEY_ERROR = "error"
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"

        /** Input for one render job. Speed is irrelevant: chunks are always rendered at 1.0. */
        fun inputData(docId: Long, voice: VoiceSpec): Data = workDataOf(
            KEY_DOC_ID to docId,
            KEY_ENGINE_ID to voice.engineId.name,
            KEY_VOICE_ID to voice.voiceId,
        )
    }

    override suspend fun doWork(): Result {
        val deps = AiReaderPlaybackService.dependencies
            ?: return failure("playback dependencies not initialized")

        val docId = inputData.getLong(KEY_DOC_ID, -1L)
        val engineName = inputData.getString(KEY_ENGINE_ID)
        val voiceId = inputData.getString(KEY_VOICE_ID)
        if (docId < 0 || engineName == null || voiceId == null) {
            return failure("missing docId/engineId/voiceId input")
        }
        val engineId = TtsEngineId.entries.firstOrNull { it.name == engineName }
            ?: return failure("unknown engine id: $engineName")

        // Cache chunks are always speed-1.0 audio; playback rate-shifts.
        val voice = VoiceSpec(engineId, voiceId, speed = 1.0f)
        val engine = when (val created = deps.engineFor(voice)) {
            is Outcome.Ok -> created.value
            is Outcome.Err -> return failure(created.error.message)
        }
        try {
            val sentences = deps.sentencesFor(docId)
            if (sentences.isEmpty()) return Result.success()
            val store = deps.chunkCache().forDocument(docId, playbackVoiceKey(voice))
            val rendered = renderMissingChunks(sentences, engine, voice, store, SynthesisConfig()) { completed, total ->
                setProgress(workDataOf(PROGRESS_COMPLETED to completed, PROGRESS_TOTAL to total))
            }
            return when (rendered) {
                is Outcome.Ok -> Result.success()
                is Outcome.Err -> failure(rendered.error.message)
            }
        } finally {
            engine.release()
        }
    }

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))
}
