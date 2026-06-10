package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine

/**
 * Removes page furniture: running headers/footers detected by cross-page
 * repetition, and standalone page-number lines detected by pattern.
 *
 * Heuristics (see docs/features/extraction.md):
 * - Repetition candidates are limited to the top/bottom 15% y-bands so a
 *   phrase repeated in body text is never removed.
 * - A candidate group is keyed by normalized text (digits -> '#', collapsed
 *   whitespace, lowercase) and clustered by y within 2% of page height.
 * - A cluster is furniture when it occurs on >= 60% of pages (minimum 3), or
 *   on >= 60% (minimum 3) of even or of odd pages — the parity check removes
 *   verso/recto alternating headers that only appear on half the pages.
 * - Standalone page-number lines (pure digits, roman numerals, "Page N",
 *   "Page N of M", "N of M", optionally wrapped in dashes/dots) are dropped
 *   anywhere on the page regardless of repetition.
 */
object RepeatedFurnitureDetector {

    private const val BAND_FRACTION = 0.15f
    private const val Y_CLUSTER_FRACTION = 0.02f
    private const val MIN_PAGE_FRACTION = 0.6
    private const val MIN_PAGES = 3

    private val DIGITS = Regex("\\d+")
    private val WHITESPACE = Regex("\\s+")

    /** Decorations commonly wrapped around folios: "- 18 -", "· 18 ·". */
    private val DECORATION = Regex("^[-–—.·•\\s]+|[-–—.·•\\s]+$")
    private val PURE_NUMBER = Regex("^\\d{1,4}$")
    private val ROMAN = Regex("^(m{0,4})(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")
    private val PAGE_N = Regex("^page \\d+( of \\d+)?$")
    private val N_OF_M = Regex("^\\d+ of \\d+$")

    fun removeFurniture(pages: List<List<PdfLine>>): List<List<PdfLine>> {
        val repeated = findRepeatedLines(pages)
        return pages.map { page ->
            page.filter { line -> line !in repeated && !isPageNumberLine(line.text) }
        }
    }

    fun isPageNumberLine(text: String): Boolean {
        val stripped = text.trim().lowercase().replace(DECORATION, "")
        if (stripped.isEmpty()) return false
        return PURE_NUMBER.matches(stripped) ||
            ROMAN.matches(stripped) ||
            PAGE_N.matches(stripped) ||
            N_OF_M.matches(stripped)
    }

    private fun normalize(text: String): String =
        text.replace(DIGITS, "#").replace(WHITESPACE, " ").trim().lowercase()

    private data class Occurrence(val pageIdx: Int, val line: PdfLine)

    /** Identity set of lines judged to be repeated headers/footers. */
    private fun findRepeatedLines(pages: List<List<PdfLine>>): Set<PdfLine> {
        val totalPages = pages.size
        if (totalPages < MIN_PAGES) return emptySet()
        val evenPages = (totalPages + 1) / 2 // page indices 0, 2, 4, ...
        val oddPages = totalPages / 2

        val groups = mutableMapOf<String, MutableList<Occurrence>>()
        pages.forEachIndexed { pageIdx, page ->
            for (line in page) {
                if (line.text.isBlank()) continue
                val yFrac = line.y / line.pageHeight
                if (yFrac <= BAND_FRACTION || yFrac >= 1f - BAND_FRACTION) {
                    groups.getOrPut(normalize(line.text)) { mutableListOf() } += Occurrence(pageIdx, line)
                }
            }
        }

        val removed = mutableSetOf<PdfLine>()
        for (occurrences in groups.values) {
            for (cluster in clusterByY(occurrences)) {
                val all = cluster.map { it.pageIdx }.distinct()
                val even = all.count { it % 2 == 0 }
                val odd = all.size - even
                val isFurniture = qualifies(all.size, totalPages) ||
                    qualifies(even, evenPages) ||
                    qualifies(odd, oddPages)
                if (isFurniture) cluster.forEach { removed += it.line }
            }
        }
        return removed
    }

    private fun qualifies(count: Int, outOf: Int): Boolean =
        count >= MIN_PAGES && outOf > 0 && count >= MIN_PAGE_FRACTION * outOf

    private fun clusterByY(occurrences: List<Occurrence>): List<List<Occurrence>> {
        val sorted = occurrences.sortedBy { it.line.y }
        val clusters = mutableListOf<MutableList<Occurrence>>()
        for (occ in sorted) {
            val window = Y_CLUSTER_FRACTION * occ.line.pageHeight
            val current = clusters.lastOrNull()
            if (current != null && occ.line.y - current.first().line.y <= window) {
                current += occ
            } else {
                clusters += mutableListOf(occ)
            }
        }
        return clusters
    }
}
