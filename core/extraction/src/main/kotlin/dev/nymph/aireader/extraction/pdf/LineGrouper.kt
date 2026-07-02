package dev.nymph.aireader.extraction.pdf

import kotlin.math.abs

/**
 * A positioned text run collected from a PDF page, before visual-line assembly.
 * [x]/[xEnd] are the run's left and right edges; [y] its baseline (top-left
 * origin, y grows downward, matching [PdfLine]).
 *
 * [glyphXStarts] holds the left x of each character in [text] (same length as
 * [text]) when the collector could align glyphs to characters; empty otherwise.
 * It lets [LineGrouper] cut a run that a PDF engine emitted as one contiguous
 * span across a column boundary (a side-rail word abutting body text with no
 * gap), which no gap-based rule can separate.
 */
data class TextFragment(
    val text: String,
    val x: Float,
    val xEnd: Float,
    val y: Float,
    val height: Float,
    val fontSizePt: Float,
    val glyphXStarts: FloatArray = FloatArray(0),
) {
    // Array identity would break value semantics; fragments are never compared.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Pure assembly of positioned [TextFragment]s into visual [PdfLine]s. Kept out
 * of the Android-touching [PdfTextCollector] so it is JVM-unit-testable.
 *
 * Two stages:
 * 1. Group fragments into rows by vertical proximity (tolerance
 *    `max(2pt, 0.3 x fontSize)`), the classic "same baseline" test.
 * 2. **Split each row at column boundaries.** Fragments that share a baseline
 *    but belong to different columns (e.g. a table-of-contents side rail sitting
 *    at the same height as body text) must NOT be glued into one line — doing so
 *    would splice sidebar words into the middle of a sentence and make the line
 *    un-droppable by [dev.nymph.aireader.extraction.pdf.layout.ColumnDetector].
 *    A row is split both where a wide horizontal gap (a gutter) appears and
 *    where consecutive fragments straddle the body column's left edge — the
 *    latter catches a side-rail word whose right edge touches the body with no
 *    whitespace at all.
 */
object LineGrouper {

    /**
     * A horizontal gap wider than this many times the (smaller) font size is a
     * column gutter, not inter-word space. Inter-word spacing (even generous
     * justified spacing) stays well under one font size; column gutters are
     * multiples of it, so 1.5x cleanly separates the two without splitting
     * ordinary lines. The *smaller* of the two fragments' font sizes is used so
     * that a small side-rail word abutting large display type (e.g. a table of
     * contents next to a cover title) still splits instead of being glued on.
     */
    private const val COLUMN_GAP_RATIO = 1.5f

    /** Bucket width (pt) for finding the dominant text column's left edge. */
    private const val X_BUCKET_PT = 3f

    /** Tolerance (pt) around the body left edge, absorbing first-line indents. */
    private const val BODY_EDGE_MARGIN_PT = 4f

    /**
     * A fragment must start at least this far left of the body edge before it is
     * cut at that edge. This distinguishes a side rail (tens of points out in the
     * margin) from an ordinary hanging indent or list bullet sitting just left of
     * the text, which must stay attached.
     */
    private const val MIN_RAIL_DISTANCE_PT = 30f

    fun group(
        fragments: List<TextFragment>,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float,
    ): List<PdfLine> {
        val bodyLeftX = bodyLeftEdge(fragments)
        val split = fragments.flatMap { splitFragmentAtBodyEdge(it, bodyLeftX) }
        val sorted = split.sortedWith(compareBy({ it.y }, { it.x }))
        val rows = mutableListOf<MutableList<TextFragment>>()
        for (fragment in sorted) {
            val current = rows.lastOrNull()
            val tolerance = maxOf(2f, 0.3f * fragment.fontSizePt)
            if (current != null && abs(fragment.y - current.first().y) <= tolerance) {
                current += fragment
            } else {
                rows += mutableListOf(fragment)
            }
        }
        return rows
            .flatMap { splitAtColumns(it, bodyLeftX) }
            .map { parts -> toLine(parts, pageIndex, pageWidth, pageHeight) }
            .filter { it.text.isNotBlank() }
    }

    /**
     * Left edge of the page's dominant text column: the min x of the x-start
     * bucket carrying the most characters. Side-rail words are short and start
     * further left, so this reliably lands on the body column even when the rail
     * has more (shorter) lines.
     */
    private fun bodyLeftEdge(fragments: List<TextFragment>): Float {
        val dominant = fragments
            .groupBy { (it.x / X_BUCKET_PT).toInt() }
            .maxByOrNull { (_, group) -> group.sumOf { it.text.length } }
            ?.value
            ?: return Float.NEGATIVE_INFINITY
        return dominant.minOf { it.x }
    }

    /**
     * Cuts one atomic run that a PDF engine emitted as a single span reaching
     * from a far-left side rail across into the body column (e.g.
     * "Macroeconomicsscience. But while..."). The run is split at the first glyph
     * that reaches [bodyLeftX]; the left piece is the rail word (dropped later as
     * marginalia), the right piece is the real body text. Requires per-glyph x
     * data aligned to the text; otherwise the run is returned unchanged.
     */
    private fun splitFragmentAtBodyEdge(fragment: TextFragment, bodyLeftX: Float): List<TextFragment> {
        if (fragment.glyphXStarts.isEmpty()) return listOf(fragment)
        val startsInRail = fragment.x < bodyLeftX - MIN_RAIL_DISTANCE_PT
        val reachesBody = fragment.xEnd > bodyLeftX
        if (!startsInRail || !reachesBody) return listOf(fragment)

        val cut = fragment.glyphXStarts.indexOfFirst { it >= bodyLeftX }
        if (cut <= 0 || cut >= fragment.text.length) return listOf(fragment)
        // The glyph array can be shorter than the text when the PDF engine
        // synthesized spaces; the cut is only trustworthy when the leading rail
        // word (which never contains a space) maps 1:1 to the first glyphs.
        if (fragment.text.substring(0, cut).any { it.isWhitespace() }) return listOf(fragment)

        val left = TextFragment(
            text = fragment.text.substring(0, cut),
            x = fragment.x,
            xEnd = fragment.glyphXStarts[cut],
            y = fragment.y,
            height = fragment.height,
            fontSizePt = fragment.fontSizePt,
            glyphXStarts = fragment.glyphXStarts.copyOfRange(0, cut),
        )
        val right = TextFragment(
            text = fragment.text.substring(cut),
            x = fragment.glyphXStarts[cut],
            xEnd = fragment.xEnd,
            y = fragment.y,
            height = fragment.height,
            fontSizePt = fragment.fontSizePt,
            glyphXStarts = fragment.glyphXStarts.copyOfRange(cut, fragment.glyphXStarts.size),
        )
        return listOf(left, right)
    }

    /**
     * Splits one same-baseline row wherever a column-gutter-sized gap appears or
     * where two fragments straddle [bodyLeftX] (a rail word ending exactly at the
     * body edge leaves no gap to split on, so the edge crossing catches it).
     */
    private fun splitAtColumns(row: List<TextFragment>, bodyLeftX: Float): List<List<TextFragment>> {
        val ordered = row.sortedBy { it.x }
        val groups = mutableListOf<MutableList<TextFragment>>()
        for (fragment in ordered) {
            val current = groups.lastOrNull()
            if (current == null) {
                groups += mutableListOf(fragment)
                continue
            }
            val prev = current.last()
            val gap = fragment.x - prev.xEnd
            val gutter = gap > COLUMN_GAP_RATIO * minOf(prev.fontSizePt, fragment.fontSizePt)
            // Only a fragment out in the rail (far left of the body) crossing into
            // the body edge splits; two ordinary adjacent words never do, even if
            // the body edge happens to fall between them.
            val crossesBodyEdge = prev.x < bodyLeftX - MIN_RAIL_DISTANCE_PT &&
                fragment.x >= bodyLeftX - BODY_EDGE_MARGIN_PT
            if (gutter || crossesBodyEdge) {
                groups += mutableListOf(fragment)
            } else {
                current += fragment
            }
        }
        return groups
    }

    private fun toLine(
        parts: List<TextFragment>,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float,
    ): PdfLine {
        val ordered = parts.sortedBy { it.x }
        val x = ordered.minOf { it.x }
        return PdfLine(
            text = ordered.joinToString(" ") { it.text }.trim(),
            x = x,
            y = ordered.first().y,
            width = ordered.maxOf { it.xEnd } - x,
            height = ordered.maxOf { it.height },
            fontSizePt = ordered.maxOf { it.fontSizePt },
            pageIndex = pageIndex,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
        )
    }
}
