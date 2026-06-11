# Feature: text-pipeline

`ExtractedDocument` → `SpeakableDocument`. The core differentiator: fixes the three TTS
failure modes that motivate the app — engines pausing on wrapped-line newlines, gluing
words across newlines ("word\nword" read as one token), and narrating punctuation names
("asterisk", "em dash"). The backend is espeak-ng-phonemized (Kokoro/Piper via
sherpa-onnx): digits are spoken fine natively; symbols like `* • § —` are not safe.

## Scope

- Line unwrapping and dehyphenation inside content blocks.
- Symbol normalization down to a strict character whitelist.
- Speakable expansion of abbreviations, URLs, and emails.
- Sentence segmentation with abbreviation/decimal/initial protection and a 280-char
  safety split.
- Assembly into `SpeakableSentence`s with display text, TTS text, and pause hints.

## Non-scope

- Content selection (headers/footers/sidebars are already gone; `core:extraction` owns
  that).
- Number verbalization (digits, decimals, years, ordinals are left to the espeak-ng
  phonemizer, which handles them natively).
- Language detection, SSML, prosody markup beyond punctuation and pause hints.
- Persistence and synthesis (owned by `core:data` / `core:tts`).

## Data flow

Entry point: `DefaultTextPipeline.process(ExtractedDocument): SpeakableDocument`.

Per `ContentBlock`, in order:

1. **LineUnwrapper** (`unwrapBlock(blockText): List<String>`)
   - CRLF/CR → LF.
   - Split into logical paragraphs on blank-line runs (defensive; extraction usually
     pre-splits). Each logical paragraph flows through the rest independently and gets
     its own block-type pause.
   - Dehyphenate line-end hyphens: `inter-\nnational` → `international`; the hyphen is
     kept when the continuation starts with an uppercase letter (`anti-\nMarxist` →
     `anti-Marxist`) or a digit (`F-\n16` → `F-16`); a line ending in `--` is a dash,
     not hyphenation, and is joined with a space.
   - Join remaining intra-paragraph newlines with one space; collapse space/tab runs.

2. **SentenceSegmenter.segment** runs on the *unwrapped, pre-normalization* paragraph.
   This ordering (segmentation before normalization) is what makes
   `displayText`/`text` alignment exact: each display sentence maps 1:1 to the
   normalized sentence derived from it.
   - Boundary: `[.!?…]` + optional closing quote/paren + whitespace + (capital | digit
     | opening quote/paren).
   - Protected (no split): decimals (never candidates — no whitespace after the dot),
     single-uppercase-letter initials and dotted acronyms (`J. R. R.`, `U.S.`), known
     abbreviations (`Dr.`, `etc.`, `e.g.`, `i.e.`, ... — the protect list is a superset
     of the expansion list), ellipses unless followed by a capital letter, and
     digit-only enumeration markers opening the text (`1. Introduction`).
   - `segment` splits at real boundaries only; length capping happens later on the
     normalized TTS text (display sentences may exceed 280 chars — UI-only).

3. **Per display sentence**, the TTS text is produced by:
   - **SpeakableExpander.expand**: URLs → registrable host with dots kept
     (`https://www.example.com/a/b?q=1` → `example.com`; leading `www.` stripped,
     other subdomains kept); emails → `local at domain`; abbreviations expanded
     case-sensitively at word boundaries (e.g.→for example, i.e.→that is, etc.→et
     cetera, vs.→versus, cf.→compare, approx.→approximately, Fig./fig.→Figure,
     Eq./eq.→Equation, No.+digit→Number, Dr./Mr./Mrs./Ms./Prof./Jr./Sr.→spoken titles,
     St.+Capitalized→Saint). Expansion runs *before* SymbolNormalizer so URL slashes
     and email `@` are rewritten, not mangled.
   - **SymbolNormalizer.normalize**: curly quotes → straight; ellipses (`…`, 3+ dots,
     spaced `. . .`) → `", "` — espeak-ng narrates `...` as "dot dot dot", so they must
     never reach the engine; stray `..` typos collapse to `.`;
     em dash and `--` runs → `", "` (adjacent commas deduped); en dash between digits →
     `" to "`, otherwise `", "`; bullets, daggers, and ``* _ ` # ~ ^ | < >`` dropped;
     numeric/`[citation needed]` bracket citations dropped; `% & § ° = +`(digit/space
     context) and `$ € £` (digit-aware) expanded to words; `/` between short lowercase
     words → `" or "`, else `", "`; then a whitelist pass — letters (any script,
     incl. combining marks), digits, `.,!?;:'"()-`, space. Invisible (control/format)
     characters vanish; any other symbol becomes a space so removal never glues words.
     Final cleanup collapses spaces, dedupes commas, fixes space-before-punctuation,
     and strips leading orphan punctuation.
   - Sentences whose normalized form has no letter or digit are dropped.
   - Normalized text past 280 chars is safety-split: last `; : , — –` before the
     limit, recursively; clause-free text splits at the last word boundary; a single
     giant token is hard-cut. All pieces share the same `displayText`.
   - **Piece punctuation**: only the FINAL piece of a split sentence gets
     `ensureTerminalPunctuation` (appends `.` when not ending `.!?`; trailing clause
     punctuation replaced, never stacked). Non-final pieces get
     `ensureContinuationPunctuation` (keep `,;:`, else append `,`) so a mid-sentence
     cut keeps mid-sentence prosody instead of a sentence-final fall.

