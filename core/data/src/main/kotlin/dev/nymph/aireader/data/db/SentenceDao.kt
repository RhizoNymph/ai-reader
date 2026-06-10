package dev.nymph.aireader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SentenceDao {

    /** Room executes list inserts inside a single transaction. */
    @Insert
    suspend fun insertAll(sentences: List<SentenceEntity>)

    @Query("SELECT * FROM sentences WHERE docId = :docId ORDER BY idx ASC")
    suspend fun getForDocument(docId: Long): List<SentenceEntity>

    /** [count] sentences starting at idx [fromIdx]; used by synthesis lookahead. */
    @Query(
        """
        SELECT * FROM sentences
        WHERE docId = :docId AND idx >= :fromIdx
        ORDER BY idx ASC
        LIMIT :count
        """,
    )
    suspend fun getRange(docId: Long, fromIdx: Int, count: Int): List<SentenceEntity>

    @Query("DELETE FROM sentences WHERE docId = :docId")
    suspend fun deleteForDocument(docId: Long)
}
