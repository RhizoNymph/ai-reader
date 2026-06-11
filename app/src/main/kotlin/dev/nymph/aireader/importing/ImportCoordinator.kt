package dev.nymph.aireader.importing

import dev.nymph.aireader.data.db.DocumentStatus
import dev.nymph.aireader.data.db.LibraryRepository
import dev.nymph.aireader.extraction.Extractor
import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.ImportError
import dev.nymph.aireader.model.Outcome
import dev.nymph.aireader.model.SpeakableDocument
import dev.nymph.aireader.pipeline.TextPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Typed result of one import run. */
sealed interface ImportResult {
    data class Success(val docId: Long) : ImportResult

    /** [docId] is the failed placeholder row left in the library (status FAILED). */
    data class Failure(val docId: Long, val error: ImportError, val userMessage: String) : ImportResult
}

/**
 * The single user-facing mapping of [ImportError] to a short message; the only
 * place import errors are turned into strings.
 */
fun userMessageFor(error: ImportError): String = when (error) {
    is ImportError.Unreadable -> "Couldn't read this document."
    is ImportError.Network -> "Couldn't fetch the page. Check the URL and your connection."
    is ImportError.NoMainContent -> "No readable main content was found."
    is ImportError.Encrypted -> "This PDF is password-protected and can't be imported."
}

/**
 * Drives one import end to end: extractor -> text pipeline -> library.
 *
 * Create-then-fill: a placeholder row (status IMPORTING) is inserted before
 * extraction starts so the library shows progress immediately. On success the
 * real document is inserted (status READY) and the placeholder is removed; on
 * failure the placeholder flips to FAILED so the library can offer retry/delete.
 *
 * core:data has no API to read back a FAILED row's stored errorMessage, so the
 * message is also kept in the in-memory [failureMessages] map (docId -> text)
 * for the library UI; it resets on process death, the DB row's status survives.
 */
class ImportCoordinator(
    private val webExtractor: Extractor<DocumentSource.WebPage>,
    private val pdfExtractor: Extractor<DocumentSource.Pdf>,
    private val pipeline: TextPipeline,
    private val library: LibraryRepository,
) {

    private val _failureMessages = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** User-facing message for each FAILED row created by this process. */
    val failureMessages: StateFlow<Map<Long, String>> = _failureMessages.asStateFlow()

    suspend fun import(source: DocumentSource): ImportResult {
        val placeholderId = library.importDocument(
            source,
            SpeakableDocument(title = placeholderTitleFor(source), sentences = emptyList()),
        )
        library.updateStatus(placeholderId, DocumentStatus.IMPORTING)

        val extracted = when (source) {
            is DocumentSource.Pdf -> pdfExtractor.extract(source)
            is DocumentSource.WebPage -> webExtractor.extract(source)
        }
        val document = when (extracted) {
            is Outcome.Err -> return fail(placeholderId, extracted.error)
            is Outcome.Ok -> extracted.value
        }

        // Pure CPU work; keep it off the caller's dispatcher assumption-free.
        val speakable = withContext(Dispatchers.Default) { pipeline.process(document) }
        if (speakable.sentences.isEmpty()) {
            return fail(
                placeholderId,
                ImportError.NoMainContent(source, "Pipeline produced no speakable sentences"),
            )
        }

        // Insert the real row first, then drop the placeholder, so the library
        // never flashes empty between the two writes.
        val docId = library.importDocument(source, speakable)
        library.delete(placeholderId)
        clearFailure(placeholderId)
        return ImportResult.Success(docId)
    }

    /** Forget the in-memory failure message for a deleted/retried row. */
    fun clearFailure(docId: Long) {
        _failureMessages.update { it - docId }
    }

    private suspend fun fail(placeholderId: Long, error: ImportError): ImportResult.Failure {
        val message = userMessageFor(error)
        library.updateStatus(placeholderId, DocumentStatus.FAILED, errorMessage = message)
        _failureMessages.update { it + (placeholderId to message) }
        return ImportResult.Failure(placeholderId, error, message)
    }
}
