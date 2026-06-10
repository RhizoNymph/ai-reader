package dev.nymph.aireader.extraction.pdf

/**
 * One visual text line on a PDF page, in page coordinates with the origin at
 * the top-left and y increasing downward (PdfBox `*DirAdj` convention).
 *
 * This is the boundary type between the Android-touching collector
 * ([PdfTextCollector]) and the pure-Kotlin layout analysis in `pdf/layout`,
 * which only ever sees this class and is therefore JVM unit-testable.
 */
data class PdfLine(
    val text: String,
    /** Left edge of the line, points from the page's left edge. */
    val x: Float,
    /** Baseline-ish vertical position, points from the page's top edge. */
    val y: Float,
    /** Horizontal extent of the rendered text. */
    val width: Float,
    /** Vertical extent of the rendered text. */
    val height: Float,
    /** Dominant font size of the line in points. */
    val fontSizePt: Float,
    /** Zero-based page index. */
    val pageIndex: Int,
    val pageWidth: Float,
    val pageHeight: Float,
)
