package dev.nymph.aireader.pipeline.stages

import kotlin.test.Test
import kotlin.test.assertEquals

class LineUnwrapperTest {

    @Test
    fun normalizesCrlfToSpaceJoin() {
        assertEquals(listOf("a b"), LineUnwrapper.unwrapBlock("a\r\nb"))
    }

    @Test
    fun normalizesBareCrToSpaceJoin() {
        assertEquals(listOf("a b"), LineUnwrapper.unwrapBlock("a\rb"))
    }

    @Test
    fun splitsParagraphsOnBlankLine() {
        assertEquals(
            listOf("para one", "para two"),
            LineUnwrapper.unwrapBlock("para one\n\npara two"),
        )
    }

    @Test
    fun splitsParagraphsOnWhitespaceOnlyBlankLine() {
        assertEquals(
            listOf("para one", "para two"),
            LineUnwrapper.unwrapBlock("para one\n  \t\npara two"),
        )
    }

    @Test
    fun collapsesRunsOfBlankLinesIntoOneBreak() {
        assertEquals(listOf("a", "b"), LineUnwrapper.unwrapBlock("a\n\n\n\nb"))
    }

    @Test
    fun preservesParagraphOrder() {
        assertEquals(
            listOf("one", "two", "three"),
            LineUnwrapper.unwrapBlock("one\n\ntwo\n\nthree"),
        )
    }

    @Test
    fun dehyphenatesLowercaseContinuation() {
        assertEquals(
            listOf("international law"),
            LineUnwrapper.unwrapBlock("inter-\nnational law"),
        )
    }

    @Test
    fun keepsHyphenWhenContinuationStartsUppercase() {
        assertEquals(
            listOf("anti-Marxist"),
            LineUnwrapper.unwrapBlock("anti-\nMarxist"),
        )
    }

    @Test
    fun keepsDoubleHyphenAtLineEnd() {
        // "--" is a dash, not hyphenation; join with a space and leave it alone.
        assertEquals(listOf("word-- next"), LineUnwrapper.unwrapBlock("word--\nnext"))
    }

    @Test
    fun dehyphenatesWithSpacesAroundTheBreak() {
        assertEquals(
            listOf("international"),
            LineUnwrapper.unwrapBlock("inter- \n national"),
        )
    }

    @Test
    fun keepsHyphenBeforeDigitContinuation() {
        assertEquals(listOf("F-16"), LineUnwrapper.unwrapBlock("F-\n16"))
    }

    @Test
    fun dehyphenatesAccentedLowercaseContinuation() {
        assertEquals(listOf("naïve"), LineUnwrapper.unwrapBlock("naï-\nve"))
    }

    @Test
    fun keepsHyphenBeforeAccentedUppercaseContinuation() {
        assertEquals(listOf("anti-Élite"), LineUnwrapper.unwrapBlock("anti-\nÉlite"))
    }

    @Test
    fun joinsIntraParagraphNewlinesWithSingleSpace() {
        assertEquals(listOf("word word"), LineUnwrapper.unwrapBlock("word\nword"))
    }

    @Test
    fun collapsesWhitespaceAroundJoinedNewlines() {
        assertEquals(listOf("word word"), LineUnwrapper.unwrapBlock("word \n word"))
    }

    @Test
    fun collapsesTabAndSpaceRuns() {
        assertEquals(listOf("a b c"), LineUnwrapper.unwrapBlock("a\t\tb   c"))
    }

    @Test
    fun emptyStringYieldsNoParagraphs() {
        assertEquals(emptyList(), LineUnwrapper.unwrapBlock(""))
    }

    @Test
    fun whitespaceOnlyYieldsNoParagraphs() {
        assertEquals(emptyList(), LineUnwrapper.unwrapBlock("  \n \t \n  "))
    }

    @Test
    fun singleWordPassesThrough() {
        assertEquals(listOf("hello"), LineUnwrapper.unwrapBlock("hello"))
    }

    @Test
    fun internalHyphensUntouched() {
        assertEquals(
            listOf("a well-known plan"),
            LineUnwrapper.unwrapBlock("a well-known plan"),
        )
    }

    @Test
    fun multiLineParagraphFullyUnwrapped() {
        assertEquals(
            listOf("The committee found that international law applies to all."),
            LineUnwrapper.unwrapBlock(
                "The committee found that inter-\nnational law applies\nto all.",
            ),
        )
    }
}
