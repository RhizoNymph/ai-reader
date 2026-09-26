package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.playback.audio.WavCodec
import dev.nymph.aireader.playback.cache.CacheError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RenderMissingChunksTest {

    private val config = SynthesisConfig()

    @Test
    fun `renders every chunk of an empty cache in order`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val result = renderMissingChunks(sentences(5), engine, testVoice, store, config) { _, _ -> }
        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(listOf(0, 1, 2, 3, 4), store.written.keys.toList())
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), engine.calls)
    }

    @Test
    fun `skips chunks already in the cache`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        store.write(1, ByteArray(4))
        store.write(3, ByteArray(4))
        val result = renderMissingChunks(sentences(5), engine, testVoice, store, config) { _, _ -> }
        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(listOf("s0", "s2", "s4"), engine.calls)
        assertEquals(setOf(0, 1, 2, 3, 4), store.written.keys)
    }

    @Test
    fun `progress counts every sentence including cached ones`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        store.write(0, ByteArray(4))
        val seen = mutableListOf<Pair<Int, Int>>()
        renderMissingChunks(sentences(3), engine, testVoice, store, config) { completed, total ->
            seen += completed to total
        }
        assertContentEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun `a synthesis failure writes a silence chunk and continues`() = runTest {
        val engine = FakeTtsEngine()
        engine.failTexts = setOf("s1")
        val store = InMemoryChunkStore()
        val result = renderMissingChunks(sentences(3), engine, testVoice, store, config) { _, _ -> }
        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(setOf(0, 1, 2), store.written.keys)
        // The fallback chunk is pure silence at the fallback duration.
        val silence = WavCodec.decode(store.written.getValue(1))
        assertIs<Outcome.Ok<dev.nymph.aireader.playback.audio.PcmAudio>>(silence)
        assertTrue(silence.value.samples.all { it == 0.toShort() })
    }

    @Test
    fun `a cache write failure aborts with the error`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        store.failWrites = true
        val result = renderMissingChunks(sentences(3), engine, testVoice, store, config) { _, _ -> }
        assertIs<Outcome.Err<CacheError>>(result)
        assertEquals(listOf("s0"), engine.calls)
    }

    @Test
    fun `no sentences is a trivial success`() = runTest {
        val engine = FakeTtsEngine()
        val result = renderMissingChunks(emptyList(), engine, testVoice, InMemoryChunkStore(), config) { _, _ -> }
        assertIs<Outcome.Ok<Unit>>(result)
        assertTrue(engine.calls.isEmpty())
    }
}
