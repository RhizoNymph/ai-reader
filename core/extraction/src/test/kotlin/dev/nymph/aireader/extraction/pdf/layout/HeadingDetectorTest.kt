package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadingDetectorTest {

    private val body = 12f

    private fun lineOf(text: String, fontSize: Float): PdfLine = PdfLine(
        text = text,
        x = 72f,
        y = 100f,
        width = text.length * fontSize * 0.5f,
        height = fontSize,
        fontSizePt = fontSize,
        pageIndex = 0,
        pageWidth = PAGE_W,
        pageHeight = PAGE_H,
    )

    @Test
    fun `size tiers map to heading levels`() {
        assertEquals(1, HeadingDetector.headingLevel(lineOf("Chapter One", 20f), body)) // 1.67x
        assertEquals(2, HeadingDetector.headingLevel(lineOf("A Section", 17f), body)) // 1.42x
        assertEquals(3, HeadingDetector.headingLevel(lineOf("A Subsection", 14f), body)) // 1.17x
    }

    @Test
    fun `tier boundaries are inclusive`() {
        assertEquals(3, HeadingDetector.headingLevel(lineOf("Smallest Heading", 13.8f), body)) // exactly 1.15x
        assertEquals(2, HeadingDetector.headingLevel(lineOf("Mid Heading", 16.2f), body)) // exactly 1.35x
        assertEquals(1, HeadingDetector.headingLevel(lineOf("Big Heading", 19.2f), body)) // exactly 1.6x
    }

    @Test
    fun `body sized line is not a heading`() {
        assertNull(HeadingDetector.headingLevel(lineOf("Just a body line", 12f), body))
        assertNull(HeadingDetector.headingLevel(lineOf("Slightly large", 13f), body)) // 1.08x
    }

    @Test
    fun `line ending in a period is not a heading`() {
        assertNull(HeadingDetector.headingLevel(lineOf("This ends with a period.", 20f), body))
    }

    @Test
    fun `long line is not a heading`() {
        val long = "An extremely long line that keeps going and going and going until it has well over ninety characters"
        assertNull(HeadingDetector.headingLevel(lineOf(long, 14f), body))
    }
}
