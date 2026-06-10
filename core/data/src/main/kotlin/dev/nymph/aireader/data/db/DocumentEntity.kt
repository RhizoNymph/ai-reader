package dev.nymph.aireader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported document in the library.
 *
 * Column conventions:
 * - [sourceType] is one of [SOURCE_TYPE_PDF] / [SOURCE_TYPE_WEB]; [sourceRef] is the
 *   matching SAF content URI or page URL (see Mappers.kt for the typed round trip).
 * - [engineId]/[voiceId] are both null until the user picks a voice for this document.
 * - [status] is one of [DocumentStatus]'s db values; [errorMessage] is only non-null
 *   when status is "failed".
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceType: String,
    val sourceRef: String,
    val addedAtEpochMs: Long,
    val lastPlayedAtEpochMs: Long? = null,
    val sentenceCount: Int,
    val currentSentenceIndex: Int = 0,
    val engineId: String? = null,
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    val status: String,
    val errorMessage: String? = null,
)
