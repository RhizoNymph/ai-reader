package dev.nymph.aireader.playback.export

import kotlin.math.roundToInt

/**
 * Pure linear-interpolation resampling of 16-bit mono PCM.
 *
 * Exists for one narrow job: an exported audiobook is a single fixed-rate
 * track, but a document's cached chunks can disagree on rate — an engine
 * change of heart is impossible mid-document, yet synthesis-failure fallback
 * chunks are silence at the scheduler's fallback rate (24 kHz) while a Piper
 * voice renders at 22.05 kHz. Linear interpolation is transparent for speech
 * at these neighboring rates, and the mismatched chunks are usually silence
 * anyway; a polyphase filter would be over-engineering.
 */
object PcmResampler {

    /**
     * Resample [samples] from [fromRate] to [toRate]. Identity (same array
     * contents) when the rates match. Output position `j` reads source
     * position `j * fromRate / toRate`, interpolating between neighbors and
     * clamping past the final sample.
     */
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0) { "fromRate must be positive: $fromRate" }
        require(toRate > 0) { "toRate must be positive: $toRate" }
        if (fromRate == toRate || samples.isEmpty()) return samples.copyOf()

        val outLength = (samples.size.toLong() * toRate / fromRate).toInt()
        val step = fromRate.toDouble() / toRate
        val out = ShortArray(outLength)
        val last = samples.size - 1
        for (j in 0 until outLength) {
            val srcPos = j * step
            val i = srcPos.toInt().coerceAtMost(last)
            val next = (i + 1).coerceAtMost(last)
            val frac = srcPos - i
            val value = samples[i] + (samples[next] - samples[i]) * frac
            out[j] = value.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }
}
