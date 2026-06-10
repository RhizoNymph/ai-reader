package dev.nymph.aireader.extraction.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import dev.nymph.aireader.extraction.Extractor
import dev.nymph.aireader.extraction.pdf.layout.LayoutAnalyzer
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.ImportError
import dev.nymph.aireader.model.Outcome
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF -> main-content blocks. Wires the Android-touching [PdfTextCollector]
 * to the pure-Kotlin [LayoutAnalyzer].
 *
 * The [openStream] lambda resolves a [DocumentSource.Pdf.uri] (a SAF content
 * URI in the app) to an [InputStream]; injecting it keeps this class free of
 * Android `ContentResolver` types and testable.
 *
 * NOTE: the app must call `PDFBoxResourceLoader.init(context)` once before
 * the first extraction (PdfBox-Android requirement).
 */
class PdfExtractor(
    private val openStream: (String) -> Outcome<InputStream, ImportError>,
) : Extractor<DocumentSource.Pdf> {

    override suspend fun extract(source: DocumentSource.Pdf): Outcome<ExtractedDocument, ImportError> =
        withContext(Dispatchers.IO) {
            val stream = when (val opened = openStream(source.uri)) {
                is Outcome.Err -> return@withContext opened
                is Outcome.Ok -> opened.value
            }

            // PDDocument.load reads the stream fully, so it can be closed right after.
            val document = try {
                stream.use { PDDocument.load(it) }
            } catch (e: InvalidPasswordException) {
                return@withContext Outcome.Err(
                    ImportError.Encrypted(source, "PDF is password protected: ${e.message}"),
                )
            } catch (e: IOException) {
                return@withContext Outcome.Err(
                    ImportError.Unreadable(source, "Failed to open PDF: ${e.message}"),
                )
            }

            document.use { doc -> extractFromDocument(source, doc) }
        }

    private fun extractFromDocument(
        source: DocumentSource.Pdf,
        document: PDDocument,
    ): Outcome<ExtractedDocument, ImportError> {
        if (document.numberOfPages == 0) {
            return Outcome.Err(ImportError.NoMainContent(source, "PDF has no pages"))
        }

        val pages = try {
            PdfTextCollector().collect(document)
        } catch (e: IOException) {
            return Outcome.Err(ImportError.Unreadable(source, "Failed to read PDF text: ${e.message}"))
        }
        if (pages.all { page -> page.all { it.text.isBlank() } }) {
            return Outcome.Err(
                ImportError.NoMainContent(source, "PDF contains no extractable text (scanned image?)"),
            )
        }

        val blocks = LayoutAnalyzer.analyze(pages)
        if (blocks.isEmpty()) {
            return Outcome.Err(ImportError.NoMainContent(source, "No main content found after layout analysis"))
        }

        val title = document.documentInformation?.title?.takeIf { it.isNotBlank() }
        return Outcome.Ok(ExtractedDocument(title = title, blocks = blocks))
    }
}
