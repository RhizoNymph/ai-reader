package dev.nymph.aireader.data.db

import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.SpeakableDocument
import dev.nymph.aireader.model.VoiceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Summary of one library document for the library list UI. */
data class LibraryItem(
    val id: Long,
    val title: String,
    val source: DocumentSource,
    val sentenceCount: Int,
    val currentSentenceIndex: Int,
    /** Played fraction in [0, 1]; 0 for empty documents. */
    val progressFraction: Float,
    /** Null until a voice has been chosen for this document. */
    val voice: VoiceSpec?,
    val status: DocumentStatus,
)

/**
 * Model-typed facade over the Room DAOs. All entity<->model conversion goes through
 * the pure functions in Mappers.kt; this class only sequences DAO calls.
 */
class LibraryRepository(
    private val documentDao: DocumentDao,
    private val sentenceDao: SentenceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Library ordered by most recently played, then most recently added.
     * Rows with unparseable source columns are dropped defensively.
     */
    fun observeLibrary(): Flow<List<LibraryItem>> =
        documentDao.observeAll().map { documents -> documents.mapNotNull(::libraryItemFrom) }

    /**
     * Persists a finished import: the document row plus its complete sentence set in a
     * single transaction. Returns the new document id. Re-importing a source creates a
     * new document; replacing an existing one is delete + import.
     */
    suspend fun importDocument(source: DocumentSource, document: SpeakableDocument): Long =
        documentDao.insertDocumentWithSentences(
            document = documentEntityFor(document, source, addedAtEpochMs = clock()),
            sentencesFor = { docId -> sentenceEntitiesFor(docId, document) },
        )

    /** Full speakable document for playback, or null if the id is unknown. */
    suspend fun getSpeakable(docId: Long): SpeakableDocument? {
        val document = documentDao.getById(docId) ?: return null
        return speakableDocumentFrom(document, sentenceDao.getForDocument(docId))
    }

    /** Records the playhead and stamps last-played with the repository clock. */
    suspend fun updatePosition(docId: Long, sentenceIndex: Int) {
        documentDao.updatePosition(docId, sentenceIndex, lastPlayedAtEpochMs = clock())
    }

    /** Sets or clears (null) the document's voice choice. */
    suspend fun updateVoice(docId: Long, voice: VoiceSpec?) {
        val columns = voiceColumnsOf(voice)
        documentDao.updateVoice(docId, columns.engineId, columns.voiceId, columns.speed)
    }

    /** [errorMessage] is persisted only for [DocumentStatus.FAILED]. */
    suspend fun updateStatus(docId: Long, status: DocumentStatus, errorMessage: String? = null) {
        documentDao.updateStatus(
            docId = docId,
            status = status.dbValue,
            errorMessage = errorMessage.takeIf { status == DocumentStatus.FAILED },
        )
    }

    /** Deletes the document; its sentences go with it via FK cascade. */
    suspend fun delete(docId: Long) {
        documentDao.deleteById(docId)
    }
}
