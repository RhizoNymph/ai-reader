package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine

/**
 * Drops footnotes (v1 policy: footnotes are not spoken).
 *
 * A footnote block is the trailing contiguous run of lines in a unit whose
 * font size is <= 0.75x the document body font size, whose first line starts
 * with a digit or footnote symbol marker, and which begins in the bottom 25%
 * of the page. Continuation lines inside the run need no marker.
 */
object FootnoteDetector {

    private const val SMALL_FONT_RATIO = 0.75f
    private const val BOTTOM_BAND_FRACTION = 0.75f
    private const val EPS = 0.01f

    private val MARKERS = "0123456789*†‡§¶#"

    fun remove(lines: List<PdfLine>, bodyFontSizePt: Float): List<PdfLine> {
        if (lines.isEmpty()) return lines
        val sorted = lines.sortedBy { it.y }
        val smallCut = SMALL_FONT_RATIO * bodyFontSizePt + EPS

        // Trailing run of small-font lines.
        var runStart = sorted.size
        while (runStart > 0 && sorted[runStart - 1].fontSizePt <= smallCut) runStart--
        if (runStart == sorted.size) return sorted

        val first = sorted[runStart]
        val inBottomBand = first.y >= BOTTOM_BAND_FRACTION * first.pageHeight
        val marked = first.text.trimStart().firstOrNull()?.let { it in MARKERS } == true
        return if (inBottomBand && marked) sorted.subList(0, runStart).toList() else sorted
    }
}
