package dev.nymph.aireader.data.db

import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.SpeakableDocument
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec

/**
 * Pure mapping functions between core:model types and Room entities.
 * No I/O, no Android dependencies; everything in this file is JVM-unit-tested.
 */

const val SOURCE_TYPE_PDF: String = "pdf"
const val SOURCE_TYPE_WEB: String = "web"

/** Title stored when the extraction layer could not find one. */
const val UNTITLED_TITLE: String = "Untitled"

/** Mirrors VoiceSpec's accepted speed range; stored values outside it are clamped. */
private val SPEED_RANGE = 0.5f..3.0f
private const val DEFAULT_SPEED = 1.0f

/** Typed view of [DocumentEntity.status]. Unknown stored values degrade to [FAILED]. */
enum class DocumentStatus(val dbValue: String) {
    READY("ready"),
    IMPORTING("importing"),
    FAILED("failed"),
    ;

    companion object {
        /**
         * Never throws: an unrecognized value (corrupt row, downgrade) maps to [FAILED]
         * so the UI refuses to play it rather than crashing or playing garbage.
         */
        fun fromDb(value: String): DocumentStatus =
            entries.firstOrNull { it.dbValue == value } ?: FAILED
    }
}

// ---------------------------------------------------------------------------
// DocumentSource <-> (sourceType, sourceRef)
// ---------------------------------------------------------------------------

fun sourceTypeOf(source: DocumentSource): String = when (source) {
    is DocumentSource.Pdf -> SOURCE_TYPE_PDF
    is DocumentSource.WebPage -> SOURCE_TYPE_WEB
}

fun sourceRefOf(source: DocumentSource): String = when (source) {
    is DocumentSource.Pdf -> source.uri
    is DocumentSource.WebPage -> source.url
}

/** Returns null for an unrecognized [sourceType] rather than throwing. */
fun documentSourceFromColumns(sourceType: String, sourceRef: String): DocumentSource? =
    when (sourceType) {
        SOURCE_TYPE_PDF -> DocumentSource.Pdf(sourceRef)
        SOURCE_TYPE_WEB -> DocumentSource.WebPage(sourceRef)
        else -> null
    }

// ---------------------------------------------------------------------------
// VoiceSpec <-> (engineId, voiceId, speed)
// ---------------------------------------------------------------------------

/** Flat column triple for a document's (possibly unset) voice. */
data class VoiceColumns(
    val engineId: String?,
    val voiceId: String?,
    val speed: Float,
)

/** A document with no chosen voice stores null engine/voice and the default speed. */
fun voiceColumnsOf(voice: VoiceSpec?): VoiceColumns =
    if (voice == null) {
        VoiceColumns(engineId = null, voiceId = null, speed = DEFAULT_SPEED)
    } else {
        VoiceColumns(engineId = voice.engineId.name, voiceId = voice.voiceId, speed = voice.speed)
    }

/**
 * Null when no voice has been chosen ([engineId] or [voiceId] null) or when the stored
 * engine id is unrecognized. Stored speeds are sanitized so VoiceSpec's range check can
 * never throw on persisted data.
 */
fun voiceSpecFromColumns(engineId: String?, voiceId: String?, speed: Float): VoiceSpec? {
    if (engineId == null || voiceId == null) return null
    val engine = TtsEngineId.entries.firstOrNull { it.name == engineId } ?: return null
    return VoiceSpec(engineId = engine, voiceId = voiceId, speed = sanitizeSpeed(speed))
}

private fun sanitizeSpeed(speed: Float): Float =
    if (speed.isFinite()) speed.coerceIn(SPEED_RANGE) else DEFAULT_SPEED

// ---------------------------------------------------------------------------
// SpeakableDocument <-> entities
// ---------------------------------------------------------------------------

/** New library row for a freshly imported document: position 0, no voice, no error. */
fun documentEntityFor(
    document: SpeakableDocument,
    source: DocumentSource,
    addedAtEpochMs: Long,
    status: DocumentStatus = DocumentStatus.READY,
): DocumentEntity = DocumentEntity(
    title = document.title ?: UNTITLED_TITLE,
    sourceType = sourceTypeOf(source),
    sourceRef = sourceRefOf(source),
    addedAtEpochMs = addedAtEpochMs,
    sentenceCount = document.sentences.size,
    status = status.dbValue,
)

fun sentenceEntitiesFor(docId: Long, document: SpeakableDocument): List<SentenceEntity> =
    document.sentences.map { sentence ->
        SentenceEntity(
            docId = docId,
            idx = sentence.index,
            text = sentence.text,
            displayText = sentence.displayText,
            blockIndex = sentence.blockIndex,
            pauseAfterMs = sentence.pauseAfterMs,
        )
    }

/**
 * Rebuilds the speakable document from its rows. [sentences] must be the document's
 * complete sentence set (idx contiguous from 0, an invariant of the import path);
 * ordering of the input list does not matter.
 */
fun speakableDocumentFrom(
    document: DocumentEntity,
    sentences: List<SentenceEntity>,
): SpeakableDocument = SpeakableDocument(
    title = document.title,
    sentences = sentences.sortedBy { it.idx }.map { row ->
        SpeakableSentence(
            index = row.idx,
            text = row.text,
            displayText = row.displayText,
            blockIndex = row.blockIndex,
            pauseAfterMs = row.pauseAfterMs,
        )
    },
)

// ---------------------------------------------------------------------------
// Library summaries
// ---------------------------------------------------------------------------

/** Fraction of the document already played, always within [0, 1]. */
fun progressFraction(currentSentenceIndex: Int, sentenceCount: Int): Float =
    if (sentenceCount <= 0) 0f
    else (currentSentenceIndex.toFloat() / sentenceCount).coerceIn(0f, 1f)

/** Null only for a row whose source columns are unparseable (should never happen). */
fun libraryItemFrom(document: DocumentEntity): LibraryItem? {
    val source = documentSourceFromColumns(document.sourceType, document.sourceRef) ?: return null
    return LibraryItem(
        id = document.id,
        title = document.title,
        source = source,
        sentenceCount = document.sentenceCount,
        currentSentenceIndex = document.currentSentenceIndex,
        progressFraction = progressFraction(document.currentSentenceIndex, document.sentenceCount),
        voice = voiceSpecFromColumns(document.engineId, document.voiceId, document.speed),
        status = DocumentStatus.fromDb(document.status),
    )
}
