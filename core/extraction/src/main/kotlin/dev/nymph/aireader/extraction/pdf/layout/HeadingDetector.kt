package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine

/**
 * Classifies a line as a heading by relative font size.
 *
 * A heading is >= 1.15x the body font size, shorter than 90 characters, and
 * does not end in a period. Levels by size tier: >=1.6x -> 1, >=1.35x -> 2,
 * otherwise 3.
 */
object HeadingDetector {

    private const val MIN_RATIO = 1.15f
    private const val LEVEL_2_RATIO = 1.35f
    private const val LEVEL_1_RATIO = 1.6f
    private const val MAX_LENGTH = 90
    private const val EPS = 1e-3f

    fun headingLevel(line: PdfLine, bodyFontSizePt: Float): Int? {
        if (bodyFontSizePt <= 0f) return null
        val ratio = line.fontSizePt / bodyFontSizePt
        val text = line.text.trim()
        if (ratio < MIN_RATIO - EPS) return null
        if (text.length >= MAX_LENGTH) return null
        if (text.endsWith(".")) return null
        return when {
            ratio >= LEVEL_1_RATIO - EPS -> 1
            ratio >= LEVEL_2_RATIO - EPS -> 2
            else -> 3
        }
    }
}
