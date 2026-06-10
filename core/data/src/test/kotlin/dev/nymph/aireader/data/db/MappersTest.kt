package dev.nymph.aireader.data.db

import dev.nymph.aireader.model.DocumentSource
import dev.nymph.aireader.model.SpeakableDocument
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.model.TtsEngineId
import dev.nymph.aireader.model.VoiceSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun sentence(index: Int): SpeakableSentence = SpeakableSentence(
    index = index,
    text = "normalized text $index",
    displayText = "Display text $index.",
    blockIndex = index / 2,
    pauseAfterMs = if (index % 2 == 0) 350 else 120,
)

private fun speakable(title: String? = "A Document", count: Int = 4): SpeakableDocument =
    SpeakableDocument(title = title, sentences = List(count) { sentence(it) })

private fun documentEntity(
    id: Long = 7L,
    title: String = "A Document",
    sourceType: String = SOURCE_TYPE_WEB,
    sourceRef: String = "https://example.com/article",
    sentenceCount: Int = 10,
    currentSentenceIndex: Int = 4,
    engineId: String? = null,
    voiceId: String? = null,
    speed: Float = 1.0f,
    status: String = DocumentStatus.READY.dbValue,
): DocumentEntity = DocumentEntity(
    id = id,
    title = title,
    sourceType = sourceType,
    sourceRef = sourceRef,
    addedAtEpochMs = 1_000L,
    lastPlayedAtEpochMs = null,
    sentenceCount = sentenceCount,
    currentSentenceIndex = currentSentenceIndex,
    engineId = engineId,
    voiceId = voiceId,
    speed = speed,
    status = status,
    errorMessage = null,
)

class DocumentSourceMappingTest {

    @Test
    fun `pdf source round trips through columns`() {
        val source = DocumentSource.Pdf("content://com.android.providers/document/42")
        val type = sourceTypeOf(source)
        val ref = sourceRefOf(source)
        assertEquals(SOURCE_TYPE_PDF, type)
        assertEquals("content://com.android.providers/document/42", ref)
        assertEquals(source, documentSourceFromColumns(type, ref))
    }

    @Test
    fun `web source round trips through columns`() {
        val source = DocumentSource.WebPage("https://example.com/post")
        val type = sourceTypeOf(source)
        val ref = sourceRefOf(source)
        assertEquals(SOURCE_TYPE_WEB, type)
        assertEquals("https://example.com/post", ref)
        assertEquals(source, documentSourceFromColumns(type, ref))
    }

    @Test
    fun `unknown source type maps to null instead of throwing`() {
        assertNull(documentSourceFromColumns("epub", "file:///book.epub"))
        assertNull(documentSourceFromColumns("", "https://example.com"))
    }
}

class VoiceSpecMappingTest {

    @Test
    fun `voice spec round trips through columns`() {
        val voice = VoiceSpec(TtsEngineId.PIPER, "en_US-amy-medium", 1.4f)
        val columns = voiceColumnsOf(voice)
        assertEquals("PIPER", columns.engineId)
        assertEquals("en_US-amy-medium", columns.voiceId)
        assertEquals(1.4f, columns.speed)
        assertEquals(voice, voiceSpecFromColumns(columns.engineId, columns.voiceId, columns.speed))
    }

    @Test
    fun `null voice maps to null columns with default speed`() {
        val columns = voiceColumnsOf(null)
        assertNull(columns.engineId)
        assertNull(columns.voiceId)
        assertEquals(1.0f, columns.speed)
    }

    @Test
    fun `missing engine or voice column maps to no voice`() {
        assertNull(voiceSpecFromColumns(null, "af_heart", 1.0f))
        assertNull(voiceSpecFromColumns("KOKORO", null, 1.0f))
        assertNull(voiceSpecFromColumns(null, null, 1.0f))
    }

    @Test
    fun `unknown engine id maps to no voice instead of throwing`() {
        assertNull(voiceSpecFromColumns("ESPEAK", "voice", 1.0f))
        assertNull(voiceSpecFromColumns("", "voice", 1.0f))
    }

    @Test
    fun `out of range stored speed is clamped instead of throwing`() {
        assertEquals(0.5f, voiceSpecFromColumns("KOKORO", "af_heart", 0.1f)?.speed)
        assertEquals(3.0f, voiceSpecFromColumns("KOKORO", "af_heart", 99f)?.speed)
        assertEquals(2.0f, voiceSpecFromColumns("KOKORO", "af_heart", 2.0f)?.speed)
    }

    @Test
    fun `non finite stored speed falls back to default`() {
        assertEquals(1.0f, voiceSpecFromColumns("KOKORO", "af_heart", Float.NaN)?.speed)
        assertEquals(1.0f, voiceSpecFromColumns("KOKORO", "af_heart", Float.POSITIVE_INFINITY)?.speed)
    }
}

class DocumentEntityMappingTest {

    @Test
    fun `new document entity is ready with zero position and no voice`() {
        val source = DocumentSource.WebPage("https://example.com/a")
        val entity = documentEntityFor(speakable(count = 4), source, addedAtEpochMs = 123_456L)

        assertEquals(0L, entity.id)
        assertEquals("A Document", entity.title)
        assertEquals(SOURCE_TYPE_WEB, entity.sourceType)
        assertEquals("https://example.com/a", entity.sourceRef)
        assertEquals(123_456L, entity.addedAtEpochMs)
        assertNull(entity.lastPlayedAtEpochMs)
        assertEquals(4, entity.sentenceCount)
        assertEquals(0, entity.currentSentenceIndex)
        assertNull(entity.engineId)
        assertNull(entity.voiceId)
        assertEquals(1.0f, entity.speed)
        assertEquals(DocumentStatus.READY.dbValue, entity.status)
        assertNull(entity.errorMessage)
    }

