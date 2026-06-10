package dev.nymph.aireader.extraction.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.Writer

/**
 * Thin PdfBox-Android adapter: drives [PDFTextStripper] over a document and
 * collects positioned [PdfLine]s per page. All layout intelligence lives in
 * the pure-Kotlin `pdf/layout` package; this class only groups raw
 * [TextPosition]s into visual lines.
 *
 * NOTE: the app must call `PDFBoxResourceLoader.init(context)` once (e.g. in
 * Application.onCreate) before any PdfBox API is used on Android.
 */
class PdfTextCollector : PDFTextStripper() {

    private data class Fragment(
        val text: String,
        val x: Float,
        val xEnd: Float,
        val y: Float,
        val height: Float,
        val fontSizePt: Float,
    )

    private val pages = mutableListOf<List<PdfLine>>()
    private val fragments = mutableListOf<Fragment>()
    private var pageWidth = 0f
    private var pageHeight = 0f

    init {
        setSortByPosition(true)
    }

    /**
     * Extracts every page's text as positioned lines.
     * @throws IOException when PdfBox fails to parse page content (callers
     *   convert this to a typed [dev.nymph.aireader.model.ImportError]).
     */
    @Throws(IOException::class)
    fun collect(document: PDDocument): List<List<PdfLine>> {
        pages.clear()
        setStartPage(1)
        setEndPage(document.numberOfPages)
        writeText(document, NullWriter)
        return pages.toList()
    }

    override fun startPage(page: PDPage) {
        fragments.clear()
        val box = page.mediaBox
        pageWidth = box?.width ?: 0f
        pageHeight = box?.height ?: 0f
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        fragments += Fragment(
            text = text,
            x = textPositions.minOf { it.xDirAdj },
            xEnd = textPositions.maxOf { it.xDirAdj + it.widthDirAdj },
            y = textPositions.first().yDirAdj,
            height = textPositions.maxOf { it.heightDir },
            fontSizePt = textPositions.maxOf { it.fontSizeInPt },
        )
    }

    override fun endPage(page: PDPage) {
        pages += groupIntoLines(fragments.toList(), currentPageNo - 1, pageWidth, pageHeight)
        fragments.clear()
        super.endPage(page)
    }

    /** Groups word fragments into visual lines by vertical proximity. */
    private fun groupIntoLines(
        fragments: List<Fragment>,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float,
    ): List<PdfLine> {
        val sorted = fragments.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = mutableListOf<MutableList<Fragment>>()
        for (fragment in sorted) {
            val current = lines.lastOrNull()
            val tolerance = maxOf(2f, 0.3f * fragment.fontSizePt)
            if (current != null && kotlin.math.abs(fragment.y - current.first().y) <= tolerance) {
                current += fragment
            } else {
                lines += mutableListOf(fragment)
            }
        }
        return lines.map { parts ->
            val ordered = parts.sortedBy { it.x }
            val x = ordered.minOf { it.x }
            PdfLine(
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
        }.filter { it.text.isNotBlank() }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
