package dev.nymph.aireader.extraction.pdf.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeatedFurnitureDetectorTest {

    private fun texts(pages: List<List<dev.nymph.aireader.extraction.pdf.PdfLine>>): List<List<String>> =
        pages.map { page -> page.map { it.text } }

    @Test
    fun `running header repeated on enough pages is removed`() {
        val pages = (0 until 5).map { i ->
            page(i) {
                line("A History of Everything", x = 180f, y = 36f, fontSize = 10f)
                para("Body text unique to page number $i goes here", startY = 200f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        cleaned.forEach { p ->
            assertTrue(p.none { it.text == "A History of Everything" })
            assertEquals(1, p.size)
        }
    }

    @Test
    fun `header with small y jitter still clusters and is removed`() {
        val ys = listOf(36f, 40f, 38f, 36f)
        val pages = ys.mapIndexed { i, y ->
            page(i) {
                line("Running Header", x = 180f, y = y, fontSize = 10f)
                para("Distinct body content for page $i", startY = 300f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        cleaned.forEach { p -> assertTrue(p.none { it.text == "Running Header" }) }
    }

    @Test
    fun `repeated phrase in the middle of the page survives`() {
        val pages = (0 until 6).map { i ->
            page(i) {
                line("the quick brown fox", x = 72f, y = 400f)
                para("Other body content for page $i", startY = 300f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        cleaned.forEach { p -> assertTrue(p.any { it.text == "the quick brown fox" }) }
    }

    @Test
    fun `line repeated on too few pages survives`() {
        val pages = (0 until 8).map { i ->
            page(i) {
                if (i < 2) line("Occasional banner", x = 180f, y = 36f, fontSize = 10f)
                para("Body content for page $i", startY = 300f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        assertEquals(2, cleaned.count { p -> p.any { it.text == "Occasional banner" } })
    }

    @Test
    fun `digit normalization groups varying numbers in headers`() {
        val pages = (0 until 4).map { i ->
            page(i) {
                line("Issue ${10 + i}", x = 180f, y = 36f, fontSize = 10f)
                para("Body content for page $i", startY = 300f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        cleaned.forEach { p -> assertTrue(p.none { it.text.startsWith("Issue") }) }
    }

    @Test
    fun `alternating parity headers are removed even at half the pages`() {
        val pages = (0 until 6).map { i ->
            page(i) {
                if (i % 2 == 0) {
                    line("Pride and Prejudice", x = 72f, y = 40f, fontSize = 10f)
                } else {
                    line("Chapter One", x = 420f, y = 40f, fontSize = 10f)
                }
                para("Body content for page $i", startY = 300f)
            }
        }
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        cleaned.forEach { p ->
            assertTrue(p.none { it.text == "Pride and Prejudice" || it.text == "Chapter One" })
        }
    }

    @Test
    fun `standalone page numbers are dropped regardless of repetition`() {
        val pages = listOf(
            page(0) {
                line("7", x = 300f, y = 756f, fontSize = 10f)
                para("Some body text on the only page", startY = 300f)
            },
        )
        assertEquals(listOf(listOf("Some body text on the only page")), texts(RepeatedFurnitureDetector.removeFurniture(pages)))
    }

    @Test
    fun `page number variants are recognized`() {
        val variants = listOf("iv", "XII", "Page 7", "Page 7 of 250", "7 of 250", "- 18 -")
        variants.forEach { variant ->
            val pages = listOf(
                page(0) {
                    line(variant, x = 300f, y = 756f, fontSize = 10f)
                    para("Body line that stays", startY = 300f)
                },
            )
            assertEquals(
                listOf(listOf("Body line that stays")),
                texts(RepeatedFurnitureDetector.removeFurniture(pages)),
                "expected '$variant' to be dropped",
            )
        }
    }

    @Test
    fun `prose lines containing digits are not mistaken for page numbers`() {
        val pages = listOf(
            page(0) {
                line("4 of the participants left early", x = 72f, y = 700f)
                para("Body line that stays", startY = 300f)
            },
        )
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
        assertTrue(cleaned[0].any { it.text == "4 of the participants left early" })
    }
}
