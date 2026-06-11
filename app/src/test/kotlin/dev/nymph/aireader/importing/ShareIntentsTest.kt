package dev.nymph.aireader.importing

import dev.nymph.aireader.model.DocumentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareIntentsTest {

    // -- parseShareIntent ----------------------------------------------------

    @Test
    fun `pdf share with stream uri maps to Pdf source`() {
        val source = parseShareIntent(
            action = ACTION_SEND,
            mimeType = "application/pdf",
            text = null,
            streamUri = "content://provider/doc/42",
        )
        assertEquals(DocumentSource.Pdf("content://provider/doc/42"), source)
    }

    @Test
    fun `text share with plain url maps to WebPage source`() {
        val source = parseShareIntent(
            action = ACTION_SEND,
            mimeType = "text/plain",
            text = "https://example.com/article",
            streamUri = null,
        )
        assertEquals(DocumentSource.WebPage("https://example.com/article"), source)
    }

    @Test
    fun `text share extracts first url out of surrounding prose`() {
        val source = parseShareIntent(
            action = ACTION_SEND,
            mimeType = "text/plain",
            text = "Check this out: https://example.com/a-story. Amazing!",
            streamUri = null,
        )
        assertEquals(DocumentSource.WebPage("https://example.com/a-story"), source)
    }

    @Test
    fun `text share without url is rejected`() {
        assertNull(parseShareIntent(ACTION_SEND, "text/plain", "no links here", null))
    }

    @Test
    fun `wrong action is rejected`() {
        assertNull(
            parseShareIntent(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                text = "https://example.com",
                streamUri = null,
            ),
        )
    }

    @Test
    fun `pdf share without stream uri is rejected`() {
        assertNull(parseShareIntent(ACTION_SEND, "application/pdf", null, null))
    }

    @Test
    fun `unsupported mime type is rejected`() {
        assertNull(parseShareIntent(ACTION_SEND, "image/png", null, "content://x"))
    }

    // -- normalizeWebUrl -----------------------------------------------------

    @Test
    fun `bare host gets https scheme`() {
        assertEquals("https://example.com/a", normalizeWebUrl(" example.com/a "))
    }

    @Test
    fun `existing scheme is preserved`() {
        assertEquals("http://example.com", normalizeWebUrl("http://example.com"))
        assertEquals("https://example.com", normalizeWebUrl("https://example.com"))
    }

    @Test
    fun `blank input yields null`() {
        assertNull(normalizeWebUrl("   "))
    }

    // -- placeholderTitleFor -------------------------------------------------

    @Test
    fun `web placeholder strips scheme www query and trailing slash`() {
        assertEquals(
            "example.com/articles/story",
            placeholderTitleFor(DocumentSource.WebPage("https://www.example.com/articles/story/?utm=1")),
        )
    }

    @Test
    fun `pdf placeholder decodes the last segment`() {
        assertEquals(
            "My Paper.pdf",
            placeholderTitleFor(DocumentSource.Pdf("content://provider/document/My%20Paper.pdf")),
        )
    }

    @Test
    fun `pdf placeholder falls back when uri has no readable name`() {
        assertEquals("PDF document", placeholderTitleFor(DocumentSource.Pdf("content://p/d/")))
    }
}
