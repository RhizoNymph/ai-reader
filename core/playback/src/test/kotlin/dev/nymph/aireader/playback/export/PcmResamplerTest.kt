package dev.nymph.aireader.playback.export

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmResamplerTest {

    @Test
    fun `same rate returns identical content`() {
        val input = shortArrayOf(1, -2, 3, -4, 5)
        assertContentEquals(input, PcmResampler.resample(input, 24_000, 24_000))
    }

    @Test
    fun `empty input yields empty output at any rate pair`() {
        assertEquals(0, PcmResampler.resample(ShortArray(0), 22_050, 24_000).size)
        assertEquals(0, PcmResampler.resample(ShortArray(0), 24_000, 22_050).size)
    }

    @Test
    fun `output length scales by the rate ratio`() {
        assertEquals(200, PcmResampler.resample(ShortArray(100), 22_050, 44_100).size)
        assertEquals(50, PcmResampler.resample(ShortArray(100), 44_100, 22_050).size)
        // 22050 -> 24000 on 441 samples: 441 * 24000 / 22050 = 480 exactly.
        assertEquals(480, PcmResampler.resample(ShortArray(441), 22_050, 24_000).size)
    }

    @Test
    fun `constant signal stays constant`() {
        val input = ShortArray(500) { 1_000 }
        val up = PcmResampler.resample(input, 22_050, 24_000)
        val down = PcmResampler.resample(input, 24_000, 22_050)
        assertTrue(up.all { it == 1_000.toShort() })
        assertTrue(down.all { it == 1_000.toShort() })
    }

    @Test
    fun `doubling interpolates midpoints linearly`() {
        val input = shortArrayOf(0, 100, 200, 300)
        val out = PcmResampler.resample(input, 22_050, 44_100)
        assertEquals(8, out.size)
        // Even outputs land exactly on inputs; odd outputs are midpoints.
        assertEquals(0, out[0].toInt())
        assertEquals(50, out[1].toInt())
        assertEquals(100, out[2].toInt())
        assertEquals(150, out[3].toInt())
        assertEquals(200, out[4].toInt())
        assertEquals(250, out[5].toInt())
        assertEquals(300, out[6].toInt())
        // Past the last input sample: clamped to the final value.
        assertEquals(300, out[7].toInt())
    }

    @Test
    fun `halving keeps every other sample`() {
        val input = shortArrayOf(0, 10, 20, 30, 40, 50, 60, 70)
        val out = PcmResampler.resample(input, 44_100, 22_050)
        assertContentEquals(shortArrayOf(0, 20, 40, 60), out)
    }

    @Test
    fun `a linear ramp stays a ramp within rounding error`() {
        val input = ShortArray(2_205) { it.toShort() } // 100 ms at 22.05 kHz
        val out = PcmResampler.resample(input, 22_050, 24_000)
        assertEquals(2_400, out.size)
        val slope = 22_050.0 / 24_000.0
        for (j in out.indices) {
            val expected = (j * slope).coerceAtMost((input.size - 1).toDouble())
            assertTrue(
                abs(out[j] - expected) <= 1.0,
                "sample $j: expected ~$expected, got ${out[j]}",
            )
        }
    }

    @Test
    fun `full scale values do not overflow`() {
        val input = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE)
        val out = PcmResampler.resample(input, 22_050, 44_100)
        assertTrue(out.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MAX_VALUE, out[1])
    }

    @Test
    fun `rejects non positive rates`() {
        assertFailsWith<IllegalArgumentException> { PcmResampler.resample(ShortArray(1), 0, 24_000) }
        assertFailsWith<IllegalArgumentException> { PcmResampler.resample(ShortArray(1), 24_000, 0) }
        assertFailsWith<IllegalArgumentException> { PcmResampler.resample(ShortArray(1), -1, 24_000) }
    }
}
