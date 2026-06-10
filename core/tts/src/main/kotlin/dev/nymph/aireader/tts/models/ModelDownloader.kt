package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext

/** Contract for installing a voice model; lets [ModelRepository] be tested with fakes. */
interface ModelInstaller {
    /**
     * Downloads (resuming a previous partial download if present), verifies,
     * and extracts [descriptor] into the store. [onProgress] receives
     * (bytesOnDisk, expectedTotalBytes); total is <= 0 when unknown.
     */
    suspend fun downloadAndInstall(
        descriptor: VoiceModelDescriptor,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Outcome<Unit, TtsError>
}

/**
 * Streams the model archive to `<root>/<modelId>.tar.bz2.part` with HTTP Range
 * resume, verifies the byte count against Content-Length/Content-Range, then
 * extracts via [TarBz2Extractor] and writes the `.complete` marker.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelInstaller {

    override suspend fun downloadAndInstall(
        descriptor: VoiceModelDescriptor,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Outcome<Unit, TtsError> = withContext(ioDispatcher) {
        if (store.isInstalled(descriptor.modelId)) return@withContext Outcome.Ok(Unit)
        store.rootDir.mkdirs()

        when (val downloaded = downloadArchive(descriptor, onProgress, allowRetryFromScratch = true)) {
            is Outcome.Err -> downloaded
            is Outcome.Ok -> extractAndFinalize(descriptor)
        }
    }

    private suspend fun downloadArchive(
        descriptor: VoiceModelDescriptor,
        onProgress: (Long, Long) -> Unit,
        allowRetryFromScratch: Boolean,
    ): Outcome<Unit, TtsError> {
        val part = store.partFile(descriptor.modelId)
        val offset = if (part.isFile) part.length() else 0L

        val request = Request.Builder()
            .url(descriptor.downloadUrl)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                handleResponse(descriptor, part, offset, response, onProgress, allowRetryFromScratch)
            }
        } catch (e: IOException) {
            Outcome.Err(
                TtsError.DownloadFailed(
                    descriptor.engineId,
                    "download of ${descriptor.modelId} failed: ${e.message}",
                ),
            )
        }
    }

    private suspend fun handleResponse(
        descriptor: VoiceModelDescriptor,
        part: File,
        requestedOffset: Long,
        response: Response,
        onProgress: (Long, Long) -> Unit,
        allowRetryFromScratch: Boolean,
    ): Outcome<Unit, TtsError> {
        val engineId = descriptor.engineId
        when {
            response.code == HTTP_RANGE_NOT_SATISFIABLE && allowRetryFromScratch -> {
                // Our .part is inconsistent with the remote file; start over once.
                part.delete()
                return downloadArchive(descriptor, onProgress, allowRetryFromScratch = false)
            }
            !response.isSuccessful -> return Outcome.Err(
                TtsError.DownloadFailed(engineId, "HTTP ${response.code} for ${descriptor.downloadUrl}"),
            )
        }

        val resumed = response.code == HttpURLConnection.HTTP_PARTIAL
        val writeOffset = if (resumed) requestedOffset else 0L
        if (resumed) {
            val rangeStart = parseContentRangeStart(response.header("Content-Range"))
            if (rangeStart != requestedOffset) {
                part.delete()
                return Outcome.Err(
                    TtsError.ChecksumMismatch(
                        engineId,
                        "server resumed at byte $rangeStart, expected $requestedOffset",
                    ),
                )
            }
        }

        val body = response.body
            ?: return Outcome.Err(TtsError.DownloadFailed(engineId, "empty response body"))
        val contentLength = body.contentLength()
        val expectedTotal =
            if (contentLength >= 0) writeOffset + contentLength else descriptor.archiveSizeBytes

        val written = copyBody(body.byteStream(), part, append = resumed, writeOffset, expectedTotal, onProgress)

        if (contentLength >= 0 && written != contentLength) {
            return Outcome.Err(
                TtsError.ChecksumMismatch(
                    engineId,
                    "expected $contentLength bytes, received $written",
                ),
            )
        }
        return Outcome.Ok(Unit)
    }

    private suspend fun copyBody(
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        writeOffset: Long,
        expectedTotal: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var written = 0L
        input.use { source ->
            FileOutputStream(part, append).use { sink ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(writeOffset + written, expectedTotal)
                }
            }
        }
        return written
    }

    private fun extractAndFinalize(descriptor: VoiceModelDescriptor): Outcome<Unit, TtsError> {
        val part = store.partFile(descriptor.modelId)
        val dir = store.modelDir(descriptor.modelId)
        // Wipe any partial previous extraction for idempotency.
        dir.deleteRecursively()

        return when (val extracted = TarBz2Extractor.extract(part, dir, stripTopLevelDir = true)) {
            is Outcome.Ok -> {
                part.delete()
                store.markInstalled(descriptor.modelId)
                Outcome.Ok(Unit)
            }
            is Outcome.Err -> {
                // A corrupt or malicious archive will never extract; drop it too.
                dir.deleteRecursively()
                part.delete()
                Outcome.Err(
                    TtsError.DownloadFailed(
                        descriptor.engineId,
                        "extraction of ${descriptor.modelId} failed: ${extracted.error.message}",
                    ),
                )
            }
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-""")

        /** Returns the start byte of a `Content-Range: bytes <start>-<end>/<total>` header, or -1. */
        fun parseContentRangeStart(header: String?): Long =
            header?.let { CONTENT_RANGE_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: -1L
    }
}
