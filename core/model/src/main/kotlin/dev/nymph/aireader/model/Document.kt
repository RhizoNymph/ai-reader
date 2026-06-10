package dev.nymph.aireader.model

/** Where a document came from. Stored so a document can be re-extracted later. */
sealed interface DocumentSource {
    /** SAF content URI of an imported PDF (persisted permission). */
    data class Pdf(val uri: String) : DocumentSource

    /** URL of an imported web page. */
    data class WebPage(val url: String) : DocumentSource
}

/**
 * Output of the extraction layer: main-content blocks only.
 * Headers, footers, page numbers, sidebars, and navigation have already been removed.
 */
data class ExtractedDocument(
    val title: String?,
    val blocks: List<ContentBlock>,
)

/**
 * A logical block of main-content text. Block boundaries are real semantic
 * boundaries (paragraph/heading/list item), never PDF line wraps.
 */
sealed interface ContentBlock {
    val text: String

    data class Heading(override val text: String, val level: Int) : ContentBlock
    data class Paragraph(override val text: String) : ContentBlock
    data class ListItem(override val text: String) : ContentBlock
    data class Quote(override val text: String) : ContentBlock
}
