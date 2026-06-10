package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine

/**
 * One reading-order unit: a column of one page. Pages are read column by
 * column (left then right), each column top to bottom.
 */
data class ColumnUnit(
    val pageIndex: Int,
    /** Left edge of the column (min x-start of its core lines). */
    val xStart: Float,
    /** Horizontal extent of the column (max x-end minus min x-start). */
    val width: Float,
    /** Lines sorted by y, then x. */
    val lines: List<PdfLine>,
)

/**
 * Detects the main column(s) of a page from a histogram of line x-starts,
 * drops marginalia, and emits [ColumnUnit]s in reading order.
 *
 * Heuristics:
 * - x-starts are clustered greedily with a tolerance of 3% of page width
 *   (wide enough to absorb first-line indents).
 * - Two columns are recognized when two clusters each hold >= 25% of the
 *   page's lines, their x-starts are separated by >= 25% of page width, the
 *   left column's text does not reach into the right column, and the
 *   narrower column is at least 40% as wide as the wider one (this last
 *   guard keeps a dense sidebar from being promoted to a column).
 * - Lines outside every main cluster are marginalia (dropped) when their
 *   x-range does not overlap any column's x-range AND they are narrower than
 *   40% of the nearest column's width; otherwise they are assigned to the
 *   column they overlap most (nearest by center when no overlap).
 */
object ColumnDetector {

    private const val X_CLUSTER_FRACTION = 0.03f
    private const val MIN_COLUMN_LINE_FRACTION = 0.25f
    private const val MIN_GUTTER_FRACTION = 0.25f
    private const val GUTTER_OVERLAP_TOLERANCE_FRACTION = 0.02f
    private const val MIN_COLUMN_WIDTH_RATIO = 0.4f
    private const val MARGINALIA_WIDTH_FRACTION = 0.4f

    fun detect(lines: List<PdfLine>): List<ColumnUnit> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        val pageWidth = usable.first().pageWidth

        val clusters = clusterByXStart(usable, pageWidth)
        val mainClusters = chooseMainClusters(clusters, usable.size, pageWidth)

        data class Column(val xStart: Float, val xEnd: Float, val lines: MutableList<PdfLine>)
        val columns = mainClusters.map { cluster ->
            Column(
                xStart = cluster.minOf { it.x },
                xEnd = cluster.maxOf { it.x + it.width },
                lines = cluster.toMutableList(),
            )
        }

        val leftovers = usable.filter { line -> mainClusters.none { line in it } }
        for (line in leftovers) {
            val overlaps = columns.map { col ->
                minOf(col.xEnd, line.x + line.width) - maxOf(col.xStart, line.x)
            }
            val best = overlaps.indices.maxBy { overlaps[it] }
            if (overlaps[best] > 0f) {
                columns[best].lines += line
                continue
            }
            // No overlap with any column: marginalia if narrow, else assign to nearest.
            val nearest = columns.indices.minBy { i ->
                val center = (columns[i].xStart + columns[i].xEnd) / 2f
                kotlin.math.abs(center - (line.x + line.width / 2f))
            }
            val columnWidth = columns[nearest].xEnd - columns[nearest].xStart
            if (line.width < MARGINALIA_WIDTH_FRACTION * columnWidth) continue // dropped
            columns[nearest].lines += line
        }

        return columns
            .sortedBy { it.xStart }
            .filter { it.lines.isNotEmpty() }
            .map { col ->
                ColumnUnit(
                    pageIndex = col.lines.first().pageIndex,
                    xStart = col.xStart,
                    width = col.xEnd - col.xStart,
                    lines = col.lines.sortedWith(compareBy({ it.y }, { it.x })),
                )
            }
    }

    /** Greedy 1-D clustering of lines by x-start. */
    private fun clusterByXStart(lines: List<PdfLine>, pageWidth: Float): List<List<PdfLine>> {
        val tolerance = X_CLUSTER_FRACTION * pageWidth
        val sorted = lines.sortedBy { it.x }
        val clusters = mutableListOf<MutableList<PdfLine>>()
        for (line in sorted) {
            val current = clusters.lastOrNull()
            if (current != null && line.x - current.last().x <= tolerance) {
                current += line
            } else {
                clusters += mutableListOf(line)
            }
        }
        return clusters
    }

    /** Picks one or two main column clusters per the two-column heuristics. */
    private fun chooseMainClusters(
        clusters: List<List<PdfLine>>,
        totalLines: Int,
        pageWidth: Float,
    ): List<List<PdfLine>> {
        val biggest = clusters.maxBy { it.size }
        val candidates = clusters
            .filter { it.size >= MIN_COLUMN_LINE_FRACTION * totalLines }
            .sortedByDescending { it.size }
            .take(2)
            .sortedBy { cluster -> cluster.minOf { it.x } }
        if (candidates.size < 2) return listOf(biggest)

        val (left, right) = candidates
        val leftStart = left.minOf { it.x }
        val leftEnd = left.maxOf { it.x + it.width }
        val rightStart = right.minOf { it.x }
        val rightEnd = right.maxOf { it.x + it.width }
        val leftWidth = leftEnd - leftStart
        val rightWidth = rightEnd - rightStart

        val gutterOk = rightStart - leftStart >= MIN_GUTTER_FRACTION * pageWidth &&
            leftEnd <= rightStart + GUTTER_OVERLAP_TOLERANCE_FRACTION * pageWidth
        val widthOk = minOf(leftWidth, rightWidth) >= MIN_COLUMN_WIDTH_RATIO * maxOf(leftWidth, rightWidth)

        return if (gutterOk && widthOk) listOf(left, right) else listOf(biggest)
    }
}
