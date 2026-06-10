package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.TtsError
import dev.nymph.aireader.playback.audio.WavCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ChunkSynthesisTest {

    private fun dataBytes(wav: ByteArray): Int =
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

    @Test
    fun `successful synthesis appends pauseAfterMs of silence`() = runTest {
        val engine = FakeTtsEngine(sampleRate = 24_000, samplesPerSentence = 2_400)
        val sentence = sentences(1, pauseMs = 250)[0]
        val render = renderChunk(engine, sentence, testVoice, fallbackSampleRate = 8_000, fallbackSilenceMs = 300)
        assertNull(render.error)
        assertEquals(24_000, render.sampleRate)
        val expectedSamples = 2_400 + WavCodec.silenceSampleCount(24_000, 250)
        assertEquals(expectedSamples * 2, dataBytes(render.wavBytes))
    }

    @Test
    fun `zero pause yields exactly the engine samples`() = runTest {
        val engine = FakeTtsEngine(sampleRate = 22_050, samplesPerSentence = 1_000)
        val render = renderChunk(engine, sentences(1, pauseMs = 0)[0], testVoice, 8_000, 300)
        assertEquals(1_000 * 2, dataBytes(render.wavBytes))
        assertEquals(22_050, render.sampleRate)
    }

    @Test
    fun `failed synthesis produces a silence chunk and reports the error`() = runTest {
        val engine = FakeTtsEngine()
        engine.failTexts = setOf("s0")
        val sentence = sentences(1, pauseMs = 100)[0]
        val render = renderChunk(engine, sentence, testVoice, fallbackSampleRate = 24_000, fallbackSilenceMs = 300)
        assertIs<TtsError.SynthesisFailed>(render.error)
        assertEquals(24_000, render.sampleRate)
        // fallback silence + the sentence's own pause, all at the fallback rate
        val expectedSamples = WavCodec.silenceSampleCount(24_000, 300 + 100)
        assertEquals(expectedSamples * 2, dataBytes(render.wavBytes))
        // every sample is silence
        val pcm = render.wavBytes.copyOfRange(44, render.wavBytes.size)
        assertTrue(pcm.all { it.toInt() == 0 })
    }

    @Test
    fun `failure chunk uses the provided fallback sample rate`() = runTest {
        val engine = FakeTtsEngine()
        engine.failTexts = setOf("s0")
        val render = renderChunk(engine, sentences(1)[0], testVoice, fallbackSampleRate = 22_050, fallbackSilenceMs = 200)
        assertEquals(22_050, render.sampleRate)
        assertEquals(WavCodec.silenceSampleCount(22_050, 200) * 2, dataBytes(render.wavBytes))
    }
}
