package dev.nymph.aireader.extraction.pdf.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class FootnoteDetectorTest {

    private val body = 12f

    @Test
    fun `trailing small-font run with digit marker in bottom band is dropped`() {
        val lines = page(0) {
            para(
                "Body text line one without trailing punctuation",
                "body text line two that ends the paragraph here.",
                startY = 130f,
            )
            line("1 The original manuscript differs here", x = 72f, y = 700f, fontSize = 8f)
            line("2 See the appendix for details", x = 72f, y = 710f, fontSize = 8f)
        }
        val kept = FootnoteDetector.remove(lines, body)
        assertEquals(
            listOf(
                "Body text line one without trailing punctuation",
                "body text line two that ends the paragraph here.",
            ),
            kept.map { it.text },
        )
    }

    @Test
    fun `continuation lines without markers are dropped with their footnote`() {
        val lines = page(0) {
            para("Body paragraph line that stays put here.", startY = 130f)
            line("* A starred footnote whose text wraps onto", x = 72f, y = 700f, fontSize = 8f)
            line("a second line without any marker at all", x = 72f, y = 710f, fontSize = 8f)
        }
        val kept = FootnoteDetector.remove(lines, body)
        assertEquals(listOf("Body paragraph line that stays put here."), kept.map { it.text })
    }

    @Test
    fun `small font run without a marker is kept`() {
        val lines = page(0) {
            para("Body paragraph line that stays put here.", startY = 130f)
            line("a small-type colophon line with no marker", x = 72f, y = 700f, fontSize = 8f)
        }
        val kept = FootnoteDetector.remove(lines, body)
        assertEquals(2, kept.size)
    }

    @Test
    fun `small font run above the bottom band is kept`() {
        val lines = page(0) {
            para("Body paragraph line that stays put here.", startY = 130f)
            line("1 small type but mid page so not a footnote", x = 72f, y = 400f, fontSize = 8f)
        }
        // y = 400 / 792 = 0.50 < 0.75, so the run does not start in the bottom band.
        val kept = FootnoteDetector.remove(lines.sortedBy { it.y }, body)
        assertEquals(2, kept.size)
    }

    @Test
    fun `marker line at body font size is kept`() {
        val lines = page(0) {
            para("Body paragraph line that stays put here.", startY = 130f)
            line("1 This is a numbered list item not a footnote", x = 72f, y = 700f, fontSize = 12f)
        }
        val kept = FootnoteDetector.remove(lines, body)
        assertEquals(2, kept.size)
    }
}
