package dev.nymph.aireader.pipeline.stages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSegmenterTest {

    // --- basic splitting ---

    @Test
    fun splitsOnPeriodBeforeCapital() {
        assertEquals(
            listOf("First sentence.", "Second sentence."),
            SentenceSegmenter.segment("First sentence. Second sentence."),
        )
    }

    @Test
    fun splitsOnQuestionAndExclamation() {
        assertEquals(
            listOf("Really?", "Yes!", "Done."),
            SentenceSegmenter.segment("Really? Yes! Done."),
        )
    }

    @Test
    fun closingQuoteStaysWithItsSentence() {
        assertEquals(
            listOf("He said \"stop.\"", "Then he left."),
            SentenceSegmenter.segment("He said \"stop.\" Then he left."),
        )
    }

    @Test
    fun splitsBeforeOpeningQuote() {
        assertEquals(
            listOf("She agreed.", "\"Fine.\""),
            SentenceSegmenter.segment("She agreed. \"Fine.\""),
        )
    }

    @Test
    fun closingParenStaysWithItsSentence() {
        assertEquals(
            listOf("It works (mostly).", "Next item."),
            SentenceSegmenter.segment("It works (mostly). Next item."),
        )
    }

    @Test
    fun parenthesizedSentenceSplits() {
        assertEquals(
            listOf("(See Fig. 2.)", "Next."),
            SentenceSegmenter.segment("(See Fig. 2.) Next."),
        )
    }

    @Test
    fun splitsBeforeDigitStart() {
        assertEquals(
            listOf("It ended.", "42 people left."),
            SentenceSegmenter.segment("It ended. 42 people left."),
        )
    }

    // --- protections ---

    @Test
    fun decimalsDoNotSplit() {
        assertEquals(
            listOf("Pi is about 3.14159 in value."),
            SentenceSegmenter.segment("Pi is about 3.14159 in value."),
        )
    }

    @Test
    fun initialsDoNotSplit() {
        assertEquals(
            listOf("J. R. R. Tolkien wrote it."),
            SentenceSegmenter.segment("J. R. R. Tolkien wrote it."),
        )
    }

    @Test
    fun acronymsMidSentenceDoNotSplit() {
        assertEquals(
            listOf("The U.S. Government acted."),
            SentenceSegmenter.segment("The U.S. Government acted."),
        )
    }

    @Test
    fun knownAbbreviationsDoNotSplit() {
        assertEquals(
            listOf("See Dr. Smith today."),
            SentenceSegmenter.segment("See Dr. Smith today."),
        )
        assertEquals(
            listOf("Bring apples, etc. Then leave."),
            SentenceSegmenter.segment("Bring apples, etc. Then leave."),
        )
        assertEquals(
            listOf("Fruits, e.g. Apples, are fine."),
            SentenceSegmenter.segment("Fruits, e.g. Apples, are fine."),
        )
    }

    @Test
    fun ellipsisSplitsOnlyBeforeCapital() {
        assertEquals(
            listOf("He waited...", "Then he left."),
            SentenceSegmenter.segment("He waited... Then he left."),
        )
        assertEquals(
            listOf("He waited... then he left."),
            SentenceSegmenter.segment("He waited... then he left."),
        )
        assertEquals(
            listOf("Counting... 3 items left."),
            SentenceSegmenter.segment("Counting... 3 items left."),
        )
    }

    // --- no terminal punctuation / degenerate input ---

    @Test
    fun headingWithoutPunctuationIsSinglePiece() {
        assertEquals(
            listOf("A heading without punctuation"),
            SentenceSegmenter.segment("A heading without punctuation"),
        )
    }

    @Test
    fun emptyAndBlankYieldNothing() {
        assertEquals(emptyList(), SentenceSegmenter.segment(""))
        assertEquals(emptyList(), SentenceSegmenter.segment("   "))
    }

    @Test
    fun singleWord() {
        assertEquals(listOf("hello"), SentenceSegmenter.segment("hello"))
    }

    // --- safety split ---

    @Test
    fun longSentenceSplitsAtLastClauseBoundaryBeforeLimit() {
        val a = "a".repeat(100)
        val b = "b".repeat(100)
        val c = "c".repeat(95)
        val input = "$a, $b, $c." // 300 chars, commas at 100 and 202
        assertEquals(
            listOf("$a, $b,", "$c."),
            SentenceSegmenter.safetySplit(input),
        )
    }

    @Test
    fun longSentenceWithoutClausesSplitsAtWordBoundary() {
        val input = "word ".repeat(70).trim() // 349 chars, no clause punctuation
        val pieces = SentenceSegmenter.safetySplit(input)
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.length <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertEquals(input, pieces.joinToString(" "))
    }

    @Test
    fun pathologicalSingleTokenHardSplits() {
        val input = "a".repeat(600)
        val pieces = SentenceSegmenter.safetySplit(input)
        assertTrue(pieces.all { it.length <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertEquals(input, pieces.joinToString(""))
        assertEquals(3, pieces.size)
    }

    @Test
    fun safetySplitRecursesUntilAllPiecesFit() {
        val clause = "x".repeat(90)
        val input = (1..5).joinToString(", ") { clause } + "." // ~460 chars
        val pieces = SentenceSegmenter.safetySplit(input)
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.all { it.length <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertTrue(pieces.all { it.isNotBlank() })
    }

    @Test
    fun segmentLeavesOverlongSentencesIntactForTheDisplaySide() {
        // Length capping happens on the TTS text in the pipeline; the display
        // sentence stays whole so the UI never shows a chopped sentence.
        val input = "alpha beta ".repeat(30).trim()
        assertEquals(listOf(input), SentenceSegmenter.segment(input))
    }

    @Test
    fun shortSentencesAreNotSplit() {
        assertEquals(
            listOf("A normal sentence, with a comma."),
            SentenceSegmenter.segment("A normal sentence, with a comma."),
        )
    }

    // --- enumeration markers ---

    @Test
    fun enumerationMarkerAtStartDoesNotSplit() {
        assertEquals(
            listOf("1. Introduction"),
            SentenceSegmenter.segment("1. Introduction"),
        )
        assertEquals(
            listOf("12. The Council of Elrond"),
            SentenceSegmenter.segment("12. The Council of Elrond"),
        )
    }

    @Test
    fun midTextDigitBeforePeriodStillSplits() {
        assertEquals(
            listOf("He was born in 1990.", "25 years later he left."),
            SentenceSegmenter.segment("He was born in 1990. 25 years later he left."),
        )
    }

    // --- ensureContinuationPunctuation ---

    @Test
    fun continuationKeepsExistingClausePunctuation() {
        assertEquals("first clause,", SentenceSegmenter.ensureContinuationPunctuation("first clause,"))
        assertEquals("first clause;", SentenceSegmenter.ensureContinuationPunctuation("first clause;"))
        assertEquals("first clause:", SentenceSegmenter.ensureContinuationPunctuation("first clause:"))
    }

    @Test
    fun continuationAppendsCommaToBareWordCut() {
        assertEquals("cut at a word,", SentenceSegmenter.ensureContinuationPunctuation("cut at a word"))
    }

    @Test
    fun continuationLeavesTerminalPunctuationAlone() {
        assertEquals("Done.", SentenceSegmenter.ensureContinuationPunctuation("Done."))
        assertEquals("Done!", SentenceSegmenter.ensureContinuationPunctuation("Done!"))
    }

    @Test
    fun continuationStripsTrailingHyphenBeforeComma() {
        assertEquals("ended with,", SentenceSegmenter.ensureContinuationPunctuation("ended with- "))
    }

    // --- ensureTerminalPunctuation ---

    @Test
    fun appendsPeriodWhenMissing() {
        assertEquals("Hello.", SentenceSegmenter.ensureTerminalPunctuation("Hello"))
        assertEquals("Introduction.", SentenceSegmenter.ensureTerminalPunctuation("Introduction"))
    }

    @Test
    fun leavesExistingTerminalPunctuation() {
        assertEquals("Hello.", SentenceSegmenter.ensureTerminalPunctuation("Hello."))
        assertEquals("Done!", SentenceSegmenter.ensureTerminalPunctuation("Done!"))
        assertEquals("Ok?", SentenceSegmenter.ensureTerminalPunctuation("Ok?"))
        assertEquals("wait...", SentenceSegmenter.ensureTerminalPunctuation("wait..."))
    }

    @Test
    fun replacesTrailingClausePunctuationWithPeriod() {
        assertEquals("Hello.", SentenceSegmenter.ensureTerminalPunctuation("Hello,"))
        assertEquals("clause.", SentenceSegmenter.ensureTerminalPunctuation("clause;"))
        assertEquals("clause.", SentenceSegmenter.ensureTerminalPunctuation("clause:"))
    }

    @Test
    fun keepsClosingQuoteAfterAppendedPeriod() {
        assertEquals(
            "He said \"go.\"",
            SentenceSegmenter.ensureTerminalPunctuation("He said \"go\""),
        )
        assertEquals(
            "Quote.\"",
            SentenceSegmenter.ensureTerminalPunctuation("Quote.\""),
        )
    }

    @Test
    fun emptyStringStaysEmpty() {
        assertEquals("", SentenceSegmenter.ensureTerminalPunctuation(""))
    }
}
