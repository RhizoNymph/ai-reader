package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.TtsError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ModelStore
    private lateinit var downloader: ModelDownloader

    private val modelId = "test-model"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = ModelStore(tmp.newFolder("models"))
        downloader = ModelDownloader(store, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun descriptor(approxSize: Long = 1024): VoiceModelDescriptor = VoiceModelDescriptor(
        engineId = TtsEngineId.PIPER,
        modelId = modelId,
        displayName = "Test model",
        language = "en-US",
        downloadUrl = server.url("/$modelId.tar.bz2").toString(),
        archiveSizeBytes = approxSize,
        voices = listOf(VoiceInfo(id = "v0", speakerId = 0, displayName = "V0")),
        sampleRate = 22050,
        filesOfInterest = ModelFilesOfInterest(modelOnnx = "model.onnx", tokens = "tokens.txt"),
    )

    /** A small but real tar.bz2 with a sherpa-style top-level directory. */
    private fun archiveBytes(): ByteArray {
        val f = tmp.newFile("payload.tar.bz2")
        writeTarBz2(
            f,
            listOf(
                TestArchiveEntry("$modelId/"),
                TestArchiveEntry("$modelId/model.onnx", ByteArray(4096) { (it % 251).toByte() }),
                TestArchiveEntry("$modelId/tokens.txt", "a 0\nb 1\n".toByteArray()),
            ),
        )
        val bytes = f.readBytes()
        f.delete()
        return bytes
    }

    @Test
    fun `successful download extracts, marks complete, deletes archive`() = runTest {
        val bytes = archiveBytes()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val progress = mutableListOf<Pair<Long, Long>>()
        val result = downloader.downloadAndInstall(descriptor()) { read, total ->
            progress.add(read to total)
        }

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(store.isInstalled(modelId))
        assertEquals("a 0\nb 1\n", File(store.modelDir(modelId), "tokens.txt").readText())
        assertTrue(File(store.modelDir(modelId), "model.onnx").length() == 4096L)
        assertFalse(store.partFile(modelId).exists())

        assertTrue(progress.isNotEmpty())
        // monotonically non-decreasing, ends at full size, total reported from Content-Length
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `already installed model is a no-op success`() = runTest {
        store.markInstalled(modelId)
        // no enqueued response: any request would fail the test via timeout/IOException
        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }
        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `http error status maps to DownloadFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.DownloadFailed>(result.error)
        assertFalse(store.isInstalled(modelId))
    }

    @Test
    fun `disconnect mid-body maps to DownloadFailed and keeps the part file for resume`() = runTest {
        val bytes = archiveBytes()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.DownloadFailed>(result.error)
        assertFalse(store.isInstalled(modelId))
        assertTrue(store.partFile(modelId).exists())
    }

    @Test
    fun `resumes from existing part file with a Range request`() = runTest {
        val bytes = archiveBytes()
        val splitAt = bytes.size / 2
        store.partFile(modelId).parentFile?.mkdirs()
        store.partFile(modelId).writeBytes(bytes.copyOfRange(0, splitAt))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(412)
                check(range == "bytes=$splitAt-") { "unexpected range: $range" }
                val rest = bytes.copyOfRange(splitAt, bytes.size)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $splitAt-${bytes.size - 1}/${bytes.size}")
                    .setBody(Buffer().write(rest))
            }
        }

        val progress = mutableListOf<Pair<Long, Long>>()
        val result = downloader.downloadAndInstall(descriptor()) { read, total ->
            progress.add(read to total)
        }

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(store.isInstalled(modelId))
        assertEquals("a 0\nb 1\n", File(store.modelDir(modelId), "tokens.txt").readText())
        // progress accounts for the pre-existing offset
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
        assertTrue(progress.first().first >= splitAt.toLong())
    }

    @Test
    fun `server ignoring Range with 200 restarts from scratch`() = runTest {
        val bytes = archiveBytes()
        store.partFile(modelId).writeBytes(byteArrayOf(9, 9, 9)) // stale garbage
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(store.isInstalled(modelId))
        assertEquals("a 0\nb 1\n", File(store.modelDir(modelId), "tokens.txt").readText())
    }

    @Test
    fun `206 with mismatched Content-Range start maps to ChecksumMismatch`() = runTest {
        val bytes = archiveBytes()
        store.partFile(modelId).writeBytes(bytes.copyOfRange(0, 10))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
                .setBody(Buffer().write(bytes)),
        )

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.ChecksumMismatch>(result.error)
        assertFalse(store.isInstalled(modelId))
    }

    @Test
    fun `416 range-not-satisfiable restarts from scratch and succeeds`() = runTest {
        val bytes = archiveBytes()
        store.partFile(modelId).writeBytes(ByteArray(bytes.size + 100)) // part larger than file
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(store.isInstalled(modelId))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `corrupt archive maps to DownloadFailed and cleans up`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64) { 7 })))

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.DownloadFailed>(result.error)
        assertFalse(store.isInstalled(modelId))
        assertFalse(store.partFile(modelId).exists())
        assertFalse(store.modelDir(modelId).exists())
    }

    @Test
    fun `archive with traversal entry maps to DownloadFailed and nothing escapes`() = runTest {
        val f = tmp.newFile("evil.tar.bz2")
        writeTarBz2(
            f,
            listOf(
                TestArchiveEntry("$modelId/ok.txt", "ok".toByteArray()),
                TestArchiveEntry("$modelId/../../evil.txt", "evil".toByteArray()),
            ),
        )
        server.enqueue(MockResponse().setBody(Buffer().write(f.readBytes())))

        val result = downloader.downloadAndInstall(descriptor()) { _, _ -> }

        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.DownloadFailed>(result.error)
        assertFalse(store.isInstalled(modelId))
        assertFalse(File(store.rootDir.parentFile, "evil.txt").exists())
        assertFalse(File(store.rootDir, "evil.txt").exists())
    }

    @Test
    fun `unreachable server maps to DownloadFailed`() = runTest {
        val dead = descriptor().copy(downloadUrl = "https://127.0.0.1:1/never.tar.bz2")
        val result = downloader.downloadAndInstall(dead) { _, _ -> }
        assertIs<Outcome.Err<TtsError>>(result)
        assertIs<TtsError.DownloadFailed>(result.error)
    }
}
