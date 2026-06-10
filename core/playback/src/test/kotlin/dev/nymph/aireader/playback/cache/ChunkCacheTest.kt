package dev.nymph.aireader.playback.cache

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(): ChunkCache = ChunkCache(File(tmp.root, "chunks"))

    // -- voice keys --

    @Test
    fun `voiceKey is engine voice speed with two decimals`() {
        assertEquals("KOKORO_af_heart_1.00", voiceKey(TtsEngineId.KOKORO, "af_heart", 1.0f))
        assertEquals("PIPER_sid-3_1.25", voiceKey(TtsEngineId.PIPER, "sid-3", 1.25f))
        assertEquals("KOKORO_bf_emma_2.50", voiceKey(TtsEngineId.KOKORO, "bf_emma", 2.5f))
    }

    @Test
    fun `voiceKey sanitizes filesystem-hostile characters`() {
        assertEquals("KOKORO_en_US_lib_r_1.00", voiceKey(TtsEngineId.KOKORO, "en US/lib:r", 1.0f))
        assertEquals("PIPER_a_b_c_1.00", voiceKey(TtsEngineId.PIPER, "a\\b*c", 1.0f))
        assertEquals("KOKORO_v__1.00", voiceKey(TtsEngineId.KOKORO, "vé", 1.0f)) // non-ASCII
    }

    @Test
    fun `playbackVoiceKey always uses speed one`() {
        // Chunks are synthesized at 1.0 and rate-shifted by the player, so the
        // cache key must not vary with the user's chosen speed.
        val fast = VoiceSpec(TtsEngineId.PIPER, "sid-0", 2.0f)
        val slow = VoiceSpec(TtsEngineId.PIPER, "sid-0", 0.5f)
        assertEquals("PIPER_sid-0_1.00", playbackVoiceKey(fast))
        assertEquals(playbackVoiceKey(fast), playbackVoiceKey(slow))
    }

    // -- layout --

    @Test
    fun `fileFor follows root docId voiceKey index layout`() {
        val c = cache()
        val store = c.forDocument(12L, "KOKORO_af_heart_1.00")
        val expected = File(File(tmp.root, "chunks"), "12/KOKORO_af_heart_1.00/3.wav")
        assertEquals(expected.canonicalFile, store.fileFor(3).canonicalFile)
    }

    @Test
    fun `fileFor rejects negative indices`() {
        val store = cache().forDocument(1L, "k")
        assertFailsWith<IllegalArgumentException> { store.fileFor(-1) }
    }

    // -- write / has --

    @Test
    fun `write then has and round trip`() {
        val store = cache().forDocument(7L, "KOKORO_af_heart_1.00")
        assertFalse(store.has(0))
        val bytes = byteArrayOf(1, 2, 3, 4)
        val out = store.write(0, bytes)
        assertIs<Outcome.Ok<File>>(out)
        assertTrue(store.has(0))
        assertContentEquals(bytes, store.fileFor(0).readBytes())
    }

    @Test
    fun `write leaves no temp files behind`() {
        val store = cache().forDocument(7L, "k")
        store.write(5, byteArrayOf(9))
        val dir = store.fileFor(5).parentFile!!
        val leftovers = dir.listFiles()!!.filter { it.name != "5.wav" }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `write overwrites an existing chunk`() {
        val store = cache().forDocument(7L, "k")
        store.write(0, byteArrayOf(1))
        store.write(0, byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(2, 3), store.fileFor(0).readBytes())
    }

    @Test
    fun `has is false for missing or foreign files`() {
        val store = cache().forDocument(7L, "k")
        store.write(1, byteArrayOf(1))
        assertFalse(store.has(0))
        assertFalse(store.has(2))
    }

    // -- accounting / eviction --

    @Test
    fun `totalBytes sums every voice directory of a document`() {
        val c = cache()
        c.forDocument(3L, "voiceA").write(0, ByteArray(10))
        c.forDocument(3L, "voiceA").write(1, ByteArray(20))
        c.forDocument(3L, "voiceB").write(0, ByteArray(5))
        c.forDocument(4L, "voiceA").write(0, ByteArray(100)) // other doc
        assertEquals(35L, c.totalBytes(3L))
        assertEquals(100L, c.totalBytes(4L))
        assertEquals(0L, c.totalBytes(99L))
    }

    @Test
    fun `bound store totalBytes covers only its voice directory`() {
        val c = cache()
        val a = c.forDocument(3L, "voiceA")
        a.write(0, ByteArray(10))
        c.forDocument(3L, "voiceB").write(0, ByteArray(5))
        assertEquals(10L, a.totalBytes())
    }

    @Test
    fun `deleteDoc removes every chunk of the document`() {
        val c = cache()
        val store = c.forDocument(3L, "voiceA")
        store.write(0, ByteArray(10))
        c.forDocument(3L, "voiceB").write(0, ByteArray(5))
        c.forDocument(4L, "voiceA").write(0, ByteArray(7))
        assertTrue(c.deleteDoc(3L))
        assertFalse(store.has(0))
        assertEquals(0L, c.totalBytes(3L))
        assertEquals(7L, c.totalBytes(4L)) // untouched
    }

    @Test
    fun `evictOthers drops stale voice directories and keeps the active one`() {
        val c = cache()
        val keep = c.forDocument(3L, "voiceB")
        c.forDocument(3L, "voiceA").write(0, ByteArray(10))
        keep.write(0, ByteArray(5))
        c.forDocument(3L, "voiceC").write(0, ByteArray(2))
        c.evictOthers(3L, keepVoiceKey = "voiceB")
        assertTrue(keep.has(0))
        assertEquals(5L, c.totalBytes(3L))
    }

    @Test
    fun `evictOthers on a missing document is a no-op`() {
        cache().evictOthers(42L, keepVoiceKey = "voiceA")
    }
}
