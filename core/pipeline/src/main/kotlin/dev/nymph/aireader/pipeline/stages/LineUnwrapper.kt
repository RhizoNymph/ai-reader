package dev.nymph.aireader.pipeline.stages

/**
 * Stage 1: undoes PDF/extraction line wrapping inside a [dev.nymph.aireader.model.ContentBlock].
 *
 * - Normalizes CRLF/CR to LF.
 * - Splits the block into logical paragraphs on blank-line runs (defensive; extraction
 *   usually pre-splits). Order is preserved.
 * - Dehyphenates line-end hyphens ("inter-\nnational" -> "international"), keeping the
 *   hyphen when the continuation starts with an uppercase letter or a digit
 *   ("anti-\nMarxist" -> "anti-Marxist", "F-\n16" -> "F-16"). A line ending in "--" is a
 *   dash, never wrap hyphenation, and is joined with a space instead.
 * - Joins remaining intra-paragraph newlines with a single space and collapses
 *   horizontal whitespace runs.
 *
 * Pure: no state, same input always yields the same output.
 */
object LineUnwrapper {

    private val paragraphBreak = Regex("""\n\s*\n""")

    /** Letter, line-end hyphen, optional spaces, newline, then letter or digit. */
    private val hyphenWrap = Regex("""(\p{L})-[ \t]*\n[ \t]*([\p{L}\p{N}])""")

    private val newlineJoin = Regex("""[ \t]*\n[ \t]*""")

    private val horizontalWhitespaceRun = Regex("""[ \t]+""")

    /** Splits a block into unwrapped logical paragraphs; blank paragraphs are dropped. */
    fun unwrapBlock(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(paragraphBreak)
            .map(::unwrapParagraph)
            .filter { it.isNotEmpty() }

    private fun unwrapParagraph(paragraph: String): String =
        paragraph
            .replace(hyphenWrap) { match ->
                val before = match.groupValues[1]
                val after = match.groupValues[2]
                if (after[0].isLowerCase()) "$before$after" else "$before-$after"
            }
            .replace(newlineJoin, " ")
            .replace(horizontalWhitespaceRun, " ")
            .trim()
}
