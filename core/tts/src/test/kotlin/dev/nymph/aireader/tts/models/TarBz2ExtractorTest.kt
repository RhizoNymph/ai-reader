package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An entry to put into a generated test archive. Content == null means directory. */
data class TestArchiveEntry(val name: String, val content: ByteArray? = null)

/** Builds a real .tar.bz2 on disk with commons-compress; shared by downloader tests. */
fun writeTarBz2(target: File, entries: List<TestArchiveEntry>) {
    target.outputStream().use { fileOut ->
        BZip2CompressorOutputStream(fileOut).use { bzOut ->
            TarArchiveOutputStream(bzOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (entry in entries) {
                    val tarEntry = TarArchiveEntry(entry.name)
                    if (entry.content != null) tarEntry.size = entry.content.size.toLong()
                    tarOut.putArchiveEntry(tarEntry)
                    if (entry.content != null) tarOut.write(entry.content)
                    tarOut.closeArchiveEntry()
                }
                tarOut.finish()
            }
        }
    }
}

class TarBz2ExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun archive(entries: List<TestArchiveEntry>): File {
        val file = tmp.newFile("archive.tar.bz2")
        writeTarBz2(file, entries)
        return file
    }

    @Test
    fun `extracts files stripping the top-level directory`() {
        val archive = archive(
            listOf(
                TestArchiveEntry("kokoro-test/"),
                TestArchiveEntry("kokoro-test/model.onnx", "weights".toByteArray()),
                TestArchiveEntry("kokoro-test/tokens.txt", "a 1\nb 2\n".toByteArray()),
                TestArchiveEntry("kokoro-test/espeak-ng-data/"),
                TestArchiveEntry("kokoro-test/espeak-ng-data/en_dict", "dict".toByteArray()),
            ),
        )
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(archive, dest, stripTopLevelDir = true)

        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals("weights", File(dest, "model.onnx").readText())
        assertEquals("a 1\nb 2\n", File(dest, "tokens.txt").readText())
        assertEquals("dict", File(dest, "espeak-ng-data/en_dict").readText())
    }

    @Test
    fun `extracts verbatim when not stripping`() {
        val archive = archive(
            listOf(TestArchiveEntry("top/model.onnx", "w".toByteArray())),
        )
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(archive, dest, stripTopLevelDir = false)

        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals("w", File(dest, "top/model.onnx").readText())
    }

    @Test
    fun `rejects entries with dot-dot path traversal`() {
        val archive = archive(
            listOf(
                TestArchiveEntry("top/ok.txt", "ok".toByteArray()),
                TestArchiveEntry("top/../../evil.txt", "evil".toByteArray()),
            ),
        )
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(archive, dest, stripTopLevelDir = true)

        assertIs<Outcome.Err<ExtractionError>>(result)
        assertIs<ExtractionError.PathTraversal>(result.error)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun `rejects absolute-ish traversal without strip`() {
        val archive = archive(
            listOf(TestArchiveEntry("../evil.txt", "evil".toByteArray())),
        )
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(archive, dest, stripTopLevelDir = false)

        assertIs<Outcome.Err<ExtractionError>>(result)
        assertIs<ExtractionError.PathTraversal>(result.error)
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun `corrupt archive yields Io error`() {
        val bogus = tmp.newFile("bogus.tar.bz2")
        bogus.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(bogus, dest, stripTopLevelDir = true)

        assertIs<Outcome.Err<ExtractionError>>(result)
        assertIs<ExtractionError.Io>(result.error)
    }

    @Test
    fun `archive with only a top-level dir extracts to empty dest`() {
        val archive = archive(listOf(TestArchiveEntry("top/")))
        val dest = tmp.newFolder("dest")

        val result = TarBz2Extractor.extract(archive, dest, stripTopLevelDir = true)

        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }
}
