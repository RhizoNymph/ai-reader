package dev.nymph.aireader.playback.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class WavCodecTest {

    private fun le(wav: ByteArray): ByteBuffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

    private fun ascii(wav: ByteArray, offset: Int, len: Int): String =
        String(wav, offset, len, Charsets.US_ASCII)

    private fun pcmSamples(wav: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((wav.size - 44) / 2) { buf.short }
    }

    // -- header --

    @Test
    fun `header fields are exact for a three sample file`() {
        val wav = WavCodec.encode(FloatArray(3), 24_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        val b = le(wav)
        assertEquals(36 + 6, b.getInt(4)) // RIFF chunk size
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, b.getInt(16)) // fmt chunk size
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(24_000, b.getInt(24)) // sample rate
        assertEquals(48_000, b.getInt(28)) // byte rate = rate * 1ch * 2B
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(6, b.getInt(40)) // data size
    }

    @Test
    fun `header carries the given sample rate`() {
        val wav = WavCodec.encode(FloatArray(10), 22_050)
        val b = le(wav)
        assertEquals(22_050, b.getInt(24))
        assertEquals(44_100, b.getInt(28))
    }

    // -- sample encoding --

    @Test
    fun `samples encode as 16 bit little endian`() {
        val wav = WavCodec.encode(floatArrayOf(0f, 0.5f, 1.0f, -1.0f), 24_000)
        val s = pcmSamples(wav)
        assertEquals(0, s[0].toInt())
        assertEquals(16_384, s[1].toInt()) // round(0.5 * 32767)
        assertEquals(32_767, s[2].toInt())
        assertEquals(-32_767, s[3].toInt())
        // raw little-endian byte order for 0.5 -> 16384 = 0x4000
        assertEquals(0x00, wav[44 + 2].toInt() and 0xFF)
        assertEquals(0x40, wav[44 + 3].toInt() and 0xFF)
    }

    @Test
    fun `out of range samples clamp to full scale`() {
        val wav = WavCodec.encode(
            floatArrayOf(2f, -3f, Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
            24_000,
        )
        val s = pcmSamples(wav)
        assertEquals(32_767, s[0].toInt())
        assertEquals(-32_767, s[1].toInt())
        assertEquals(32_767, s[2].toInt())
        assertEquals(-32_767, s[3].toInt())
        assertEquals(32_767, s[4].toInt())
        assertEquals(-32_767, s[5].toInt())
    }

    @Test
    fun `nan samples encode as silence`() {
        val s = pcmSamples(WavCodec.encode(floatArrayOf(Float.NaN), 24_000))
        assertEquals(0, s[0].toInt())
    }

    // -- lengths --

    @Test
    fun `zero length input yields a valid empty wav`() {
        val wav = WavCodec.encode(FloatArray(0), 24_000)
        assertEquals(44, wav.size)
        val b = le(wav)
        assertEquals(36, b.getInt(4))
        assertEquals(0, b.getInt(40))
    }

    @Test
    fun `odd sample counts produce even data sizes`() {
        for (n in intArrayOf(1, 3, 7, 99)) {
            val wav = WavCodec.encode(FloatArray(n), 24_000)
            assertEquals(44 + n * 2, wav.size)
            assertEquals(n * 2, le(wav).getInt(40))
            assertEquals(0, le(wav).getInt(40) % 2)
        }
    }

    @Test
    fun `rejects non positive sample rate`() {
        assertFailsWith<IllegalArgumentException> { WavCodec.encode(FloatArray(1), 0) }
        assertFailsWith<IllegalArgumentException> { WavCodec.encode(FloatArray(1), -8000) }
    }

    // -- silence math --

    @Test
    fun `silence sample count math`() {
        assertEquals(6_000, WavCodec.silenceSampleCount(24_000, 250))
        assertEquals(7_342, WavCodec.silenceSampleCount(22_050, 333)) // truncates 7342.65
        assertEquals(8, WavCodec.silenceSampleCount(8_000, 1))
        assertEquals(0, WavCodec.silenceSampleCount(24_000, 0))
        assertEquals(44_100, WavCodec.silenceSampleCount(44_100, 1000))
    }

    @Test
    fun `silence sample count rejects bad input`() {
        assertFailsWith<IllegalArgumentException> { WavCodec.silenceSampleCount(0, 100) }
        assertFailsWith<IllegalArgumentException> { WavCodec.silenceSampleCount(24_000, -1) }
    }

    @Test
    fun `appendSilence appends zeros and preserves the prefix`() {
        val original = floatArrayOf(0.1f, -0.2f, 0.3f)
        val padded = WavCodec.appendSilence(original, 24_000, 250)
        assertEquals(3 + 6_000, padded.size)
        assertContentEquals(original, padded.copyOfRange(0, 3))
        assertTrue(padded.drop(3).all { it == 0f })
    }

    @Test
    fun `appendSilence with zero ms returns an equal copy`() {
        val original = floatArrayOf(0.5f, -0.5f)
        val padded = WavCodec.appendSilence(original, 24_000, 0)
        assertContentEquals(original, padded)
        assertNotSame(original, padded)
    }

    @Test
    fun `encoded silence duration matches the data size`() {
        val padded = WavCodec.appendSilence(FloatArray(100), 22_050, 500)
        val wav = WavCodec.encode(padded, 22_050)
        val expectedSamples = 100 + 11_025
        assertEquals(expectedSamples * 2, le(wav).getInt(40))
    }
}
