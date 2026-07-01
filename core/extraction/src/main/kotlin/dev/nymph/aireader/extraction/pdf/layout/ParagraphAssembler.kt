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
 *
 * A non-repeating running head (e.g. a section title at the very top of a page)
 * that lands between the two halves of a sentence split across a page break is
 * dropped, and the sentence is healed across it: when the open paragraph is
 * unfinished and the first line of the next unit is a short top-of-page line
 * followed by a lowercase continuation, that first line is treated as furniture
 * and skipped. This catches running heads that appear too few times for
 * [RepeatedFurnitureDetector]'s repetition test to remove.
 */
object ParagraphAssembler {

    private const val GAP_RATIO = 1.8f
    private const val INDENT_PAGE_WIDTH_FRACTION = 0.01f
    private const val SHORT_LINE_COLUMN_FRACTION = 0.7f
    private const val FALLBACK_SPACING_RATIO = 1.2f

    /** A running head sits at the very top of the page (top 15% band). */
    private const val RUNNING_HEAD_TOP_BAND = 0.15f
    /** Running heads are short titles, not sentences. */
    private const val RUNNING_HEAD_MAX_CHARS = 70

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
            val lines = unit.lines
            var previousInUnit: PdfLine? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.text.isBlank()) {
                    i++
                    continue
                }

                val headingLevel = HeadingDetector.headingLevel(line, bodyFontSize)
                if (headingLevel != null) {
                    flush()
                    blocks += ContentBlock.Heading(line.text.trim(), headingLevel)
                    previousInUnit = null
                    i++
                    continue
                }

                val prev = previousInUnit
                if (prev == null) {
                    // First body line of this unit. It may continue the open
                    // paragraph, be a running head bleeding into a split
                    // sentence (dropped), or start a fresh paragraph.
                    val open = lastParagraphLine
                    val unfinished = open != null && !endsSentence(open.text)
                    when {
                        unfinished && startsLowercase(line.text) -> Unit // continues; append below
                        unfinished && isRunningHead(line) &&
                            nextBodyLineContinues(lines, i, bodyFontSize) -> {
                            // Drop the running head; leave the paragraph open so
                            // the following lowercase line heals the sentence.
                            i++
                            continue
                        }
                        else -> flush()
                    }
                } else if (startsNewParagraph(prev, line, unit, modalSpacing)) {
                    flush()
                }

                paragraphLines += line.text.trim()
                lastParagraphLine = line
                previousInUnit = line
                i++
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

    /**
     * A short line at the very top of the page with no terminal punctuation —
     * the shape of a running head. Only ever consulted when a sentence was left
     * open by the previous unit and the following line continues it, so this
     * stays lenient without eating real headings (which [HeadingDetector] has
     * already claimed) or body text.
     */
    private fun isRunningHead(line: PdfLine): Boolean {
        val text = line.text.trim()
        if (text.isEmpty() || line.pageHeight <= 0f) return false
        if (line.y / line.pageHeight > RUNNING_HEAD_TOP_BAND) return false
        if (text.length > RUNNING_HEAD_MAX_CHARS) return false
        return !endsSentence(text)
    }

    /**
     * True when the next non-blank, non-heading line after [afterIndex] starts
     * lowercase — i.e. it continues the sentence left open before the running
     * head. A heading in between means this is a real section break, not a leak.
     */
    private fun nextBodyLineContinues(lines: List<PdfLine>, afterIndex: Int, bodyFontSize: Float): Boolean {
        for (j in afterIndex + 1 until lines.size) {
            val next = lines[j]
            if (next.text.isBlank()) continue
            if (HeadingDetector.headingLevel(next, bodyFontSize) != null) return false
            return startsLowercase(next.text)
        }
        return false
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
