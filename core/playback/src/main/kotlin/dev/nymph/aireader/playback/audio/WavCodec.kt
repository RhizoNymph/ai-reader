package dev.nymph.aireader.playback.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure PCM-to-WAV encoding for synthesized sentence chunks.
 *
 * Output is always 16-bit signed little-endian mono PCM in a canonical
 * 44-byte-header RIFF container, which Media3's WAV extractor plays directly.
 * No Android dependencies; fully unit-tested on the JVM.
 */
object WavCodec {

    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2

    /** Number of samples representing [ms] milliseconds at [sampleRate] (truncating). */
    fun silenceSampleCount(sampleRate: Int, ms: Int): Int {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(ms >= 0) { "ms must be non-negative: $ms" }
        return (sampleRate.toLong() * ms / 1000L).toInt()
    }

    /**
     * Returns a copy of [samples] followed by [ms] milliseconds of silence.
     * Used to honor [dev.nymph.aireader.model.SpeakableSentence.pauseAfterMs]
     * so paragraph/heading pauses are baked into the cached chunk.
     */
    fun appendSilence(samples: FloatArray, sampleRate: Int, ms: Int): FloatArray {
        val extra = silenceSampleCount(sampleRate, ms)
        // copyOf zero-fills the appended region, which is PCM silence.
        return samples.copyOf(samples.size + extra)
    }

    /**
     * Encode mono float PCM (nominal range [-1, 1]; out-of-range values are
     * clamped, NaN becomes silence) into a 16-bit PCM WAV byte array.
     */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(RIFF)
        buffer.putInt(36 + dataBytes) // RIFF chunk size
        buffer.put(WAVE)
        buffer.put(FMT)
        buffer.putInt(16) // fmt chunk size
        buffer.putShort(1) // audio format: PCM
        buffer.putShort(1) // channels: mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * BYTES_PER_SAMPLE) // byte rate (mono)
        buffer.putShort(BYTES_PER_SAMPLE.toShort()) // block align
        buffer.putShort(16) // bits per sample
        buffer.put(DATA)
        buffer.putInt(dataBytes)

        for (sample in samples) {
            buffer.putShort(toPcm16(sample))
        }
        return buffer.array()
    }

    private fun toPcm16(sample: Float): Short {
        if (sample.isNaN()) return 0
        val clamped = sample.coerceIn(-1f, 1f)
        return (clamped * Short.MAX_VALUE).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val WAVE = byteArrayOf(0x57, 0x41, 0x56, 0x45) // "WAVE"
    private val FMT = byteArrayOf(0x66, 0x6D, 0x74, 0x20) // "fmt "
    private val DATA = byteArrayOf(0x64, 0x61, 0x74, 0x61) // "data"
}
