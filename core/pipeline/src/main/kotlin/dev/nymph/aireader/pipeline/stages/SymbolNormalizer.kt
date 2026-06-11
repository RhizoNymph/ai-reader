package dev.nymph.aireader.pipeline.stages

/**
 * Stage 2: rewrites or removes every character the espeak-ng-phonemized TTS backend
 * would narrate by name ("asterisk", "em dash") or stumble over.
 *
 * After this stage the text contains only whitelisted characters: letters (any script,
 * including combining marks), digits, `.,!?;:'"()-` and spaces. Digits are left for the
 * engine, which speaks numbers natively.
 *
 * Pure function of its input; idempotent on its own output.
 */
object SymbolNormalizer {

    fun normalize(text: String): String =
        text
            .let(::straightenQuotes)
            .let(::normalizeEllipses)
            .let(::removeCitations)
            .let(::normalizeDashes)
            .let(::dropNoiseChars)
            .let(::expandCurrency)
            .let(::expandSymbols)
            .let(::normalizeSlashes)
            .let(::applyWhitelist)
            .let(::cleanup)

    // --- quotes ---

    private val singleQuoteLikes = "‘’‚‛ʼ′"
    private val doubleQuoteLikes = "“”„‟«»″"

    private fun straightenQuotes(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            in singleQuoteLikes -> append('\'')
            in doubleQuoteLikes -> append('"')
            else -> append(c)
        }
    }

    // --- ellipses ---

    // espeak-ng narrates "..." literally as "dot dot dot". An ellipsis becomes a
    // comma pause instead (a sentence-trailing comma is later promoted to "." by
    // ensureTerminalPunctuation); stray ".." typos collapse to a single period.
    // Dots separated by anything but whitespace ("J. R. R.", "3.14") never match.
    private val ellipsisLike = Regex("""(?:\.\s*){2,}\.|…""")
    private val doubledDot = Regex("""\.\.""")

    private fun normalizeEllipses(s: String): String =
        s.replace(ellipsisLike, ", ").replace(doubledDot, ".")

    // --- citations ---

    private val numericCitation = Regex("""\[\s*\d+(?:\s*[,;–—-]\s*\d+)*\s*\]""")
    private val citationNeeded = Regex("""(?i)\[\s*citation\s+needed\s*\]""")

    private fun removeCitations(s: String): String =
        s.replace(numericCitation, "").replace(citationNeeded, "")

    // --- dashes ---

    private val emDashLike = Regex("""\h*(?:[—―‒]|--+)\h*""")
    private val enDashBetweenDigits = Regex("""(\d)\h*–\h*(\d)""")
    private val enDashOther = Regex("""\h*–\h*""")

    private fun normalizeDashes(s: String): String =
        s.replace(emDashLike, ", ")
            .replace(enDashBetweenDigits, "$1 to $2")
            .replace(enDashOther, ", ")

    // --- unconditional drops ---

    private val noiseChars = Regex("""[•▪‣◦·*_`#~^|<>†‡]""")

    private fun dropNoiseChars(s: String): String = s.replace(noiseChars, "")

    // --- currency ---

    private val currencyAmount = Regex("""([${'$'}€£])\s*(\d+(?:,\d{3})*(?:\.\d+)?)""")

    private fun currencyWord(symbol: Char): String = when (symbol) {
        '$' -> "dollars"
        '€' -> "euros"
        else -> "pounds"
    }

    private fun expandCurrency(s: String): String =
        s.replace(currencyAmount) { m ->
            "${m.groupValues[2]} ${currencyWord(m.groupValues[1][0])}"
        }
            .replace("$", " dollars ")
            .replace("€", " euros ")
            .replace("£", " pounds ")

    // --- symbol expansions ---

    private val percent = Regex("""\h*%""")
    private val ampersand = Regex("""\h*&\h*""")
    private val sectionSign = Regex("""\h*§\h*""")
    private val degreeSign = Regex("""\h*°\h*""")
    private val equalsSign = Regex("""\h*=\h*""")

    private fun expandSymbols(s: String): String =
        s.replace(percent, " percent")
            .replace(ampersand, " and ")
            .replace(sectionSign, " section ")
            .replace(degreeSign, " degrees ")
            .replace(equalsSign, " equals ")
            .let(::expandPlus)

    /** "+" reads as "plus" only between digits/spaces; elsewhere the whitelist drops it. */
    private fun expandPlus(s: String): String = buildString(s.length) {
        for ((i, c) in s.withIndex()) {
            if (c != '+') {
                append(c)
                continue
            }
            val prev = s.getOrNull(i - 1)
            val next = s.getOrNull(i + 1)
            val prevOk = prev == ' ' || prev?.isDigit() == true
            val nextOk = next == ' ' || next?.isDigit() == true
            if (prevOk && nextOk) append(" plus ") else append(c)
        }
    }

    // --- slashes ---

    private val wordSlashWord = Regex("""(\p{L}+)/(\p{L}+)""")
    private val remainingSlash = Regex("""\h*/\h*""")
    private const val MAX_OR_WORD_LENGTH = 12

    private fun normalizeSlashes(s: String): String =
        s.replace(wordSlashWord) { m ->
            val left = m.groupValues[1]
            val right = m.groupValues[2]
            val speakAsOr = left.length <= MAX_OR_WORD_LENGTH &&
                right.length <= MAX_OR_WORD_LENGTH &&
                left.all(Char::isLowerCase) &&
                right.all(Char::isLowerCase)
            if (speakAsOr) "$left or $right" else "$left, $right"
        }.replace(remainingSlash, ", ")

    // --- whitelist ---

    private const val ALLOWED_PUNCTUATION = ".,!?;:'\"()- "

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true

        else -> false
    }

    private fun isInvisible(c: Char): Boolean =
        c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt()

    /**
     * Keeps letters/digits/marks and allowed punctuation. Whitespace becomes a plain
     * space; invisible (control/format) characters vanish without leaving a gap; every
     * other symbol becomes a space so dropping it never glues two words together.
     */
    private fun applyWhitelist(s: String): String = buildString(s.length) {
        for (c in s) when {
            c.isLetterOrDigit() || isCombiningMark(c) -> append(c)
            c == ' ' || c in ALLOWED_PUNCTUATION -> append(c)
            c.isWhitespace() -> append(' ')
            isInvisible(c) -> Unit
            else -> append(' ')
        }
    }

    // --- final cleanup ---

    private val emptyParens = Regex("""\(\s*\)""")
    private val commaRun = Regex("""\s*,(?:\s*,)+""")
    private val commaBeforeStrongPunctuation = Regex(""",\s*([.!?;:])""")
    private val spaceBeforePunctuation = Regex("""[ \t]+([.,!?;:)])""")
    private val spaceAfterOpenParen = Regex("""\(\s+""")
    private val whitespaceRun = Regex("""\s+""")
    private val leadingJunk = Regex("""^[\s,;:]+""")

    private fun cleanup(s: String): String =
        s.replace(emptyParens, " ")
            .replace(commaRun, ", ")
            .replace(commaBeforeStrongPunctuation, "$1")
            .replace(spaceBeforePunctuation, "$1")
            .replace(spaceAfterOpenParen, "(")
            .replace(emptyParens, " ")
            .replace(whitespaceRun, " ")
            .replace(leadingJunk, "")
            .trim()
}
