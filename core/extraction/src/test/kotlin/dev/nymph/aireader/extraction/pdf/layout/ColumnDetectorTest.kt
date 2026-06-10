package dev.nymph.aireader.extraction.pdf.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnDetectorTest {

    @Test
    fun `single column page yields one unit with lines in y order`() {
        val lines = page(0) {
            line("third", x = 72f, y = 158f)
            line("first", x = 72f, y = 130f)
            line("second", x = 80f, y = 144f) // indented but still the same column
        }
        val units = ColumnDetector.detect(lines)
        assertEquals(1, units.size)
        assertEquals(72f, units[0].xStart)
        assertEquals(listOf("first", "second", "third"), units[0].lines.map { it.text })
    }

    @Test
    fun `two column page yields left then right units each in y order`() {
        val lines = page(0) {
            // Interleave y positions across columns to prove ordering is per column.
            line("left one is some text here", x = 50f, y = 120f)
            line("right one is some text here", x = 312f, y = 120f)
            line("left two is some text here", x = 50f, y = 134f)
            line("right two is some text here", x = 312f, y = 134f)
            line("left three is some text xx", x = 50f, y = 148f)
            line("right three is some text x", x = 312f, y = 148f)
        }
        val units = ColumnDetector.detect(lines)
        assertEquals(2, units.size)
        assertEquals(
            listOf("left one is some text here", "left two is some text here", "left three is some text xx"),
            units[0].lines.map { it.text },
        )
        assertEquals(
            listOf("right one is some text here", "right two is some text here", "right three is some text x"),
            units[1].lines.map { it.text },
        )
        assertTrue(units[0].xStart < units[1].xStart)
    }

    @Test
    fun `narrow marginalia outside the column is dropped`() {
        val lines = page(0) {
            para(
                "The committee then turned to the question of how the",
                "new infrastructure would be funded over the following",
                "decade, a question that had been postponed repeatedly",
                "during earlier sessions while the budget office paused",
                "estimates from each of the regional planning councils",
                "involved in the original proposal.",
                startY = 130f,
            )
            line("A margin note", x = 470f, y = 200f, fontSize = 9f)
            line("about the topic", x = 470f, y = 211f, fontSize = 9f)
            line("set in the side", x = 470f, y = 222f, fontSize = 9f)
        }
        val units = ColumnDetector.detect(lines)
        assertEquals(1, units.size)
        assertEquals(6, units[0].lines.size)
        assertTrue(units[0].lines.none { it.x == 470f })
    }

    @Test
    fun `narrow cluster is not promoted to a second column`() {
        // Sidebar holds >= 25% of lines but is far too narrow to be a real column.
        val lines = page(0) {
            para(
                "Body line number one with plenty of words in it here",
                "body line number two with plenty of words in it here",
                "body line number three with plenty of words in here",
                "body line number four with plenty of words in here.",
                startY = 130f,
            )
            line("note a", x = 470f, y = 130f, fontSize = 9f)
            line("note b", x = 470f, y = 141f, fontSize = 9f)
        }
        val units = ColumnDetector.detect(lines)
        assertEquals(1, units.size)
        assertTrue(units[0].lines.all { it.x == 72f })
    }

    @Test
    fun `wide outside line is kept and assigned to the nearest column`() {
        val lines = page(0) {
            para(
                "Main column text line one with plenty of words here",
                "main column text line two with plenty of words here",
                "main column text line three with plenty of words xx",
                "main column text line four with plenty of words xx.",
                startY = 130f,
            )
            // Wide enough (>= 40% of column width) that it is not marginalia.
            line("A wide pull quote spans out", x = 470f, y = 300f, fontSize = 12f, width = 160f)
        }
        val units = ColumnDetector.detect(lines)
        assertEquals(1, units.size)
        assertTrue(units[0].lines.any { it.text == "A wide pull quote spans out" })
    }

    @Test
    fun `empty page yields no units`() {
        assertEquals(emptyList(), ColumnDetector.detect(emptyList()))
    }
}
