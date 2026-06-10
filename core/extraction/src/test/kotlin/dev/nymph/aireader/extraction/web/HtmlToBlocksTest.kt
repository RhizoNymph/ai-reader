package dev.nymph.aireader.extraction.web

import dev.nymph.aireader.model.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlToBlocksTest {

    @Test
    fun `headings map to Heading with the tag level`() {
        val html = """
            <h1>Top</h1>
            <h2>Second</h2>
            <h3>Third</h3>
            <h4>Fourth</h4>
            <h5>Fifth</h5>
            <h6>Sixth</h6>
        """.trimIndent()
        assertEquals(
            listOf(
                ContentBlock.Heading("Top", 1),
                ContentBlock.Heading("Second", 2),
                ContentBlock.Heading("Third", 3),
                ContentBlock.Heading("Fourth", 4),
                ContentBlock.Heading("Fifth", 5),
                ContentBlock.Heading("Sixth", 6),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `article-shaped html produces the expected block sequence`() {
        val html = """
            <div>
              <h1>The Article Title</h1>
              <p>First paragraph of the article body.</p>
              <div><p>Paragraph nested inside a div is flattened.</p></div>
              <ul>
                <li>First item</li>
                <li>Second item</li>
              </ul>
              <blockquote>Somebody once said something memorable.</blockquote>
              <p>Closing paragraph.</p>
            </div>
        """.trimIndent()
        assertEquals(
            listOf(
                ContentBlock.Heading("The Article Title", 1),
                ContentBlock.Paragraph("First paragraph of the article body."),
                ContentBlock.Paragraph("Paragraph nested inside a div is flattened."),
                ContentBlock.ListItem("First item"),
                ContentBlock.ListItem("Second item"),
                ContentBlock.Quote("Somebody once said something memorable."),
                ContentBlock.Paragraph("Closing paragraph."),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `unspeakable elements pre figure table are skipped entirely`() {
        val html = """
            <p>Before.</p>
            <pre>val x = 1 // code listing</pre>
            <figure><img src="x.png"><figcaption>A caption</figcaption></figure>
            <table><tr><td>cell one</td><td>cell two</td></tr></table>
            <p>After.</p>
        """.trimIndent()
        assertEquals(
            listOf(
                ContentBlock.Paragraph("Before."),
                ContentBlock.Paragraph("After."),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `blank blocks are dropped`() {
        val html = """
            <p>   </p>
            <h2></h2>
            <p>Real content.</p>
            <li>  </li>
        """.trimIndent()
        assertEquals(
            listOf(ContentBlock.Paragraph("Real content.")),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `deeply nested divs are flattened in document order`() {
        val html = """
            <div><div><div>
              <p>Deep one.</p>
            </div></div>
            <p>Shallow two.</p></div>
        """.trimIndent()
        assertEquals(
            listOf(
                ContentBlock.Paragraph("Deep one."),
                ContentBlock.Paragraph("Shallow two."),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `nested lists yield one ListItem per li without duplicated text`() {
        val html = "<ul><li>Parent item<ul><li>Child item</li></ul></li><li>Sibling</li></ul>"
        assertEquals(
            listOf(
                ContentBlock.ListItem("Parent item"),
                ContentBlock.ListItem("Child item"),
                ContentBlock.ListItem("Sibling"),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `ordered lists are treated like unordered lists`() {
        val html = "<ol><li>Step one</li><li>Step two</li></ol>"
        assertEquals(
            listOf(
                ContentBlock.ListItem("Step one"),
                ContentBlock.ListItem("Step two"),
            ),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `multi-paragraph blockquote becomes a single Quote`() {
        val html = "<blockquote><p>First quoted line.</p><p>Second quoted line.</p></blockquote>"
        assertEquals(
            listOf(ContentBlock.Quote("First quoted line. Second quoted line.")),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `inline markup inside a paragraph is collapsed to plain text`() {
        val html = "<p>Text with <strong>bold</strong> and <a href=\"#\">a link</a>.</p>"
        assertEquals(
            listOf(ContentBlock.Paragraph("Text with bold and a link.")),
            HtmlToBlocks.convert(html),
        )
    }

    @Test
    fun `leaf element with text but unknown tag becomes a paragraph`() {
        val html = "<span>Loose inline text at top level</span>"
        assertEquals(
            listOf(ContentBlock.Paragraph("Loose inline text at top level")),
            HtmlToBlocks.convert(html),
        )
    }
}
