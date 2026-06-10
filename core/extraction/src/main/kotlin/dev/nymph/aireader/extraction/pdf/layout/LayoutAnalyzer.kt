package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine
import dev.nymph.aireader.model.ContentBlock
import kotlin.math.roundToInt

/**
 * Pure-Kotlin PDF layout analysis: positioned lines in, main-content blocks
 * out. Pipeline:
 *
 * 1. [RepeatedFurnitureDetector] — drop running headers/footers/page numbers.
 * 2. Body font size = modal font size of the remaining text (weighted by
 *    character count, bucketed to 0.5pt).
 * 3. [ColumnDetector] — per page: main column(s), marginalia dropped,
 *    reading order page -> column (left, right) -> y.
 * 4. [FootnoteDetector] — drop trailing small-font footnote runs per unit.
 * 5. [ParagraphAssembler] — headings + paragraphs, raw line wraps preserved
 *    with "\n", paragraphs merged across page/column breaks when a sentence
 *    is clearly unfinished.
 */
object LayoutAnalyzer {

    fun analyze(pages: List<List<PdfLine>>): List<ContentBlock> {
        val cleaned = RepeatedFurnitureDetector.removeFurniture(pages)
            .map { page -> page.filter { it.text.isNotBlank() } }
        val bodyFont = modalFontSize(cleaned.flatten()) ?: return emptyList()

        val units = cleaned
            .flatMap { ColumnDetector.detect(it) }
            .map { unit -> unit.copy(lines = FootnoteDetector.remove(unit.lines, bodyFont)) }
            .filter { it.lines.isNotEmpty() }

        return ParagraphAssembler.assemble(units, bodyFont)
    }

    /**
     * Modal font size weighted by character count, bucketed to 0.5pt, so body
     * text wins over any amount of large display type. Null when there is no
     * text at all.
     */
    private fun modalFontSize(lines: List<PdfLine>): Float? {
        val weights = mutableMapOf<Int, Int>() // key = font size in half-points
        for (line in lines) {
            val chars = line.text.trim().length
            if (chars == 0) continue
            val key = (line.fontSizePt * 2f).roundToInt()
            weights[key] = (weights[key] ?: 0) + chars
        }
        val best = weights.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull() ?: return null
        return best.key / 2f
    }
}
