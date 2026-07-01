package dev.nymph.aireader.pipeline

import dev.nymph.aireader.model.ContentBlock
import dev.nymph.aireader.model.ExtractedDocument
import dev.nymph.aireader.model.SpeakableSentence
import dev.nymph.aireader.pipeline.stages.SpeakableExpander
import dev.nymph.aireader.pipeline.stages.SymbolNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTextPipelineTest {

    private val pipeline = DefaultTextPipeline()

    private fun realisticDocument() = ExtractedDocument(
        title = "Annual Report",
        blocks = listOf(
            ContentBlock.Heading("Introduction", level = 1), // 0
            ContentBlock.Paragraph( // 1: wraps, hyphenation, citation, URL, em dash
                "The committee found that inter-\nnational law applies [12]. See\n" +
                    "https://www.example.com/report?id=1 for details — the findings\nwere clear.",
            ),
            ContentBlock.Paragraph("   \n  "), // 2: whitespace-only, dropped
            ContentBlock.ListItem("• Use the hot-\nkey approach"), // 3
            ContentBlock.Paragraph("* * *"), // 4: normalizes to nothing, dropped
            ContentBlock.Quote("“It was 50% better,” she said."), // 5
        ),
    )

    @Test
    fun endToEndRealisticDocument() {
        val result = pipeline.process(realisticDocument())

        assertEquals("Annual Report", result.title)
        assertEquals(
            listOf(
                SpeakableSentence(
                    index = 0,
                    text = "Introduction.",
                    displayText = "Introduction",
                    blockIndex = 0,
                    pauseAfterMs = 700,
                ),
                SpeakableSentence(
                    index = 1,
                    text = "The committee found that international law applies.",
                    displayText = "The committee found that international law applies [12].",
                    blockIndex = 1,
                    pauseAfterMs = 0,
                ),
                SpeakableSentence(
                    index = 2,
                    text = "See example.com for details, the findings were clear.",
                    displayText = "See https://www.example.com/report?id=1 for details — " +
                        "the findings were clear.",
                    blockIndex = 1,
                    pauseAfterMs = 400,
                ),
                SpeakableSentence(
                    index = 3,
                    text = "Use the hotkey approach.",
                    displayText = "• Use the hotkey approach",
                    blockIndex = 3,
                    pauseAfterMs = 300,
                ),
                SpeakableSentence(
                    index = 4,
                    text = "\"It was 50 percent better,\" she said.",
                    displayText = "“It was 50% better,” she said.",
                    blockIndex = 5,
                    pauseAfterMs = 400,
                ),
            ),
            result.sentences,
        )
    }

    @Test
    fun normalizationIsIdempotentOnPipelineOutput() {
        // Re-feeding final TTS text through stages 2-3 must not change it.
        val result = pipeline.process(realisticDocument())
        for (s in result.sentences) {
            assertEquals(s.text, SymbolNormalizer.normalize(SpeakableExpander.expand(s.text)))
        }
    }

    @Test
    fun pausesWithinMultiSentenceBlock() {
        val result = pipeline.process(
            ExtractedDocument(null, listOf(ContentBlock.Paragraph("One. Two. Three."))),
        )
        assertEquals(listOf("One.", "Two.", "Three."), result.sentences.map { it.text })
        assertEquals(listOf(0, 0, 400), result.sentences.map { it.pauseAfterMs })
    }

    @Test
    fun blankLineSplitParagraphsEachGetParagraphPause() {
        val result = pipeline.process(
            ExtractedDocument(
                null,
                listOf(ContentBlock.Paragraph("First part one\nand two.\n\nSecond part.")),
            ),
        )
        assertEquals(
            listOf("First part one and two.", "Second part."),
            result.sentences.map { it.text },
        )
        assertEquals(listOf(400, 400), result.sentences.map { it.pauseAfterMs })
        assertEquals(listOf(0, 0), result.sentences.map { it.blockIndex })
    }

    @Test
    fun headingWithExistingTerminalPunctuationKeepsIt() {
        val result = pipeline.process(
            ExtractedDocument(null, listOf(ContentBlock.Heading("What Now?", level = 2))),
        )
        assertEquals(listOf("What Now?"), result.sentences.map { it.text })
        assertEquals(listOf(700), result.sentences.map { it.pauseAfterMs })
    }

    @Test
    fun emptyDocumentYieldsNoSentences() {
        val result = pipeline.process(ExtractedDocument(null, emptyList()))
        assertEquals(null, result.title)
        assertEquals(emptyList(), result.sentences)
    }

    @Test
    fun emptyAndSymbolOnlyBlocksProduceNothing() {
        val result = pipeline.process(
            ExtractedDocument(
                null,
                listOf(
                    ContentBlock.Paragraph(""),
                    ContentBlock.Paragraph("   "),
                    ContentBlock.Paragraph("* * *"),
                    ContentBlock.Paragraph("• — …"),
                ),
            ),
        )
        assertEquals(emptyList(), result.sentences)
    }

    @Test
    fun unicodeSurvivesEndToEnd() {
        val result = pipeline.process(
            ExtractedDocument(null, listOf(ContentBlock.Paragraph("Müller's café—résumé"))),
        )
        assertEquals(listOf("Müller's café, résumé."), result.sentences.map { it.text })
        assertEquals(listOf("Müller's café—résumé"), result.sentences.map { it.displayText })
    }

    @Test
    fun singleWordBlock() {
        val result = pipeline.process(
            ExtractedDocument(null, listOf(ContentBlock.Paragraph("hello"))),
        )
        assertEquals(listOf("hello."), result.sentences.map { it.text })
        assertEquals(listOf("hello"), result.sentences.map { it.displayText })
    }

    @Test
    fun longUnpunctuatedParagraphIsSafetySplit() {
        val result = pipeline.process(
            ExtractedDocument(
                null,
                listOf(ContentBlock.Paragraph(("alpha beta " .repeat(30)).trim())),
            ),
        )
        assertEquals(2, result.sentences.size)
        assertTrue(result.sentences.all { it.text.length <= 281 })
        // Mid-sentence cut: the first piece must NOT end with a period (that
        // makes the voice do a sentence-final fall mid-sentence); it ends with
        // continuation punctuation, and only the final piece is terminal.
        assertTrue(result.sentences.first().text.endsWith(","))
        assertTrue(result.sentences.last().text.endsWith("."))
        assertEquals(listOf(0, 400), result.sentences.map { it.pauseAfterMs })
        // Both pieces highlight the same (whole) display sentence in the UI.
        assertEquals(result.sentences[0].displayText, result.sentences[1].displayText)
    }

    @Test
    fun ellipsesNeverReachTheTtsText() {
        val result = pipeline.process(
            ExtractedDocument(
                null,
                listOf(ContentBlock.Paragraph("He waited… nothing happened. Then silence...")),
            ),
        )
        assertEquals(
            listOf("He waited, nothing happened.", "Then silence."),
            result.sentences.map { it.text },
        )
        assertTrue(result.sentences.none { "..." in it.text })
    }

    @Test
    fun numberedHeadingStaysOneChunk() {
        val result = pipeline.process(
            ExtractedDocument(
                null,
                listOf(ContentBlock.Heading("3. Methods", level = 2)),
            ),
        )
        assertEquals(listOf("3. Methods."), result.sentences.map { it.text })
    }

    @Test
    fun everySentenceEndsWithTerminalPunctuationAndHasNoNewlines() {
        val result = pipeline.process(realisticDocument())
        for (s in result.sentences) {
            assertTrue(s.text.last() in ".!?\"" , "bad ending: ${s.text}")
            assertTrue('\n' !in s.text)
            assertTrue('\r' !in s.text)
        }
    }

    @Test
    fun indicesAreContiguousFromZero() {
        val result = pipeline.process(realisticDocument())
        assertEquals(result.sentences.indices.toList(), result.sentences.map { it.index })
    }
}
