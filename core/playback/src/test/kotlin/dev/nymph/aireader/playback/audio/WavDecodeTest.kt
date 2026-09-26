package dev.nymph.aireader.playback.audio

import dev.nymph.aireader.model.Outcome
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WavDecodeTest {

    private fun decodeOk(wav: ByteArray): PcmAudio {
        val result = WavCodec.decode(wav)
        assertIs<Outcome.Ok<PcmAudio>>(result, "expected Ok, got $result")
        return result.value
    }

    private fun decodeErr(wav: ByteArray): WavError {
        val result = WavCodec.decode(wav)
        assertIs<Outcome.Err<WavError>>(result, "expected Err, got $result")
        return result.error
    }

    /** Flip the byte at [offset] so a magic tag no longer matches. */
    private fun corrupt(wav: ByteArray, offset: Int): ByteArray =
        wav.copyOf().also { it[offset] = 'X'.code.toByte() }

    private fun withLeShort(wav: ByteArray, offset: Int, value: Short): ByteArray =
        wav.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value)
        }

    private fun withLeInt(wav: ByteArray, offset: Int, value: Int): ByteArray =
        wav.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
        }

    // -- round trips --

    @Test
    fun `round trip preserves sample rate and pcm values`() {
        val wav = WavCodec.encode(floatArrayOf(0f, 0.5f, 1.0f, -1.0f), 22_050)
        val pcm = decodeOk(wav)
        assertEquals(22_050, pcm.sampleRate)
        assertContentEquals(shortArrayOf(0, 16_384, 32_767, -32_767), pcm.samples)
    }

    @Test
    fun `round trip of encoded silence is all zero`() {
        val pcm = decodeOk(WavCodec.encode(FloatArray(1_000), 24_000))
        assertEquals(1_000, pcm.samples.size)
        assertTrue(pcm.samples.all { it == 0.toShort() })
    }

    @Test
    fun `empty wav decodes to zero samples`() {
        val pcm = decodeOk(WavCodec.encode(FloatArray(0), 24_000))
        assertEquals(0, pcm.samples.size)
        assertEquals(24_000, pcm.sampleRate)
    }

    // -- header-only decoding --

    @Test
    fun `decodeHeader reads rate and data size from the header prefix alone`() {
        val wav = WavCodec.encode(FloatArray(5_000), 22_050)
        val header = wav.copyOfRange(0, 44)
        val result = WavCodec.decodeHeader(header)
        assertIs<Outcome.Ok<WavFormat>>(result)
        assertEquals(22_050, result.value.sampleRate)
        assertEquals(10_000, result.value.dataBytes)
    }

    @Test
    fun `decodeHeader rejects fewer than 44 bytes`() {
        val wav = WavCodec.encode(FloatArray(10), 24_000)
        val result = WavCodec.decodeHeader(wav.copyOfRange(0, 43))
        assertIs<Outcome.Err<WavError>>(result)
        assertIs<WavError.Malformed>(result.error)
    }

    // -- malformed containers --

    @Test
    fun `rejects buffers shorter than a header`() {
        assertIs<WavError.Malformed>(decodeErr(ByteArray(0)))
        assertIs<WavError.Malformed>(decodeErr(ByteArray(43)))
    }

    @Test
    fun `rejects wrong riff magic`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(corrupt(wav, 0)))
    }

    @Test
    fun `rejects wrong wave magic`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(corrupt(wav, 8)))
    }

    @Test
    fun `rejects wrong fmt magic`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(corrupt(wav, 12)))
    }

    @Test
    fun `rejects wrong data magic`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(corrupt(wav, 36)))
    }

    @Test
    fun `rejects data size larger than the buffer`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(wav.copyOfRange(0, wav.size - 2)))
    }

    @Test
    fun `rejects declared data size mismatching the payload`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(withLeInt(wav, 40, 6)))
    }

    @Test
    fun `rejects non positive sample rate in header`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Malformed>(decodeErr(withLeInt(wav, 24, 0)))
        assertIs<WavError.Malformed>(decodeErr(withLeInt(wav, 24, -8_000)))
    }

    // -- unsupported formats --

    @Test
    fun `rejects non pcm format code`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Unsupported>(decodeErr(withLeShort(wav, 20, 3))) // IEEE float
    }

    @Test
    fun `rejects stereo`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Unsupported>(decodeErr(withLeShort(wav, 22, 2)))
    }

    @Test
    fun `rejects non 16 bit samples`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Unsupported>(decodeErr(withLeShort(wav, 34, 8)))
    }

    @Test
    fun `rejects non canonical fmt chunk size`() {
        val wav = WavCodec.encode(FloatArray(4), 24_000)
        assertIs<WavError.Unsupported>(decodeErr(withLeInt(wav, 16, 18)))
    }
}
