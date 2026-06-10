package dev.nymph.aireader.pipeline.stages

/**
 * Stage 3: rewrites tokens that are written for the eye but read badly aloud.
 *
 * - URLs collapse to their host with dots kept ("https://www.example.com/a/b?q=1" ->
 *   "example.com"); a leading "www." is stripped, other subdomains are kept.
 * - Emails become "local at domain".
 * - Common abbreviations expand case-sensitively at word boundaries, removing their
 *   trailing dots so they can never be mistaken for sentence ends.
 * - Plain numbers, decimals, years, and ordinals are deliberately left alone; the
 *   espeak-ng phonemizer speaks them natively.
 *
 * Pure function of its input; idempotent on its own output.
 */
object SpeakableExpander {

    fun expand(text: String): String =
        expandAbbreviations(expandEmails(expandUrls(text)))

    // --- URLs ---

    private val urlToken = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private const val URL_TRAILING_PUNCTUATION = ".,!?;:)\"']"

    private fun expandUrls(s: String): String = s.replace(urlToken) { match ->
        val raw = match.value
        val trailing = raw.takeLastWhile { it in URL_TRAILING_PUNCTUATION }
        val core = raw.dropLast(trailing.length)
        extractHost(core) + trailing
    }

    private fun extractHost(url: String): String =
        url.replaceFirst(Regex("(?i)^https?://"), "")
            .takeWhile { it !in "/?#" }
            .substringAfterLast('@')
            .substringBefore(':')
            .removePrefix("www.")

    // --- emails ---

    private val emailToken =
        Regex("""\b([\p{L}\p{N}._%+\-]+)@([\p{L}\p{N}.\-]+\.\p{L}{2,})\b""")

    private fun expandEmails(s: String): String =
        s.replace(emailToken) { m -> "${m.groupValues[1]} at ${m.groupValues[2]}" }

    // --- abbreviations ---

    /** Order matters only for readability; patterns are mutually exclusive. */
    private val abbreviations: List<Pair<Regex, String>> = listOf(
        Regex("""\be\.g\.""") to "for example",
        Regex("""\bi\.e\.""") to "that is",
        Regex("""\betc\.""") to "et cetera",
        Regex("""\bvs\.""") to "versus",
        Regex("""\bcf\.""") to "compare",
        Regex("""\bapprox\.""") to "approximately",
        Regex("""\bFig\.""") to "Figure",
        Regex("""\bfig\.""") to "Figure",
        Regex("""\bEq\.""") to "Equation",
        Regex("""\beq\.""") to "Equation",
        Regex("""\bNo\.(?=\s*\d)""") to "Number",
        Regex("""\bMrs\.""") to "Missus",
        Regex("""\bMs\.""") to "Miss",
        Regex("""\bMr\.""") to "Mister",
        Regex("""\bDr\.""") to "Doctor",
        Regex("""\bProf\.""") to "Professor",
        Regex("""\bJr\.""") to "Junior",
        Regex("""\bSr\.""") to "Senior",
        Regex("""\bSt\.(?=\s+\p{Lu})""") to "Saint",
    )

    private fun expandAbbreviations(s: String): String =
        abbreviations.fold(s) { acc, (pattern, replacement) ->
            acc.replace(pattern, replacement)
        }
}
