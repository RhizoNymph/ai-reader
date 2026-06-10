package dev.nymph.aireader.playback.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SynthWindowTest {

    private fun cachedSet(vararg indices: Int): (Int) -> Boolean = indices.toSet()::contains

    // -- nextTarget: lookahead window --

    @Test
    fun `picks the first uncached index at the playhead`() {
        assertEquals(0, nextTarget(0, 10, 4, 0, Long.MAX_VALUE, cachedSet()))
    }

    @Test
    fun `picks the first uncached index inside the window`() {
        assertEquals(2, nextTarget(0, 10, 4, 0, Long.MAX_VALUE, cachedSet(0, 1)))
        assertEquals(5, nextTarget(3, 10, 4, 0, Long.MAX_VALUE, cachedSet(3, 4, 6)))
    }

    @Test
    fun `window is anchored at the playhead not the document start`() {
        assertEquals(7, nextTarget(7, 20, 3, 0, Long.MAX_VALUE, cachedSet(0, 1, 2)))
    }

    @Test
    fun `window clamps at the end of the document`() {
        // playhead 8, lookahead 5, size 10 -> window [8, 10)
        assertEquals(9, nextTarget(8, 10, 5, 0, Long.MAX_VALUE, cachedSet(8)))
    }

    // -- nextTarget: beyond-window fill --

    @Test
    fun `fills beyond the window when it is fully cached and budget remains`() {
        assertEquals(4, nextTarget(0, 10, 4, 100, 1_000, cachedSet(0, 1, 2, 3)))
        assertEquals(6, nextTarget(0, 10, 4, 100, 1_000, cachedSet(0, 1, 2, 3, 4, 5)))
    }

    @Test
    fun `does not fill beyond the window when the budget is exhausted`() {
        assertNull(nextTarget(0, 10, 4, 1_000, 1_000, cachedSet(0, 1, 2, 3)))
        assertNull(nextTarget(0, 10, 4, 2_000, 1_000, cachedSet(0, 1, 2, 3)))
    }

    @Test
    fun `window synthesis ignores the byte budget`() {
        // The playhead window must always be filled or playback stalls.
        assertEquals(1, nextTarget(0, 10, 4, Long.MAX_VALUE, 0, cachedSet(0)))
    }

    @Test
    fun `returns null when everything from the playhead onward is cached`() {
        assertNull(nextTarget(2, 5, 4, 0, Long.MAX_VALUE, cachedSet(2, 3, 4)))
    }

    @Test
    fun `returns null for an empty document`() {
        assertNull(nextTarget(0, 0, 4, 0, Long.MAX_VALUE, cachedSet()))
    }

    @Test
    fun `does not look behind the playhead`() {
        // Index 0 is uncached but behind the playhead; forward fill only.
        assertNull(nextTarget(1, 3, 4, 0, Long.MAX_VALUE, cachedSet(1, 2)))
    }

    // -- cachedThrough --

    @Test
    fun `cachedThrough is playhead minus one when the playhead is uncached`() {
        assertEquals(-1, cachedThrough(0, 10) { false })
        assertEquals(4, cachedThrough(5, 10, cachedSet(0, 1, 6)))
    }

    @Test
    fun `cachedThrough is the last contiguous cached index`() {
        assertEquals(2, cachedThrough(0, 10, cachedSet(0, 1, 2, 4)))
        assertEquals(6, cachedThrough(5, 10, cachedSet(5, 6, 8)))
    }

    @Test
    fun `cachedThrough reaches the end when everything is cached`() {
        assertEquals(9, cachedThrough(0, 10) { true })
        assertEquals(9, cachedThrough(7, 10) { true })
    }

    @Test
    fun `cachedThrough on an empty document`() {
        assertEquals(-1, cachedThrough(0, 0) { true })
    }
}
