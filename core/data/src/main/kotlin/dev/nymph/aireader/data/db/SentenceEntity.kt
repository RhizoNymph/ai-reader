package dev.nymph.aireader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One TTS-ready sentence of a document, keyed by (docId, idx).
 *
 * Rows are immutable after import: a document's sentences are only ever written as a
 * complete set (insert on import, cascade-delete with the document, or delete+reinsert
 * on re-import). [idx] values for a document are always contiguous from 0.
 */
@Entity(
    tableName = "sentences",
    primaryKeys = ["docId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("docId")],
)
data class SentenceEntity(
    val docId: Long,
    val idx: Int,
    /** Normalized text handed to the TTS engine. */
    val text: String,
    /** Text as shown in the UI. */
    val displayText: String,
    /** Index of the source ContentBlock; used for paragraph-level UI grouping. */
    val blockIndex: Int,
    /** Silence inserted after this sentence. */
    val pauseAfterMs: Int,
)
