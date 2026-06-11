package dev.nymph.aireader.pipeline.stages

import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolNormalizerTest {

    private fun n(s: String): String = SymbolNormalizer.normalize(s)

    // --- quotes ---

    @Test
    fun straightensCurlyQuotes() {
        assertEquals("'a' \"b\"", n("‘a’ “b”"))
    }

    @Test
    fun straightensCurlyApostropheInWord() {
        assertEquals("don't", n("don’t"))
    }

    @Test
    fun straightensGuillemets() {
        assertEquals("\"word\"", n("«word»"))
    }

    // --- ellipses ---

    // espeak-ng reads "..." literally as "dot dot dot", so ellipses must never
    // reach the engine: they become a comma pause instead.

    @Test
    fun ellipsisCharBecomesCommaPause() {
        assertEquals("wait, ok", n("wait… ok"))
    }

    @Test
    fun threeDotsBecomeCommaPause() {
        assertEquals("wait, ok", n("wait... ok"))
    }

    @Test
    fun dotRunsBecomeCommaPause() {
        assertEquals("wait, ok", n("wait..... ok"))
        assertEquals("wait, ok", n("wait.... ok"))
    }

    @Test
    fun spacedEllipsisBecomesCommaPause() {
        assertEquals("wait, ok", n("wait . . . ok"))
    }

    @Test
    fun trailingEllipsisBecomesTrailingComma() {
        assertEquals("wait,", n("wait..."))
    }

    @Test
    fun doubledDotTypoCollapsesToOnePeriod() {
        assertEquals("done. Next", n("done.. Next"))
    }

    @Test
    fun initialsAndDecimalsAreNotEllipses() {
        assertEquals("J. R. R. Tolkien", n("J. R. R. Tolkien"))
        assertEquals("about 3.14 then", n("about 3.14 then"))
    }

    // --- dashes ---

    @Test
    fun emDashBecomesCommaPause() {
        assertEquals("one, two", n("one—two"))
        assertEquals("one, two", n("one — two"))
    }

    @Test
    fun doubleHyphenBecomesCommaPause() {
        assertEquals("one, two", n("one -- two"))
        assertEquals("one, two", n("one--two"))
    }

    @Test
    fun adjacentCommasDeduped() {
        assertEquals("one, two", n("one, — two"))
    }

    @Test
    fun enDashBetweenDigitsBecomesTo() {
        assertEquals("pages 3 to 5", n("pages 3–5"))
        assertEquals("pages 3 to 5", n("pages 3 – 5"))
    }

    @Test
    fun enDashBetweenWordsBecomesCommaPause() {
        assertEquals("rock, paper", n("rock–paper"))
    }

    @Test
    fun trailingDashBeforePeriodCleaned() {
        assertEquals("It was over.", n("It was over —."))
    }

    @Test
    fun leadingDashStripped() {
        assertEquals("item", n("— item"))
    }

    // --- dropped symbols ---

    @Test
    fun bulletsDropped() {
        assertEquals("item", n("• item"))
        assertEquals("a b c d", n("▪ a ‣ b ◦ c · d"))
    }

    @Test
    fun asterisksUnderscoresBackticksDropped() {
        assertEquals("bold and under and code", n("*bold* and _under_ and `code`"))
    }

    @Test
    fun hashTildeCaretPipeAngleDropped() {
        assertEquals("tag code", n("#tag `code`"))
        assertEquals("xy", n("x|y"))
        assertEquals("a b c", n("a < b > c"))
    }

    @Test
    fun daggersDropped() {
        assertEquals("word and here", n("word† and ‡ here"))
    }

    // --- citations ---

    @Test
    fun numericCitationDropped() {
        assertEquals("fact.", n("fact [12]."))
    }

    @Test
    fun multiNumberCitationDropped() {
        assertEquals("fact more", n("fact [3, 4] more"))
    }

    @Test
    fun rangeCitationDropped() {
        assertEquals("fact here", n("fact [3–5] here"))
    }

    @Test
    fun citationNeededDropped() {
        assertEquals("claim.", n("claim [citation needed]."))
    }

    @Test
    fun nonCitationBracketsKeepContent() {
        assertEquals("note sic here", n("note [sic] here"))
    }

    // --- expansions ---

    @Test
    fun percentExpanded() {
        assertEquals("50 percent", n("50%"))
        assertEquals("50 percent", n("50 %"))
    }

    @Test
    fun ampersandExpanded() {
        assertEquals("Smith and Jones", n("Smith & Jones"))
    }

    @Test
    fun sectionExpanded() {
        assertEquals("see section 12", n("see § 12"))
    }

    @Test
    fun degreesExpanded() {
        assertEquals("20 degrees C", n("20°C"))
    }

    @Test
    fun plusBetweenDigitsExpanded() {
        assertEquals("2 plus 2", n("2+2"))
        assertEquals("2 plus 2", n("2 + 2"))
    }

    @Test
    fun plusNotBetweenDigitsOrSpacesDropped() {
        assertEquals("C", n("C++"))
    }

    @Test
    fun equalsExpanded() {
        assertEquals("x equals 5", n("x = 5"))
        assertEquals("x equals 5", n("x=5"))
    }

    @Test
    fun dollarAmountExpanded() {
        assertEquals("5 dollars", n("$5"))
        assertEquals("5 dollars", n("$ 5"))
        assertEquals("5.50 dollars", n("$5.50"))
        assertEquals("costs 1,000 dollars today", n("costs $1,000 today"))
    }

    @Test
    fun dollarAmountDoesNotEatTrailingComma() {
        assertEquals("costs 5 dollars, then left", n("costs $5, then left"))
    }

    @Test
    fun bareDollarExpanded() {
        assertEquals("the dollars sign", n("the $ sign"))
    }

    @Test
    fun euroAndPoundExpanded() {
        assertEquals("5 euros", n("€5"))
        assertEquals("10 pounds", n("£10"))
    }

    // --- slashes ---

    @Test
    fun slashBetweenShortLowercaseWordsBecomesOr() {
        assertEquals("and or or", n("and/or"))
        assertEquals("cat or dog", n("cat/dog"))
    }

    @Test
    fun slashOtherwiseBecomesCommaPause() {
        assertEquals("Read, Write", n("Read/Write"))
        assertEquals("12, 25", n("12/25"))
        assertEquals(
            "internationalization, localization",
            n("internationalization/localization"),
        )
    }

    // --- whitelist ---

    @Test
    fun emojiDropped() {
        assertEquals("fun party", n("fun 🎉 party"))
    }

    @Test
    fun zeroWidthFormatCharsRemovedWithoutSpace() {
        assertEquals("ab", n("a\u200Bb"))
    }

    @Test
    fun mathSymbolsBecomeWordSeparators() {
        assertEquals("a b", n("a × b"))
        assertEquals("5 3", n("5×3"))
    }

    @Test
    fun accentedLettersSurvive() {
        assertEquals("café naïve Müller", n("café naïve Müller"))
    }

    @Test
    fun whitelistPunctuationSurvives() {
        assertEquals("a, b. c! d? e; f: g's \"h\" (i) j-k", n("a, b. c! d? e; f: g's \"h\" (i) j-k"))
    }

    // --- cleanup ---

    @Test
    fun spaceBeforePunctuationFixed() {
        assertEquals("word, word.", n("word , word ."))
    }

    @Test
    fun emptyParensRemoved() {
        assertEquals("note here", n("note (*) here"))
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals("", n(""))
        assertEquals("", n("   "))
    }

    @Test
    fun newlinesNeverSurvive() {
        val out = n("a\nb")
        assertEquals("a b", out)
    }

    @Test
    fun idempotentOnOwnOutput() {
        val messy = "He said “don’t — stop” • now… [12] at 50% & $5"
        val once = n(messy)
        assertEquals(once, n(once))
    }
}
