package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.model.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals

/** End-to-end scenarios through [LayoutAnalyzer.analyze] on synthetic pages. */
class LayoutAnalyzerTest {

    // (a) Book pages: running header + centered page number.
    @Test
    fun `book pages lose running header and page numbers`() {
        val paras = listOf(
            listOf("It was a bright cold day in April and", "the clocks were striking thirteen."),
            listOf("Winston Smith slipped quickly through the", "doors of Victory Mansions at last."),
            listOf("The hallway smelt of boiled cabbage and", "old rag mats throughout the year."),
            listOf("It depicted simply an enormous face of a", "man of about forty-five years old."),
        )
        val pages = paras.mapIndexed { i, lines ->
            page(i) {
                line("The Great Work", x = 220f, y = 36f, fontSize = 10f)
                para(lines[0], lines[1], startY = 130f)
                line("${i + 1}", x = 300f, y = 756f, fontSize = 10f)
            }
        }
        assertEquals(
            paras.map { ContentBlock.Paragraph(it.joinToString("\n")) },
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (b) Alternating left/right headers (different text per parity).
    @Test
    fun `alternating headers are removed`() {
        val paras = (0 until 6).map { i ->
            listOf("Unique body text for page number $i runs", "onward and concludes with a full stop.")
        }
        val pages = paras.mapIndexed { i, lines ->
            page(i) {
                if (i % 2 == 0) {
                    line("Pride and Prejudice", x = 72f, y = 40f, fontSize = 10f)
                } else {
                    line("Chapter One", x = 420f, y = 40f, fontSize = 10f)
                }
                para(lines[0], lines[1], startY = 130f)
            }
        }
        assertEquals(
            paras.map { ContentBlock.Paragraph(it.joinToString("\n")) },
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (c) Two-column academic paper: left column read fully before right.
    @Test
    fun `two column page reads left column then right column`() {
        val leftLines = listOf(
            "Distributed systems often fail in ways",
            "that are hard to reproduce because",
            "timing dependent faults hide between",
            "independent components and only show",
            "up under production load.",
        )
        val rightLines = listOf(
            "We present a tracing framework that",
            "records causal order across machine",
            "boundaries with low overhead and",
            "reconstructs failures deterministically",
            "for offline replay and analysis.",
        )
        val pages = listOf(
            page(0) {
                // Interleave by y to prove ordering is column-major.
                leftLines.forEachIndexed { i, t -> line(t, x = 50f, y = 120f + i * 14f) }
                rightLines.forEachIndexed { i, t -> line(t, x = 312f, y = 120f + i * 14f) }
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(leftLines.joinToString("\n")),
                ContentBlock.Paragraph(rightLines.joinToString("\n")),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (d) Sidebar / margin note removal.
    @Test
    fun `narrow sidebar outside the main column is dropped`() {
        val bodyLines = listOf(
            "The committee then turned to the question of how the",
            "new infrastructure would be funded over the following",
            "decade, a question that had been postponed repeatedly",
            "during earlier sessions while the budget office paused",
            "estimates from each of the regional planning councils",
            "involved in the original proposal.",
        )
        val pages = listOf(
            page(0) {
                para(*bodyLines.toTypedArray(), startY = 130f)
                line("A margin note", x = 470f, y = 200f, fontSize = 9f)
                line("about the topic", x = 470f, y = 211f, fontSize = 9f)
                line("set in the side", x = 470f, y = 222f, fontSize = 9f)
            },
        )
        assertEquals(
            listOf(ContentBlock.Paragraph(bodyLines.joinToString("\n"))),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (e) Footnotes dropped.
    @Test
    fun `small-font marker lines at the page bottom are dropped`() {
        val bodyLines = listOf(
            "The text refers to an earlier edition of",
            "the work and to several disputed letters",
            "discussed at length by later scholars.",
        )
        val pages = listOf(
            page(0) {
                para(*bodyLines.toTypedArray(), startY = 130f)
                line("1 The original manuscript differs here", x = 72f, y = 700f, fontSize = 8f)
                line("2 See the appendix for details", x = 72f, y = 712f, fontSize = 8f)
            },
        )
        assertEquals(
            listOf(ContentBlock.Paragraph(bodyLines.joinToString("\n"))),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (f) Headings detected with levels.
    @Test
    fun `headings are detected with size-tier levels`() {
        val bodyLines = listOf(
            "The forest had been silent for a very",
            "long time before the first birds came.",
        )
        val pages = listOf(
            page(0) {
                line("The Silent Forest", x = 72f, y = 130f, fontSize = 20f)
                line("Part One", x = 72f, y = 170f, fontSize = 17f)
                line("A Beginning", x = 72f, y = 205f, fontSize = 14f)
                para(*bodyLines.toTypedArray(), startY = 240f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Heading("The Silent Forest", 1),
                ContentBlock.Heading("Part One", 2),
                ContentBlock.Heading("A Beginning", 3),
                ContentBlock.Paragraph(bodyLines.joinToString("\n")),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (g) Paragraph split by vertical gap.
    @Test
    fun `vertical gap splits paragraphs`() {
        val pages = listOf(
            page(0) {
                line("The first paragraph talks about one idea", x = 72f, y = 130f)
                line("and continues onto a second line here", x = 72f, y = 144f)
                line("The second paragraph begins after a", x = 72f, y = 174f)
                line("visible vertical gap in the page here", x = 72f, y = 188f)
                line("and continues for one more line again", x = 72f, y = 202f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("The first paragraph talks about one idea\nand continues onto a second line here"),
                ContentBlock.Paragraph(
                    "The second paragraph begins after a\nvisible vertical gap in the page here\nand continues for one more line again",
                ),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (g) Paragraph split by first-line indent after terminal punctuation.
    @Test
    fun `first-line indent after a finished sentence splits paragraphs`() {
        val pages = listOf(
            page(0) {
                line("The opening paragraph runs along this line", x = 72f, y = 130f)
                line("and then comes to a complete stop here now.", x = 72f, y = 144f)
                line("The next paragraph opens with a clear", x = 81f, y = 158f)
                line("first line indent set by the typesetter", x = 72f, y = 172f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("The opening paragraph runs along this line\nand then comes to a complete stop here now."),
                ContentBlock.Paragraph("The next paragraph opens with a clear\nfirst line indent set by the typesetter"),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (g) Paragraph split by short terminal line followed by an uppercase start.
    @Test
    fun `short final line followed by capitalized line splits paragraphs`() {
        val pages = listOf(
            page(0) {
                line("A long line of text that fills the column width here", x = 72f, y = 130f)
                line("before ending shortly.", x = 72f, y = 144f)
                line("Then a new paragraph starts unindented", x = 72f, y = 158f)
                line("with more text following the break here", x = 72f, y = 172f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("A long line of text that fills the column width here\nbefore ending shortly."),
                ContentBlock.Paragraph("Then a new paragraph starts unindented\nwith more text following the break here"),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (g) Counter-case: a full-width line ending in punctuation does not split.
    @Test
    fun `full width terminal line does not split the paragraph`() {
        val pages = listOf(
            page(0) {
                line("The sentence concludes exactly at the right margin here.", x = 72f, y = 130f)
                line("Another sentence follows in the same paragraph flow now", x = 72f, y = 144f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(
                    "The sentence concludes exactly at the right margin here.\nAnother sentence follows in the same paragraph flow now",
                ),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (h) Cross-page paragraph merge.
    @Test
    fun `paragraph continues across a page break`() {
        val pages = listOf(
            page(0) {
                para(
                    "The travelers walked for many hours through",
                    "the dark woods until they reached the",
                    startY = 130f,
                )
            },
            page(1) {
                para("edge of the river and made their camp.", startY = 130f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(
                    "The travelers walked for many hours through\n" +
                        "the dark woods until they reached the\n" +
                        "edge of the river and made their camp.",
                ),
            ),
            LayoutAnalyzer.analyze(pages),
        )
    }

    // (i) Roman-numeral front-matter page numbers.
    @Test
    fun `roman numeral page numbers are dropped`() {
        val paras = listOf(
            listOf("The preface explains the origins of the", "work and thanks those who helped most."),
            listOf("Further acknowledgements follow on the", "second page of the front matter here."),
            listOf("A note on the translation closes out the", "front matter before the first chapter."),
        )
        val numerals = listOf("ii", "iii", "iv")
        val pages = paras.mapIndexed { i, lines ->
            page(i) {
                para(lines[0], lines[1], startY = 130f)
                line(numerals[i], x = 300f, y = 756f, fontSize = 10f)
            }
        }
        assertEquals(
            paras.map { ContentBlock.Paragraph(it.joinToString("\n")) },
            LayoutAnalyzer.analyze(pages),
        )
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList(), LayoutAnalyzer.analyze(emptyList()))
        assertEquals(emptyList(), LayoutAnalyzer.analyze(listOf(emptyList())))
    }
}
