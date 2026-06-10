package dev.nymph.aireader.playback.cache

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec
import java.io.File
import java.io.IOException
import java.util.Locale

/** Structured failure for chunk cache writes. */
sealed interface CacheError {
    val message: String

    data class Io(val path: String, override val message: String) : CacheError
}

/**
 * Cache key for one voice configuration: `<engineId>_<voiceId>_<speed 2dp>`,
 * sanitized so it is always a safe single path segment.
 */
fun voiceKey(engineId: TtsEngineId, voiceId: String, speed: Float): String {
    val raw = "%s_%s_%.2f".format(Locale.ROOT, engineId.name, voiceId, speed)
    return buildString(raw.length) {
        for (c in raw) append(if (c.isSafePathChar()) c else '_')
    }
}

/**
 * The voice key the playback layer actually uses. Chunks are always
 * synthesized at voice speed 1.0 and rate-shifted by the player
 * ([androidx.media3.common.PlaybackParameters]), so changing the listening
 * speed never invalidates the cache: the key pins speed to 1.00 regardless of
 * [VoiceSpec.speed].
 */
fun playbackVoiceKey(voice: VoiceSpec): String = voiceKey(voice.engineId, voice.voiceId, 1.0f)

private fun Char.isSafePathChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

/**
 * What the synthesis scheduler and render worker need from the cache, bound to
 * one `(docId, voiceKey)` directory. Implemented by [ChunkCache.forDocument];
 * tests use an in-memory fake.
 */
interface ChunkStore {
    /** True iff the chunk file exists. Files only appear via atomic rename, so existence implies complete. */
    fun has(index: Int): Boolean

    /** Path of the chunk for [index] (whether or not it exists yet). */
    fun fileFor(index: Int): File

    /** Atomically persist a chunk: write to a temp file, then rename into place. */
    fun write(index: Int, wavBytes: ByteArray): Outcome<File, CacheError>

    /** Bytes currently used by this `(docId, voiceKey)` directory. */
    fun totalBytes(): Long
}

/**
 * On-disk audio chunk cache. Layout: `<root>/<docId>/<voiceKey>/<index>.wav`.
 *
 * Readers (Media3) only ever see fully-written files: writes go to a `.tmp`
 * sibling and are renamed into place, and `has` only reports the final name.
 */
class ChunkCache(private val rootDir: File) {

    fun docDir(docId: Long): File = File(rootDir, docId.toString())

    fun dirFor(docId: Long, voiceKey: String): File = File(docDir(docId), voiceKey)

    /** A view of the cache bound to one document + voice; what playback components use. */
    fun forDocument(docId: Long, voiceKey: String): ChunkStore = DocumentChunkStore(dirFor(docId, voiceKey))

    /** Delete every cached chunk (all voices) for [docId]. Returns true when nothing remains. */
    fun deleteDoc(docId: Long): Boolean = docDir(docId).deleteRecursively()

    /** Bytes used by [docId] across all of its voice directories. */
    fun totalBytes(docId: Long): Long = sizeOf(docDir(docId))

    /**
     * Voice changed: chunks for any other voice key of [docId] are stale audio
     * and are deleted, keeping only [keepVoiceKey].
     */
    fun evictOthers(docId: Long, keepVoiceKey: String) {
        docDir(docId).listFiles()?.forEach { child ->
            if (child.name != keepVoiceKey) child.deleteRecursively()
        }
    }

    private companion object {
        fun sizeOf(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }
    }
}

private class DocumentChunkStore(private val dir: File) : ChunkStore {

    override fun fileFor(index: Int): File {
        require(index >= 0) { "chunk index must be non-negative: $index" }
        return File(dir, "$index.wav")
    }

    override fun has(index: Int): Boolean = fileFor(index).isFile

    override fun write(index: Int, wavBytes: ByteArray): Outcome<File, CacheError> {
        val target = fileFor(index)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Outcome.Err(CacheError.Io(dir.path, "could not create chunk directory"))
        }
        val tmp = File(dir, "$index.wav.tmp")
        return try {
            tmp.writeBytes(wavBytes)
            if (tmp.renameTo(target)) {
                Outcome.Ok(target)
            } else {
                tmp.delete()
                Outcome.Err(CacheError.Io(target.path, "atomic rename failed"))
            }
        } catch (e: IOException) {
            tmp.delete()
            Outcome.Err(CacheError.Io(target.path, e.message ?: "write failed"))
        }
    }

    override fun totalBytes(): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
