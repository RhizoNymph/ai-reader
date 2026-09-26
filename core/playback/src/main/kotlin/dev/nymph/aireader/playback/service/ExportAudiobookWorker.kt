package dev.nymph.aireader.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec
import dev.nymph.aireader.playback.audio.WavCodec
import dev.nymph.aireader.playback.cache.ChunkStore
import dev.nymph.aireader.playback.cache.playbackVoiceKey
import dev.nymph.aireader.playback.export.M4aEncoder
import dev.nymph.aireader.playback.export.PcmResampler
import dev.nymph.aireader.playback.export.chooseTargetRate
import dev.nymph.aireader.playback.synth.SynthesisConfig
import dev.nymph.aireader.playback.synth.renderMissingChunks
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.yield

/**
 * "Download the audiobook": render every not-yet-cached chunk of a document,
 * stitch the chunks into one fixed-rate PCM stream, encode it as an M4A
 * (AAC-LC mono) into an app-private temp file, and copy that to the
 * user-chosen SAF destination.
 *
 * Runs as a long-running foreground job (dataSync): synthesis is roughly 1x
 * real-time, so a book export can far exceed WorkManager's 10-minute
 * non-foreground budget. Chunks rendered here land in the shared playback
 * cache, so an export also makes later playback instant.
 *
 * Progress is reported as (phase, completed, total); the phases are
 * [PHASE_SYNTHESIZING] (per sentence, slow), [PHASE_ENCODING] (per sentence,
 * fast), and [PHASE_SAVING] (the final copy, brief). On failure or
 * cancellation the created destination document is deleted best-effort so no
 * empty or truncated file is left in the user's folder.
 */
class ExportAudiobookWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DOC_ID = "docId"
        const val KEY_ENGINE_ID = "engineId"
        const val KEY_VOICE_ID = "voiceId"
        const val KEY_TARGET_URI = "targetUri"
        const val KEY_TITLE = "title"
        const val KEY_ERROR = "error"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
        const val PHASE_SYNTHESIZING = "synthesizing"
        const val PHASE_ENCODING = "encoding"
        const val PHASE_SAVING = "saving"

        private const val CHANNEL_ID = "audiobook_export"
        private const val NOTIFICATION_ID = 41_733
        private const val WAV_HEADER_BYTES = 44

        /** One export at a time per document. */
        fun uniqueWorkName(docId: Long): String = "export-audiobook-$docId"

        /** Speed is irrelevant: chunks are rendered and exported at 1.0. */
        fun inputData(docId: Long, voice: VoiceSpec, targetUri: String, title: String): Data = workDataOf(
            KEY_DOC_ID to docId,
            KEY_ENGINE_ID to voice.engineId.name,
            KEY_VOICE_ID to voice.voiceId,
            KEY_TARGET_URI to targetUri,
            KEY_TITLE to title,
        )
    }

    override suspend fun doWork(): Result {
        val deps = AiReaderPlaybackService.dependencies
            ?: return failure("playback dependencies not initialized")

        val docId = inputData.getLong(KEY_DOC_ID, -1L)
        val engineName = inputData.getString(KEY_ENGINE_ID)
        val voiceId = inputData.getString(KEY_VOICE_ID)
        val targetUriString = inputData.getString(KEY_TARGET_URI)
        if (docId < 0 || engineName == null || voiceId == null || targetUriString == null) {
            return failure("missing docId/engineId/voiceId/targetUri input")
        }
        val engineId = TtsEngineId.entries.firstOrNull { it.name == engineName }
            ?: return failure("unknown engine id: $engineName")
        val voice = VoiceSpec(engineId, voiceId, speed = 1.0f)
        val targetUri = Uri.parse(targetUriString)
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Audiobook" }

        startForegroundBestEffort(title)

        val result = try {
            export(deps, docId, voice, targetUri, title)
        } catch (e: CancellationException) {
            deleteTargetQuietly(targetUri)
            throw e
        }
        if (result !is Outcome.Ok) deleteTargetQuietly(targetUri)
        return when (result) {
            is Outcome.Ok -> Result.success()
            is Outcome.Err -> failure(result.error)
        }
    }

    private suspend fun export(
        deps: PlaybackDependencies,
        docId: Long,
        voice: VoiceSpec,
        targetUri: Uri,
        title: String,
    ): Outcome<Unit, String> {
        val sentences = deps.sentencesFor(docId)
        if (sentences.isEmpty()) return Outcome.Err("this document has no readable sentences")
        val store = deps.chunkCache().forDocument(docId, playbackVoiceKey(voice))

        // Phase 1: synthesize whatever the playback cache doesn't have yet.
        if (sentences.any { !store.has(it.index) }) {
            val engine = when (val created = deps.engineFor(voice)) {
                is Outcome.Ok -> created.value
                is Outcome.Err -> return Outcome.Err(created.error.message)
            }
            val rendered = try {
                renderMissingChunks(sentences, engine, voice, store, SynthesisConfig()) { completed, total ->
                    setProgress(progressData(PHASE_SYNTHESIZING, completed, total))
                    if (completed % 25 == 0 || completed == total) {
                        updateNotification(title, "Rendering audio · $completed / $total", completed, total)
                    }
                }
            } finally {
                // Free the ONNX model before the encode phase needs memory.
                engine.release()
            }
            when (rendered) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> return Outcome.Err(rendered.error.message)
            }
        }

        // Phase 2: stitch and encode into an app-private temp file.
        setProgress(progressData(PHASE_ENCODING, 0, sentences.size))
        updateNotification(title, "Encoding audiobook…", 0, sentences.size)
        val tmp = File(applicationContext.cacheDir, "export-$docId.m4a")
        var encoder: M4aEncoder? = null
        try {
            val targetRate = when (val rates = scanChunkRates(store, sentences.size)) {
                is Outcome.Ok -> chooseTargetRate(rates.value)
                is Outcome.Err -> return rates
            }
            val active = M4aEncoder(tmp, targetRate)
            encoder = active
            for ((position, sentence) in sentences.withIndex()) {
                val pcm = when (val decoded = WavCodec.decode(readChunk(store, sentence.index))) {
                    is Outcome.Ok -> decoded.value
                    is Outcome.Err ->
                        return Outcome.Err("chunk ${sentence.index} is corrupt: ${decoded.error.message}")
                }
                active.writePcm(PcmResampler.resample(pcm.samples, pcm.sampleRate, targetRate))
                val completed = position + 1
                if (completed % 10 == 0 || completed == sentences.size) {
                    setProgress(progressData(PHASE_ENCODING, completed, sentences.size))
                }
                if (completed % 200 == 0) {
                    updateNotification(title, "Encoding audiobook · $completed / ${sentences.size}", completed, sentences.size)
                }
                yield()
            }
            active.finish()
            encoder = null

            // Phase 3: copy the finished file to the user's chosen destination.
            setProgress(progressData(PHASE_SAVING, sentences.size, sentences.size))
            updateNotification(title, "Saving…", sentences.size, sentences.size)
            val out = applicationContext.contentResolver.openOutputStream(targetUri, "wt")
                ?: return Outcome.Err("could not open the destination file")
            out.use { destination -> tmp.inputStream().use { it.copyTo(destination) } }
            return Outcome.Ok(Unit)
        } catch (e: IOException) {
            return Outcome.Err("export failed: ${e.message}")
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException and muxer state failures land here.
            return Outcome.Err("audio encoding failed: ${e.message}")
        } finally {
            encoder?.release()
            tmp.delete()
        }
    }

    /** Sample rate of every chunk, from headers only — no payload reads. */
    private fun scanChunkRates(store: ChunkStore, count: Int): Outcome<List<Int>, String> {
        val rates = ArrayList<Int>(count)
        for (index in 0 until count) {
            val header = ByteArray(WAV_HEADER_BYTES)
            try {
                DataInputStream(store.fileFor(index).inputStream()).use { it.readFully(header) }
            } catch (e: IOException) {
                return Outcome.Err("chunk $index is unreadable: ${e.message}")
            }
            when (val format = WavCodec.decodeHeader(header)) {
                is Outcome.Ok -> rates.add(format.value.sampleRate)
                is Outcome.Err -> return Outcome.Err("chunk $index is corrupt: ${format.error.message}")
            }
        }
        return Outcome.Ok(rates)
    }

    private fun readChunk(store: ChunkStore, index: Int): ByteArray = store.fileFor(index).readBytes()

    private fun progressData(phase: String, completed: Int, total: Int): Data =
        workDataOf(PROGRESS_PHASE to phase, PROGRESS_COMPLETED to completed, PROGRESS_TOTAL to total)

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    /**
     * Nothing to keep on a failed or cancelled export: the SAF document was
     * created up front by the picker, so remove the empty/partial husk.
     */
    private fun deleteTargetQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)
        } catch (_: FileNotFoundException) {
            // Already gone.
        } catch (_: SecurityException) {
            // Grant revoked; the user keeps whatever half-file exists.
        } catch (_: IllegalArgumentException) {
            // Not a document URI; nothing we can remove.
        } catch (_: UnsupportedOperationException) {
            // Provider without delete support.
        }
    }

    // -- foreground notification --

    private suspend fun startForegroundBestEffort(title: String) {
        try {
            setForeground(foregroundInfo(buildNotification(title, "Preparing…", 0, 0)))
        } catch (_: IllegalStateException) {
            // Foreground start restricted (app not visible): run as a plain job.
            // Exports started from the UI — the only path — won't hit this.
        }
    }

    private suspend fun updateNotification(title: String, text: String, completed: Int, total: Int) {
        try {
            setForeground(foregroundInfo(buildNotification(title, text, completed, total)))
        } catch (_: IllegalStateException) {
            // Same best-effort as above; progress still flows through setProgress.
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    private fun buildNotification(title: String, text: String, completed: Int, total: Int): Notification {
        ensureChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, completed, total == 0)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancel)
            .build()
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audiobook export", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
