package dev.nymph.aireader.extraction.web

import dev.nymph.aireader.model.ContentBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Pure HTML -> [ContentBlock] conversion for readability-extracted article
 * HTML. Walks elements in document order:
 *
 * - h1..h6 -> [ContentBlock.Heading] with the tag's level
 * - p -> [ContentBlock.Paragraph]
 * - li -> [ContentBlock.ListItem] (nested lists yield their own items)
 * - blockquote -> a single [ContentBlock.Quote]
 * - pre / figure / table -> skipped (unspeakable)
 * - div / section / other containers -> flattened (children walked)
 * - leaf elements with text but no recognized tag -> [ContentBlock.Paragraph]
 *
 * Blocks whose text is blank are dropped. Text is jsoup-normalized (inline
 * markup collapsed, whitespace folded).
 */
object HtmlToBlocks {

    private val SKIPPED_TAGS = setOf("pre", "figure", "table", "script", "style", "noscript", "iframe")
    private val LIST_TAGS = setOf("ul", "ol")

    fun convert(html: String): List<ContentBlock> {
        val body = Jsoup.parse(html).body()
        val out = mutableListOf<ContentBlock>()
        walk(body.children(), out)
        return out
    }

    private fun walk(elements: List<Element>, out: MutableList<ContentBlock>) {
        for (el in elements) {
            val tag = el.tagName().lowercase()
            when {
                tag in SKIPPED_TAGS -> Unit

                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' ->
                    add(out, el.text()) { ContentBlock.Heading(it, tag[1] - '0') }

                tag == "p" -> add(out, el.text()) { ContentBlock.Paragraph(it) }

                tag == "blockquote" -> add(out, el.text()) { ContentBlock.Quote(it) }

                tag in LIST_TAGS -> walk(el.children(), out)

                tag == "li" -> {
                    add(out, ownTextWithoutNestedLists(el)) { ContentBlock.ListItem(it) }
                    walk(el.children().filter { it.tagName().lowercase() in LIST_TAGS }, out)
                }

                el.children().isEmpty() -> add(out, el.text()) { ContentBlock.Paragraph(it) }

                else -> walk(el.children(), out) // flatten divs/sections/etc.
            }
        }
    }

    /** The li's own text with any nested list content removed. */
    private fun ownTextWithoutNestedLists(li: Element): String {
        val clone = li.clone()
        clone.select("ul, ol").remove()
        return clone.text()
    }

    private inline fun add(out: MutableList<ContentBlock>, text: String, make: (String) -> ContentBlock) {
        if (text.isNotBlank()) out += make(text.trim())
    }
}
