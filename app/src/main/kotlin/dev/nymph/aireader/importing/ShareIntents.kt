package dev.nymph.aireader.importing

import dev.nymph.aireader.model.DocumentSource
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Pure helpers for turning incoming share intents and user-typed URLs into
 * [DocumentSource]s. Deliberately free of Android types (string in, model out)
 * so they are JVM-unit-testable; MainActivity extracts the raw intent fields.
 */

/** [android.content.Intent.ACTION_SEND] without depending on the Android SDK here. */
const val ACTION_SEND: String = "android.intent.action.SEND"

private val URL_REGEX = Regex("""https?://\S+""")

/** Characters commonly glued onto a URL shared inside prose ("see https://x.com/y."). */
private const val URL_TRAILING_JUNK = ".,;:!?)]}>\"'"

/**
 * Maps a share intent's raw fields to an importable source, or null when the
 * intent is not an import we understand.
 *
 * - `application/pdf` + a stream URI -> [DocumentSource.Pdf]
 * - `text/...` + text containing an http(s) URL -> [DocumentSource.WebPage]
 *   (first URL found; trailing punctuation stripped)
 */
fun parseShareIntent(
    action: String?,
    mimeType: String?,
    text: String?,
    streamUri: String?,
): DocumentSource? {
    if (action != ACTION_SEND || mimeType == null) return null
    return when {
        mimeType == "application/pdf" && !streamUri.isNullOrBlank() ->
            DocumentSource.Pdf(streamUri)

        mimeType.startsWith("text/") && text != null ->
            URL_REGEX.find(text)?.value
                ?.trimEnd { it in URL_TRAILING_JUNK }
                ?.takeIf { it.length > "https://".length }
                ?.let { DocumentSource.WebPage(it) }

        else -> null
    }
}

/**
 * Normalizes a user-typed URL: trims whitespace, prepends `https://` when no
 * scheme is present. Returns null for blank input.
 */
fun normalizeWebUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/**
 * Short human-readable title for the placeholder library row shown while an
 * import is still running (the real title arrives with extraction).
 */
fun placeholderTitleFor(source: DocumentSource): String = when (source) {
    is DocumentSource.WebPage ->
        source.url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .ifBlank { "Web page" }

    is DocumentSource.Pdf -> {
        val lastSegment = source.uri
            .substringBefore('?')
            .substringAfterLast('/')
            .substringAfterLast(':') // SAF document ids often end "...document:name.pdf"
        val decoded = try {
            URLDecoder.decode(lastSegment, "UTF-8")
        } catch (e: IllegalArgumentException) {
            lastSegment
        } catch (e: UnsupportedEncodingException) {
            lastSegment
        }
        decoded.ifBlank { "PDF document" }
    }
}
