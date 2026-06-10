package dev.nymph.aireader.extraction.web

import dev.nymph.aireader.extraction.Extractor
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.ImportError
import dev.nymph.aireader.model.Outcome
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Web page -> main-content blocks: okhttp GET, Readability4J main-content
 * extraction, then [HtmlToBlocks] on the readability output.
 */
class WebPageExtractor(
    private val client: OkHttpClient = defaultClient(),
) : Extractor<DocumentSource.WebPage> {

    override suspend fun extract(source: DocumentSource.WebPage): Outcome<ExtractedDocument, ImportError> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(source.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Outcome.Err(ImportError.Network(source.url, "Invalid URL: ${e.message}"))
            }

            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                return@withContext Outcome.Err(ImportError.Network(source.url, "Request failed: ${e.message}"))
            }

            response.use { resp -> handleResponse(source, resp) }
        }

    private fun handleResponse(
        source: DocumentSource.WebPage,
        response: Response,
    ): Outcome<ExtractedDocument, ImportError> {
        if (!response.isSuccessful) {
            return Outcome.Err(ImportError.Network(source.url, "HTTP ${response.code}"))
        }
        val contentType = response.body?.contentType()
        val isHtml = contentType == null ||
            (contentType.type == "text" && contentType.subtype == "html") ||
            (contentType.type == "application" && contentType.subtype == "xhtml+xml")
        if (!isHtml) {
            return Outcome.Err(ImportError.Unreadable(source, "Not an HTML page: $contentType"))
        }

        val html = try {
            response.body?.string()
        } catch (e: IOException) {
            return Outcome.Err(ImportError.Network(source.url, "Failed reading response body: ${e.message}"))
        }
        if (html.isNullOrBlank()) {
            return Outcome.Err(ImportError.NoMainContent(source, "Empty response body"))
        }

        val article = Readability4J(source.url, html).parse()
        val articleHtml = article.contentWithUtf8Encoding
        if (articleHtml.isNullOrBlank()) {
            return Outcome.Err(ImportError.NoMainContent(source, "Readability found no main content"))
        }

        val blocks = HtmlToBlocks.convert(articleHtml)
        if (blocks.isEmpty()) {
            return Outcome.Err(ImportError.NoMainContent(source, "Main content had no speakable text"))
        }

        val title = article.title?.takeIf { it.isNotBlank() }
            ?: Jsoup.parse(html).title().takeIf { it.isNotBlank() }
        return Outcome.Ok(ExtractedDocument(title = title, blocks = blocks))
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
