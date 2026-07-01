package dev.nymph.aireader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    /** Library order: most recently played first, never-played docs after, newest added first. */
    @Query(
        """
        SELECT * FROM documents
        ORDER BY lastPlayedAtEpochMs IS NULL ASC,
                 lastPlayedAtEpochMs DESC,
                 addedAtEpochMs DESC
        """,
    )
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :docId")
    fun observeById(docId: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :docId")
    suspend fun getById(docId: Long): DocumentEntity?

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :docId")
    suspend fun deleteById(docId: Long)

    @Query(
        """
        UPDATE documents
        SET currentSentenceIndex = :sentenceIndex, lastPlayedAtEpochMs = :lastPlayedAtEpochMs
        WHERE id = :docId
        """,
    )
    suspend fun updatePosition(docId: Long, sentenceIndex: Int, lastPlayedAtEpochMs: Long)

    @Query(
        """
        UPDATE documents
        SET engineId = :engineId, voiceId = :voiceId, speed = :speed
        WHERE id = :docId
        """,
    )
    suspend fun updateVoice(docId: Long, engineId: String?, voiceId: String?, speed: Float)

    @Query("UPDATE documents SET status = :status, errorMessage = :errorMessage WHERE id = :docId")
    suspend fun updateStatus(docId: Long, status: String, errorMessage: String?)

    @Query("UPDATE documents SET title = :title WHERE id = :docId")
    suspend fun updateTitle(docId: Long, title: String)

    @Insert
    suspend fun insertSentences(sentences: List<SentenceEntity>)

    /**
     * Atomically inserts a document and its complete sentence set. [sentencesFor]
     * receives the generated document id and must be pure (it may run inside the
     * transaction more than once if Room retries).
     */
    @Transaction
    suspend fun insertDocumentWithSentences(
        document: DocumentEntity,
        sentencesFor: (docId: Long) -> List<SentenceEntity>,
    ): Long {
        val docId = insert(document)
        insertSentences(sentencesFor(docId))
        return docId
    }
}
