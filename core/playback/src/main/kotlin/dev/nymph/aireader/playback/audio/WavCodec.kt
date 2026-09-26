package dev.nymph.aireader.playback.audio

import dev.nymph.aireader.model.Outcome
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Structured failure when reading a WAV chunk back from the cache. */
sealed interface WavError {
    val message: String

    /** Not a canonical RIFF/WAVE container, or sizes are inconsistent. */
    data class Malformed(override val message: String) : WavError

    /** A valid WAV, but not the 16-bit mono PCM this codec produces. */
    data class Unsupported(override val message: String) : WavError
}

/** The format half of a canonical chunk header. */
class WavFormat(val sampleRate: Int, val dataBytes: Int)

/** Decoded chunk audio: 16-bit mono PCM samples at [sampleRate]. */
class PcmAudio(val sampleRate: Int, val samples: ShortArray)

/**
 * Pure PCM-to-WAV encoding for synthesized sentence chunks, and the matching
 * decoder used when stitching cached chunks into a full-book export.
 *
 * Output is always 16-bit signed little-endian mono PCM in a canonical
 * 44-byte-header RIFF container, which Media3's WAV extractor plays directly.
 * The decoder accepts exactly that canonical shape and nothing else — cache
 * chunks are the only input it will ever see, so anything off-spec is a
 * corrupt file, not a format to accommodate.
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

    /**
     * Read the sample rate and data size from a canonical chunk header.
     * Needs only the first [HEADER_BYTES] bytes of the file, so a rate scan
     * over a whole document never reads full chunk payloads.
     */
    fun decodeHeader(bytes: ByteArray): Outcome<WavFormat, WavError> {
        if (bytes.size < HEADER_BYTES) {
            return Outcome.Err(WavError.Malformed("header truncated: ${bytes.size} bytes"))
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!hasTag(bytes, 0, RIFF)) return malformed("missing RIFF tag")
        if (!hasTag(bytes, 8, WAVE)) return malformed("missing WAVE tag")
        if (!hasTag(bytes, 12, FMT)) return malformed("missing fmt tag")
        if (!hasTag(bytes, 36, DATA)) return malformed("missing data tag")
        val fmtSize = b.getInt(16)
        if (fmtSize != 16) return unsupported("non-canonical fmt chunk size: $fmtSize")
        val formatCode = b.getShort(20).toInt()
        if (formatCode != 1) return unsupported("non-PCM format code: $formatCode")
        val channels = b.getShort(22).toInt()
        if (channels != 1) return unsupported("expected mono, got $channels channels")
        val bitsPerSample = b.getShort(34).toInt()
        if (bitsPerSample != 16) return unsupported("expected 16-bit samples, got $bitsPerSample")
        val sampleRate = b.getInt(24)
        if (sampleRate <= 0) return malformed("non-positive sample rate: $sampleRate")
        val dataBytes = b.getInt(40)
        if (dataBytes < 0 || dataBytes % BYTES_PER_SAMPLE != 0) {
            return malformed("bad data size: $dataBytes")
        }
        return Outcome.Ok(WavFormat(sampleRate, dataBytes))
    }

    /** Decode a complete canonical chunk file back into PCM samples. */
    fun decode(bytes: ByteArray): Outcome<PcmAudio, WavError> {
        val format = when (val header = decodeHeader(bytes)) {
            is Outcome.Err -> return Outcome.Err(header.error)
            is Outcome.Ok -> header.value
        }
        if (bytes.size - HEADER_BYTES != format.dataBytes) {
            return malformed(
                "data size ${format.dataBytes} disagrees with payload ${bytes.size - HEADER_BYTES}",
            )
        }
        val buf = ByteBuffer.wrap(bytes, HEADER_BYTES, format.dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(format.dataBytes / BYTES_PER_SAMPLE) { buf.short }
        return Outcome.Ok(PcmAudio(format.sampleRate, samples))
    }

    private fun malformed(reason: String): Outcome.Err<WavError> =
        Outcome.Err(WavError.Malformed(reason))

    private fun unsupported(reason: String): Outcome.Err<WavError> =
        Outcome.Err(WavError.Unsupported(reason))

    private fun hasTag(bytes: ByteArray, offset: Int, tag: ByteArray): Boolean {
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i]) return false
        }
        return true
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
