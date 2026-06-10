package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine
import dev.nymph.aireader.model.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class ParagraphAssemblerTest {

    private fun unitOf(lines: List<PdfLine>): ColumnUnit {
        val sorted = lines.sortedBy { it.y }
        val xStart = sorted.minOf { it.x }
        val width = sorted.maxOf { it.x + it.width } - xStart
        return ColumnUnit(sorted.first().pageIndex, xStart, width, sorted)
    }

    @Test
    fun `lines in one unit join with newlines into a single paragraph`() {
        val unit = unitOf(
            page(0) {
                para(
                    "The first line of the paragraph runs on",
                    "and the second line completes the thought.",
                    startY = 130f,
                )
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(
                    "The first line of the paragraph runs on\nand the second line completes the thought.",
                ),
            ),
            ParagraphAssembler.assemble(listOf(unit), bodyFontSize = 12f),
        )
    }

    @Test
    fun `paragraph continues across units when sentence is unfinished`() {
        val left = unitOf(
            page(0) {
                para(
                    "The argument developed in the previous",
                    "section continues here without any",
                    startY = 130f,
                )
            },
        )
        val right = unitOf(
            page(0) {
                para(
                    "terminal punctuation to interrupt the",
                    "flow of the original sentence at all.",
                    x = 312f,
                    startY = 130f,
                )
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(
                    "The argument developed in the previous\n" +
                        "section continues here without any\n" +
                        "terminal punctuation to interrupt the\n" +
                        "flow of the original sentence at all.",
                ),
            ),
            ParagraphAssembler.assemble(listOf(left, right), bodyFontSize = 12f),
        )
    }

    @Test
    fun `no merge across units when previous paragraph ended with punctuation`() {
        val left = unitOf(
            page(0) {
                para("A complete sentence ends this column.", startY = 130f)
            },
        )
        val right = unitOf(
            page(0) {
                para("a lowercase start cannot resurrect it", x = 312f, startY = 130f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("A complete sentence ends this column."),
                ContentBlock.Paragraph("a lowercase start cannot resurrect it"),
            ),
            ParagraphAssembler.assemble(listOf(left, right), bodyFontSize = 12f),
        )
    }

    @Test
    fun `no merge across units when next line starts uppercase`() {
        val first = unitOf(
            page(0) {
                para("This page ends mid sentence without", startY = 130f)
            },
        )
        val second = unitOf(
            page(1) {
                para("New paragraphs start with capitals here.", startY = 130f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("This page ends mid sentence without"),
                ContentBlock.Paragraph("New paragraphs start with capitals here."),
            ),
            ParagraphAssembler.assemble(listOf(first, second), bodyFontSize = 12f),
        )
    }

    @Test
    fun `heading line flushes the open paragraph and emits a Heading`() {
        val unit = unitOf(
            page(0) {
                para("Closing words of the previous section here.", startY = 130f)
                line("The Next Section", x = 72f, y = 180f, fontSize = 17f)
                para("Opening words of the next section follow on.", startY = 210f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("Closing words of the previous section here."),
                ContentBlock.Heading("The Next Section", 2),
                ContentBlock.Paragraph("Opening words of the next section follow on."),
            ),
            ParagraphAssembler.assemble(listOf(unit), bodyFontSize = 12f),
        )
    }

    @Test
    fun `closing quote after terminal punctuation still ends the paragraph`() {
        val first = unitOf(
            page(0) {
                para("“That is the end of the quotation.”", startY = 130f)
            },
        )
        val second = unitOf(
            page(1) {
                para("and a lowercase opener still starts fresh", startY = 130f)
            },
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph("“That is the end of the quotation.”"),
                ContentBlock.Paragraph("and a lowercase opener still starts fresh"),
            ),
            ParagraphAssembler.assemble(listOf(first, second), bodyFontSize = 12f),
        )
    }
}
