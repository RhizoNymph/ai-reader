package dev.nymph.aireader.extraction.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineGrouperTest {

    private val pageW = 612f
    private val pageH = 792f

    /** A run whose glyphs are evenly spaced from [x], one per character. */
    private fun run(text: String, x: Float, y: Float, fontSize: Float = 15f, glyphWidth: Float = 7.5f): TextFragment {
        val starts = FloatArray(text.length) { x + it * glyphWidth }
        return TextFragment(
            text = text,
            x = x,
            xEnd = x + text.length * glyphWidth,
            y = y,
            height = fontSize,
            fontSizePt = fontSize,
            glyphXStarts = starts,
        )
    }

    private fun group(vararg frags: TextFragment) =
        LineGrouper.group(frags.toList(), pageIndex = 0, pageWidth = pageW, pageHeight = pageH)

    @Test
    fun `fragments on the same baseline join into one line`() {
        val lines = group(
            run("Hello", x = 72f, y = 100f),
            run("world", x = 72f + 5 * 7.5f + 4f, y = 100f), // one small space gap
        )
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `a wide gutter gap splits one baseline into two lines`() {
        // Body dominates so the left edge is the body (x=300); the left rail word
        // is separated by a gutter far wider than a space.
        val lines = group(
            run("Rail", x = 60f, y = 100f),
            run("This is the main body text that dominates", x = 300f, y = 100f),
        )
        assertEquals(2, lines.map { it.text }.size)
        assertTrue(lines.any { it.text == "Rail" })
        assertTrue(lines.any { it.text.startsWith("This is the main body") })
    }

    @Test
    fun `different baselines are separate lines in y order`() {
        val lines = group(
            run("second", x = 72f, y = 120f),
            run("first", x = 72f, y = 100f),
        )
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun `a rail word touching the body edge with no gap still splits`() {
        // The rail word ends exactly where the body begins (no whitespace gap),
        // as a real PDF side rail does; the body column dominates the page.
        val body = run("This body line is long and carries the page's text mass", x = 159f, y = 100f)
        val touching = run("Sidebar", x = 60f, y = 120f) // ends at 60+7*7.5=112.5 < 159, own line
        val bodyLine2 = run("more body text to keep the column dominant here now", x = 159f, y = 120f)
        val lines = group(body, touching, bodyLine2)
        // "Sidebar" is far left and short; it stays its own (droppable) line.
        assertTrue(lines.any { it.text == "Sidebar" })
        assertTrue(lines.none { it.text.contains("Sidebarmore") })
    }

    @Test
    fun `an atomic run glued across the body edge is cut at the edge`() {
        // One fragment "Macroeconomicsscience..." spanning rail x=55 into body
        // x=159 with no internal gap — only the glyph x-starts can separate it.
        val glued = run("Macroeconomicsscience and more body words here after", x = 55f, y = 100f)
        // Extra body lines so x=159 is the dominant (body) left edge.
        val b1 = run("another body line here that is nice and long enough now", x = 159f, y = 114f)
        val b2 = run("yet another body line here that is also long enough too", x = 159f, y = 128f)
        val lines = group(glued, b1, b2)
        assertTrue(lines.any { it.text == "Macroeconomics" }, "rail word should split off: ${lines.map { it.text }}")
        assertTrue(lines.any { it.text.startsWith("science and more body") })
        assertTrue(lines.none { it.text.startsWith("Macroeconomicsscience") })
    }

    @Test
    fun `a small rail word abutting large display type splits`() {
        // Title at font 46 next to a rail entry at font 15; the gutter threshold
        // uses the smaller font so the rail word still separates.
        val title = run("Exponential", x = 159f, y = 100f, fontSize = 46f, glyphWidth = 23f)
        val rail = run("safety", x = 55f, y = 100f, fontSize = 15f)
        val lines = group(title, rail)
        assertTrue(lines.any { it.text == "safety" })
        assertTrue(lines.any { it.text == "Exponential" })
        assertTrue(lines.none { it.text.contains("safetyExponential") })
    }

    @Test
    fun `an indented first line is not treated as a rail`() {
        // A hanging indent sits only a little left of the body and must not be cut.
        val lines = group(
            run("body text one that dominates the page nicely here", x = 100f, y = 100f),
            run("body text two also dominating the column width here", x = 100f, y = 114f),
            run("Indented start of a paragraph continues normally here", x = 90f, y = 128f),
        )
        assertTrue(lines.any { it.text.startsWith("Indented start") })
    }
}
