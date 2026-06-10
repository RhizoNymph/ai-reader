package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.Outcome
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/** Typed extraction failures; mapped to [dev.nymph.aireader.model.TtsError] by the caller. */
sealed interface ExtractionError {
    val message: String

    data class PathTraversal(val entryName: String) : ExtractionError {
        override val message: String get() = "archive entry escapes destination: $entryName"
    }

    data class Io(override val message: String) : ExtractionError
}

/**
 * Streams a `.tar.bz2` archive into [destDir].
 *
 * sherpa-onnx release archives wrap everything in a top-level directory named
 * after the archive; with [stripTopLevelDir] the first path segment of every
 * entry is dropped so files land directly in [destDir].
 *
 * Safety: entries containing `..` segments or resolving outside [destDir] are
 * rejected ([ExtractionError.PathTraversal]); symlink/hardlink entries are skipped.
 */
internal object TarBz2Extractor {

    fun extract(
        archive: File,
        destDir: File,
        stripTopLevelDir: Boolean = true,
    ): Outcome<Unit, ExtractionError> {
        return try {
            destDir.mkdirs()
            BufferedInputStream(archive.inputStream()).use { fileIn ->
                TarArchiveInputStream(BZip2CompressorInputStream(fileIn)).use { tarIn ->
                    extractEntries(tarIn, destDir, stripTopLevelDir)
                }
            }
        } catch (e: IOException) {
            Outcome.Err(ExtractionError.Io(e.message ?: "I/O error while extracting"))
        }
    }

    private fun extractEntries(
        tarIn: TarArchiveInputStream,
        destDir: File,
        stripTopLevelDir: Boolean,
    ): Outcome<Unit, ExtractionError> {
        val destRoot = destDir.canonicalFile
        var entry: TarArchiveEntry? = tarIn.nextEntry
        while (entry != null) {
            val rawName = entry.name
            if (rawName.split('/').contains("..")) {
                return Outcome.Err(ExtractionError.PathTraversal(rawName))
            }
            val relative = if (stripTopLevelDir) rawName.substringAfter('/', "") else rawName
            val isLink = entry.isSymbolicLink || entry.isLink
            if (relative.isNotEmpty() && !isLink) {
                val target = File(destRoot, relative)
                val canonical = target.canonicalFile
                if (canonical != destRoot &&
                    !canonical.path.startsWith(destRoot.path + File.separator)
                ) {
                    return Outcome.Err(ExtractionError.PathTraversal(rawName))
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else if (entry.isFile) {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { out -> tarIn.copyTo(out) }
                }
                // other entry types (fifo, device, ...) are intentionally skipped
            }
            entry = tarIn.nextEntry
        }
        return Outcome.Ok(Unit)
    }
}