4. **Assembly** (`DefaultTextPipeline`): `SpeakableSentence(index, text, displayText,
   blockIndex, pauseAfterMs)`. `displayText` is the post-unwrap, pre-normalization
   sentence (original punctuation, casing, citations, URLs intact). Pauses: the last
   sentence of each logical paragraph gets the block pause — Heading 900, Paragraph
   600, ListItem 400, Quote 600 — all earlier sentences get 0. Indices are contiguous
   from 0 (`SpeakableDocument.init` enforces this).

## Files

| File | Role |
| --- | --- |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/TextPipeline.kt` | Module contract: `TextPipeline.process(ExtractedDocument): SpeakableDocument`. |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/DefaultTextPipeline.kt` | Stage orchestration, sentence assembly, pause hints. Exports `DefaultTextPipeline`. |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/stages/LineUnwrapper.kt` | Stage 1. Exports `LineUnwrapper.unwrapBlock(String): List<String>`. |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/stages/SymbolNormalizer.kt` | Stage 2. Exports `SymbolNormalizer.normalize(String): String` (pure, idempotent). |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/stages/SpeakableExpander.kt` | Stage 3. Exports `SpeakableExpander.expand(String): String` (pure, idempotent). |
| `core/pipeline/src/main/kotlin/dev/nymph/aireader/pipeline/stages/SentenceSegmenter.kt` | Stage 4. Exports `SentenceSegmenter.segment`, `.safetySplit`, `.ensureTerminalPunctuation`, `MAX_SENTENCE_CHARS`. |
| `core/pipeline/src/test/kotlin/.../stages/*Test.kt` | One unit-test class per stage. |
| `core/pipeline/src/test/kotlin/.../DefaultTextPipelineTest.kt` | End-to-end: realistic PDF-ish document, exact sentence list, pauses, displayText vs text divergence, idempotence. |

## Invariants

- `SpeakableSentence.text` contains only whitelist characters: letters, digits,
  `.,!?;:'"()-`, and single spaces. No newlines, tabs, symbols, or emoji — ever.
- Every `text` ends with `.`, `!`, or `?` (optionally followed by a closing
  quote/paren) — EXCEPT non-final pieces of a safety-split sentence, which end with
  `,`, `;`, or `:` to keep mid-sentence prosody across the cut.
- `text` never contains `...` (espeak-ng would say "dot dot dot") and never has two
  adjacent periods.
- `text` is never empty and always contains at least one letter or digit.
- `text.length <= 281` (280 from the safety split, +1 for appended punctuation).
- Re-running stages 2–3 (`SymbolNormalizer.normalize(SpeakableExpander.expand(text))`)
  on any output `text` is a no-op (idempotence).
- Sentence indices are contiguous from 0; `blockIndex` is monotonically non-decreasing
  and refers to the original `ExtractedDocument.blocks` position (blocks that produce
  no sentences leave gaps in `blockIndex`, never in `index`).
- All stage functions are pure: no I/O, no shared mutable state, no exceptions on any
  string input.

## Judgment calls (documented deviations / interpretations)

- Segmentation runs on display text *before* normalization (stage order 1→4→3→2 per
  sentence) so display/TTS sentences align exactly; the segmenter therefore protects
  the expandable abbreviations too. Net behavior equals the spec's 1→2→3→4 order.
- SpeakableExpander runs before SymbolNormalizer so URLs/emails survive symbol
  rewriting. Both stages are idempotent, so the "stages 2–3" idempotence property is
  tested in this composition order.
- Unspaced `--` between words is also treated as a dash (`word--word` → `word, word`).
- "Registrable host" is implemented as host minus a leading `www.` (no public-suffix
  list); deeper subdomains are kept and spoken.
- Unknown non-whitelist symbols become a space (prevents `5×3` → `53` gluing); explicit
  drop-list symbols (``* _ ` # ~ ^ | < > • † ‡`` and bullets) and invisible
  control/format characters are removed outright.
- A heading's appended `.` goes only to `text`; `displayText` stays exactly as written.
- Multi-paragraph blocks (blank-line splits) give *each* logical paragraph the block
  pause, since each becomes its own paragraph downstream.
