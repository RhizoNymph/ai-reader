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

    private val pages = mutableListOf<List<PdfLine>>()
    private val fragments = mutableListOf<TextFragment>()
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
        // Per-glyph x-starts (left to right); LineGrouper uses them to cut a run
        // a PDF engine glued across a column boundary. They can be fewer than the
        // characters when the engine synthesized spaces, so LineGrouper only
        // trusts the leading (space-free) prefix.
        val glyphXStarts = FloatArray(textPositions.size) { textPositions[it].xDirAdj }
        fragments += TextFragment(
            text = text,
            x = textPositions.minOf { it.xDirAdj },
            xEnd = textPositions.maxOf { it.xDirAdj + it.widthDirAdj },
            y = textPositions.first().yDirAdj,
            height = textPositions.maxOf { it.heightDir },
            fontSizePt = textPositions.maxOf { it.fontSizeInPt },
            glyphXStarts = glyphXStarts,
        )
    }

    override fun endPage(page: PDPage) {
        pages += LineGrouper.group(fragments.toList(), currentPageNo - 1, pageWidth, pageHeight)
        fragments.clear()
        super.endPage(page)
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
