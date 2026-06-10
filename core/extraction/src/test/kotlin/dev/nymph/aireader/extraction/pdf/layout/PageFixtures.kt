package dev.nymph.aireader.extraction.pdf.layout

import dev.nymph.aireader.extraction.pdf.PdfLine

/** US-Letter points, the default page geometry for fixtures. */
const val PAGE_W = 612f
const val PAGE_H = 792f

/**
 * Builder for synthetic positioned-text pages. Width defaults to a crude but
 * deterministic glyph estimate (0.5em per char) so fixtures read naturally.
 */
class PageBuilder(
    private val pageIndex: Int,
    private val pageWidth: Float = PAGE_W,
    private val pageHeight: Float = PAGE_H,
) {
    private val lines = mutableListOf<PdfLine>()

    fun line(
        text: String,
        x: Float,
        y: Float,
        fontSize: Float = 12f,
        width: Float = text.length * fontSize * 0.5f,
    ): PageBuilder {
        lines += PdfLine(
            text = text,
            x = x,
            y = y,
            width = width,
            height = fontSize,
            fontSizePt = fontSize,
            pageIndex = pageIndex,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
        )
        return this
    }

    /** A run of body lines at a fixed leading, i.e. one visual paragraph. */
    fun para(
        vararg texts: String,
        x: Float = 72f,
        startY: Float,
        fontSize: Float = 12f,
        leading: Float = 14f,
    ): PageBuilder {
        texts.forEachIndexed { i, t -> line(t, x = x, y = startY + i * leading, fontSize = fontSize) }
        return this
    }

    fun build(): List<PdfLine> = lines.toList()
}

fun page(
    index: Int,
    pageWidth: Float = PAGE_W,
    pageHeight: Float = PAGE_H,
    build: PageBuilder.() -> Unit,
): List<PdfLine> = PageBuilder(index, pageWidth, pageHeight).apply(build).build()
