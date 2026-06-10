package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine
import dev.nymph.aireader.model.ContentBlock
import kotlin.math.roundToInt

/**
 * Assembles classified lines from reading-order [ColumnUnit]s into
 * [ContentBlock]s. Lines inside a paragraph are joined with "\n" — the text
 * pipeline downstream owns unwrapping and dehyphenation, so raw wraps must be
 * preserved here.
 *
 * A new paragraph starts when:
 * - the vertical gap to the previous line exceeds 1.8x the modal line
 *   spacing of the document, or
 * - the line is indented past the column start (> 1% of page width) and the
 *   previous line ended a sentence, or
 * - the previous line ended a sentence, was short (< 70% of column width),
 *   and the current line starts with an uppercase letter.
 *
 * A paragraph continues across unit boundaries (page/column breaks) when the
 * previous unit's last line lacks terminal punctuation and the next unit's
 * first line starts lowercase.
 */
object ParagraphAssembler {

    private const val GAP_RATIO = 1.8f
    private const val INDENT_PAGE_WIDTH_FRACTION = 0.01f
    private const val SHORT_LINE_COLUMN_FRACTION = 0.7f
    private const val FALLBACK_SPACING_RATIO = 1.2f

    private const val TERMINAL = ".!?…"
    private const val CLOSERS = "\"'”’»)]"

    fun assemble(units: List<ColumnUnit>, bodyFontSize: Float): List<ContentBlock> {
        val modalSpacing = modalLineSpacing(units, bodyFontSize)
        val blocks = mutableListOf<ContentBlock>()
        val paragraphLines = mutableListOf<String>()
        var lastParagraphLine: PdfLine? = null

        fun flush() {
            if (paragraphLines.isNotEmpty()) {
                blocks += ContentBlock.Paragraph(paragraphLines.joinToString("\n"))
                paragraphLines.clear()
            }
            lastParagraphLine = null
        }

        for (unit in units) {
            var previousInUnit: PdfLine? = null
            for (line in unit.lines) {
                if (line.text.isBlank()) continue

                val headingLevel = HeadingDetector.headingLevel(line, bodyFontSize)
                if (headingLevel != null) {
                    flush()
                    blocks += ContentBlock.Heading(line.text.trim(), headingLevel)
                    previousInUnit = null
                    continue
                }

                val prev = previousInUnit
                if (prev == null) {
                    // First body line of this unit: maybe continue the open paragraph.
                    val open = lastParagraphLine
                    val continues = open != null &&
                        !endsSentence(open.text) &&
                        startsLowercase(line.text)
                    if (!continues) flush()
                } else if (startsNewParagraph(prev, line, unit, modalSpacing)) {
                    flush()
                }

                paragraphLines += line.text.trim()
                lastParagraphLine = line
                previousInUnit = line
            }
        }
        flush()
        return blocks
    }

    private fun startsNewParagraph(
        prev: PdfLine,
        line: PdfLine,
        unit: ColumnUnit,
        modalSpacing: Float,
    ): Boolean {
        if (line.y - prev.y > GAP_RATIO * modalSpacing) return true
        val prevEnded = endsSentence(prev.text)
        if (prevEnded && line.x > unit.xStart + INDENT_PAGE_WIDTH_FRACTION * line.pageWidth) return true
        return prevEnded &&
            startsUppercase(line.text) &&
            prev.width < SHORT_LINE_COLUMN_FRACTION * unit.width
    }

    /** True when the text ends with terminal punctuation (closing quotes/brackets ignored). */
    private fun endsSentence(text: String): Boolean {
        val trimmed = text.trim().trimEnd { it in CLOSERS }
        return trimmed.lastOrNull()?.let { it in TERMINAL } == true
    }

    private fun startsLowercase(text: String): Boolean =
        text.trimStart().firstOrNull()?.isLowerCase() == true

    private fun startsUppercase(text: String): Boolean =
        text.trimStart().firstOrNull()?.isUpperCase() == true

    /**
     * Modal baseline-to-baseline distance between consecutive body lines,
     * bucketed to 0.5pt. Falls back to 1.2x the body font size.
     */
    private fun modalLineSpacing(units: List<ColumnUnit>, bodyFontSizePt: Float): Float {
        val counts = mutableMapOf<Int, Int>() // key = spacing in half-points
        for (unit in units) {
            val bodyLines = unit.lines.filter {
                it.text.isNotBlank() && HeadingDetector.headingLevel(it, bodyFontSizePt) == null
            }
            for (i in 1 until bodyLines.size) {
                val gap = bodyLines[i].y - bodyLines[i - 1].y
                if (gap <= 0.1f) continue
                val key = (gap * 2f).roundToInt()
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val best = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull() ?: return FALLBACK_SPACING_RATIO * bodyFontSizePt
        return best.key / 2f
    }
}
