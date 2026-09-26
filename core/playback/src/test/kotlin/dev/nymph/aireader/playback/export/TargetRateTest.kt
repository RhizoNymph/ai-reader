package dev.nymph.aireader.playback.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetRateTest {

    @Test
    fun `single rate is chosen`() {
        assertEquals(24_000, chooseTargetRate(listOf(24_000)))
        assertEquals(22_050, chooseTargetRate(List(50) { 22_050 }))
    }

    @Test
    fun `majority rate wins over fallback silence chunks`() {
        // A Piper document (22.05 kHz) with a few 24 kHz fallback silence chunks.
        val rates = List(100) { 22_050 } + List(3) { 24_000 }
        assertEquals(22_050, chooseTargetRate(rates))
    }

    @Test
    fun `tie breaks to the higher rate`() {
        assertEquals(24_000, chooseTargetRate(listOf(22_050, 24_000)))
        assertEquals(24_000, chooseTargetRate(listOf(24_000, 22_050, 24_000, 22_050)))
    }

    @Test
    fun `empty input is rejected`() {
        assertFailsWith<IllegalArgumentException> { chooseTargetRate(emptyList()) }
    }
}