    @Test
    fun `null model title falls back to untitled`() {
        val entity = documentEntityFor(speakable(title = null), DocumentSource.Pdf("content://x"), 0L)
        assertEquals(UNTITLED_TITLE, entity.title)
    }

    @Test
    fun `explicit status is preserved`() {
        val entity = documentEntityFor(
            speakable(),
            DocumentSource.Pdf("content://x"),
            0L,
            status = DocumentStatus.IMPORTING,
        )
        assertEquals(DocumentStatus.IMPORTING.dbValue, entity.status)
    }
}

class SentenceEntityMappingTest {

    @Test
    fun `sentence entities carry every field and the document id`() {
        val doc = speakable(count = 3)
        val entities = sentenceEntitiesFor(docId = 9L, document = doc)

        assertEquals(3, entities.size)
        entities.forEachIndexed { i, entity ->
            assertEquals(9L, entity.docId)
            assertEquals(i, entity.idx)
            assertEquals(doc.sentences[i].text, entity.text)
            assertEquals(doc.sentences[i].displayText, entity.displayText)
            assertEquals(doc.sentences[i].blockIndex, entity.blockIndex)
            assertEquals(doc.sentences[i].pauseAfterMs, entity.pauseAfterMs)
        }
    }

    @Test
    fun `empty document maps to no sentence rows`() {
        assertEquals(emptyList(), sentenceEntitiesFor(1L, speakable(count = 0)))
    }
}

class SpeakableRoundTripTest {

    @Test
    fun `document with titled content round trips exactly`() {
        val original = speakable(title = "Round Trip", count = 5)
        val source = DocumentSource.Pdf("content://docs/5")
        val docEntity = documentEntityFor(original, source, addedAtEpochMs = 1L).copy(id = 11L)
        val sentenceEntities = sentenceEntitiesFor(docEntity.id, original)

        assertEquals(original, speakableDocumentFrom(docEntity, sentenceEntities))
    }

    @Test
    fun `untitled document round trips with the fallback title`() {
        val original = speakable(title = null, count = 2)
        val docEntity = documentEntityFor(original, DocumentSource.WebPage("https://e.com"), 1L).copy(id = 2L)
        val restored = speakableDocumentFrom(docEntity, sentenceEntitiesFor(2L, original))

        assertEquals(UNTITLED_TITLE, restored.title)
        assertEquals(original.sentences, restored.sentences)
    }

    @Test
    fun `sentences are ordered by idx even if rows arrive shuffled`() {
        val original = speakable(count = 4)
        val docEntity = documentEntityFor(original, DocumentSource.WebPage("https://e.com"), 1L).copy(id = 3L)
        val shuffled = sentenceEntitiesFor(3L, original).reversed()

        assertEquals(original.sentences, speakableDocumentFrom(docEntity, shuffled).sentences)
    }
}

class ProgressFractionTest {

    @Test
    fun `zero sentence count yields zero progress`() {
        assertEquals(0f, progressFraction(currentSentenceIndex = 0, sentenceCount = 0))
        assertEquals(0f, progressFraction(currentSentenceIndex = 5, sentenceCount = 0))
    }

    @Test
    fun `progress is index over count`() {
        assertEquals(0f, progressFraction(0, 10))
        assertEquals(0.5f, progressFraction(5, 10))
        assertEquals(0.9f, progressFraction(9, 10))
    }

    @Test
    fun `progress is clamped to the unit interval`() {
        assertEquals(1f, progressFraction(15, 10))
        assertEquals(0f, progressFraction(-2, 10))
    }
}

class DocumentStatusTest {

    @Test
    fun `known db values parse to their status`() {
        assertEquals(DocumentStatus.READY, DocumentStatus.fromDb("ready"))
        assertEquals(DocumentStatus.IMPORTING, DocumentStatus.fromDb("importing"))
        assertEquals(DocumentStatus.FAILED, DocumentStatus.fromDb("failed"))
    }

    @Test
    fun `unknown db value falls back to failed instead of throwing`() {
        assertEquals(DocumentStatus.FAILED, DocumentStatus.fromDb("corrupted"))
        assertEquals(DocumentStatus.FAILED, DocumentStatus.fromDb(""))
    }
}

class LibraryItemMappingTest {

    @Test
    fun `entity maps to a full library item`() {
        val entity = documentEntity(
            id = 21L,
            title = "Long Read",
            sourceType = SOURCE_TYPE_PDF,
            sourceRef = "content://docs/21",
            sentenceCount = 200,
            currentSentenceIndex = 50,
            engineId = "KOKORO",
            voiceId = "af_heart",
            speed = 1.2f,
        )

        val item = libraryItemFrom(entity)

        assertEquals(
            LibraryItem(
                id = 21L,
                title = "Long Read",
                source = DocumentSource.Pdf("content://docs/21"),
                sentenceCount = 200,
                currentSentenceIndex = 50,
                progressFraction = 0.25f,
                voice = VoiceSpec(TtsEngineId.KOKORO, "af_heart", 1.2f),
                status = DocumentStatus.READY,
            ),
            item,
        )
    }

    @Test
    fun `entity without a chosen voice maps to a null voice`() {
        assertNull(libraryItemFrom(documentEntity())?.voice)
    }

    @Test
    fun `entity with unknown source type maps to null`() {
        assertNull(libraryItemFrom(documentEntity(sourceType = "epub")))
    }

    @Test
    fun `entity with unknown status maps to failed`() {
        assertEquals(DocumentStatus.FAILED, libraryItemFrom(documentEntity(status = "???"))?.status)
    }
}
