package dev.nymph.aireader.pipeline.stages

/**
 * Stage 4: splits an unwrapped paragraph into synthesis-sized sentences.
 *
 * A boundary is `[.!?…]` (plus optional closing quote/paren) followed by whitespace and
 * a capital letter, digit, or opening quote/paren. Boundaries are suppressed for:
 * decimals (never candidates: no whitespace after the dot), single-letter initials and
 * dotted acronyms ("J. R. R.", "U.S."), known abbreviations ("Dr.", "etc.", "e.g."),
 * and ellipses not followed by a capital letter.
 *
 * Sentences longer than [MAX_SENTENCE_CHARS] are split at the last clause boundary
 * (`; : , — –`) before the limit, recursively; clause-free text splits at the last word
 * boundary, and a single giant token is hard-cut.
 *
 * Pure functions only; no state.
 */
object SentenceSegmenter {

    const val MAX_SENTENCE_CHARS = 280

    /** Splits [text] into trimmed sentence pieces, each at most [MAX_SENTENCE_CHARS]. */
    fun segment(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return splitAtBoundaries(trimmed).flatMap(::safetySplit)
    }

    /**
     * Guarantees the sentence ends with `.`, `!`, or `?` (an optional closing
     * quote/paren may follow it). Trailing clause punctuation is replaced, not stacked:
     * "clause," becomes "clause." rather than "clause,.".
     */
    fun ensureTerminalPunctuation(sentence: String): String {
        val s = sentence.trim()
        if (s.isEmpty()) return s
        val closers = s.takeLastWhile { it in CLOSING_MARKS }
        val core = s.dropLast(closers.length)
        if (core.isEmpty()) return s
        if (core.last() in TERMINAL_PUNCTUATION) return s
        val stripped = core.trimEnd { it in TRAILING_CLAUSE_PUNCTUATION }
        if (stripped.isEmpty()) return s
        return stripped + "." + closers
    }

    // --- boundary detection ---

    private const val TERMINAL_PUNCTUATION = ".!?"
    private const val CLOSING_MARKS = "\"')"
    private const val TRAILING_CLAUSE_PUNCTUATION = ",;:- "

    private val boundary =
        Regex("""[.!?…](["'”’)\]]*)(\s+)(?=["'“‘(\[]?[\p{Lu}\p{N}])""")

    private val plainAbbreviations = setOf(
        "etc", "vs", "cf", "approx", "Fig", "fig", "Eq", "eq", "No",
        "Dr", "Mr", "Mrs", "Ms", "Prof", "Jr", "Sr", "St",
        "Inc", "Ltd", "Co", "Corp", "Mt", "al", "ca", "ed", "eds", "vol", "Vol", "pp",
    )

    private val dottedAbbreviations = listOf("e.g.", "i.e.")

    private fun splitAtBoundaries(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        for (match in boundary.findAll(text)) {
            val punctIndex = match.range.first
            val afterWhitespace = match.range.last + 1
            if (isProtected(text, punctIndex, afterWhitespace)) continue
            val closers = match.groupValues[1]
            val piece = text.substring(start, punctIndex + 1) + closers
            pieces += piece.trim()
            start = afterWhitespace
        }
        if (start < text.length) pieces += text.substring(start).trim()
        return pieces.filter { it.isNotEmpty() }
    }

    private fun isProtected(text: String, punctIndex: Int, nextIndex: Int): Boolean {
        val punct = text[punctIndex]
        if (punct == '!' || punct == '?') return false

        val isEllipsis = punct == '…' ||
            (punctIndex >= 2 && text[punctIndex - 1] == '.' && text[punctIndex - 2] == '.')
        if (isEllipsis) {
            // Ellipses split only before a capital letter (never before a digit).
            var j = nextIndex
            while (j < text.length && text[j] in "\"'“‘([") j++
            return !(j < text.length && text[j].isUpperCase())
        }

        var wordStart = punctIndex
        while (wordStart > 0 && text[wordStart - 1].isLetterOrDigit()) wordStart--
        val word = text.substring(wordStart, punctIndex)

        // Single capital letters: initials ("J. R. R.") and acronym tails ("U.S.").
        if (word.length == 1 && word[0].isUpperCase()) return true
        if (word in plainAbbreviations) return true

        for (abbreviation in dottedAbbreviations) {
            val from = punctIndex + 1 - abbreviation.length
            if (from >= 0 && text.regionMatches(from, abbreviation, 0, abbreviation.length)) {
                val before = if (from == 0) null else text[from - 1]
                if (before == null || !before.isLetterOrDigit()) return true
            }
        }
        return false
    }

    // --- length safety net ---

    private const val CLAUSE_BOUNDARIES = ";:,—–"

    /** Recursively splits [sentence] so every piece fits in [MAX_SENTENCE_CHARS]. */
    fun safetySplit(sentence: String): List<String> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        if (s.length <= MAX_SENTENCE_CHARS) return listOf(s)

        var clauseCut = -1
        var wordCut = -1
        for (i in 0 until MAX_SENTENCE_CHARS) {
            when {
                s[i] in CLAUSE_BOUNDARIES -> clauseCut = i
                s[i].isWhitespace() -> wordCut = i
            }
        }
        return when {
            clauseCut > 0 ->
                listOf(s.substring(0, clauseCut + 1).trim()) + safetySplit(s.substring(clauseCut + 1))
            wordCut > 0 ->
                listOf(s.substring(0, wordCut).trim()) + safetySplit(s.substring(wordCut + 1))
            else ->
                listOf(s.substring(0, MAX_SENTENCE_CHARS)) + safetySplit(s.substring(MAX_SENTENCE_CHARS))
        }
    }
}
