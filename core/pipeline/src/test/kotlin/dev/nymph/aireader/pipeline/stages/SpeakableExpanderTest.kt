package dev.nymph.aireader.pipeline.stages

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakableExpanderTest {

    private fun e(s: String): String = SpeakableExpander.expand(s)

    // --- abbreviations ---

    @Test
    fun expandsEg() {
        assertEquals("fruits, for example apples", e("fruits, e.g. apples"))
    }

    @Test
    fun expandsIe() {
        assertEquals("that is this", e("i.e. this"))
    }

    @Test
    fun expandsEtc() {
        assertEquals("apples, et cetera were eaten", e("apples, etc. were eaten"))
    }

    @Test
    fun expandsVs() {
        assertEquals("cats versus dogs", e("cats vs. dogs"))
    }

    @Test
    fun expandsCf() {
        assertEquals("compare Smith 2020", e("cf. Smith 2020"))
    }

    @Test
    fun expandsApprox() {
        assertEquals("approximately 50 units", e("approx. 50 units"))
    }

    @Test
    fun expandsFigBothCases() {
        assertEquals("Figure 3 and Figure 4", e("Fig. 3 and fig. 4"))
    }

    @Test
    fun expandsEqBothCases() {
        assertEquals("Equation 2 and Equation 5", e("Eq. 2 and eq. 5"))
    }

    @Test
    fun expandsNoOnlyBeforeDigit() {
        assertEquals("Number 7 wins", e("No. 7 wins"))
        assertEquals("No. That is final.", e("No. That is final."))
    }

    @Test
    fun expandsTitles() {
        assertEquals("Doctor Smith met Mister Jones", e("Dr. Smith met Mr. Jones"))
        assertEquals("Missus Lee and Miss Day", e("Mrs. Lee and Ms. Day"))
        assertEquals("Professor Hall spoke", e("Prof. Hall spoke"))
        assertEquals("King Junior spoke; Senior listened", e("King Jr. spoke; Sr. listened"))
    }

    @Test
    fun expandsStOnlyBeforeCapitalizedWord() {
        assertEquals("Saint Petersburg is cold", e("St. Petersburg is cold"))
        assertEquals("Main St. is busy", e("Main St. is busy"))
    }

    @Test
    fun abbreviationsAreCaseSensitive() {
        assertEquals("dr. smith", e("dr. smith"))
        assertEquals("VS. battle", e("VS. battle"))
    }

    @Test
    fun abbreviationsRespectWordBoundaries() {
        assertEquals("veg. matters", e("veg. matters"))
        assertEquals("almonds. etcetera", e("almonds. etcetera"))
    }

    // --- URLs ---

    @Test
    fun urlReducedToHost() {
        assertEquals(
            "see example.com now",
            e("see https://www.example.com/a/b?q=1 now"),
        )
    }

    @Test
    fun urlTrailingPunctuationPreserved() {
        assertEquals("go to foo.org.", e("go to https://foo.org/page."))
    }

    @Test
    fun wwwUrlWithoutSchemeReduced() {
        assertEquals("visit example.com today", e("visit www.example.com today"))
    }

    @Test
    fun subdomainKept() {
        assertEquals("read docs.foo.io", e("read https://docs.foo.io/x/y"))
    }

    @Test
    fun httpSchemeAlsoHandled() {
        assertEquals("old example.org site", e("old http://example.org/index.html site"))
    }

    // --- emails ---

    @Test
    fun emailBecomesLocalAtDomain() {
        assertEquals("mail bob at example.com now", e("mail bob@example.com now"))
    }

    @Test
    fun emailWithDottedLocalPart() {
        assertEquals("ann.lee at uni.edu writes", e("ann.lee@uni.edu writes"))
    }

    // --- left alone ---

    @Test
    fun plainNumbersDecimalsYearsOrdinalsUntouched() {
        assertEquals(
            "In 1984, 3.14 and the 2nd item stay",
            e("In 1984, 3.14 and the 2nd item stay"),
        )
    }

    @Test
    fun plainTextUntouched() {
        assertEquals("nothing to expand here", e("nothing to expand here"))
    }

    @Test
    fun idempotentOnOwnOutput() {
        val once = e("see https://www.example.com/a vs. bob@x.org, e.g. Dr. Smith")
        assertEquals(once, e(once))
    }
}
