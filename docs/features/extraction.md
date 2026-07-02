# Feature: extraction

Source (`DocumentSource`) → `ExtractedDocument` containing **main-content
blocks only**. Headers, footers, page numbers, sidebars, footnotes, and web
navigation/boilerplate are removed here, before the text pipeline ever sees
the text. This is a core product differentiator.

## Scope

- Web pages: fetch, Readability main-content extraction, HTML → `ContentBlock`s.
- PDFs: positioned text collection, pure-Kotlin layout analysis (furniture
  removal, column detection and reading order, marginalia and footnote
  removal, heading detection, paragraph assembly).
- Typed errors: every failure is an `Outcome.Err(ImportError.…)`; no
  exceptions cross the module boundary.

## Non-scope

- Line unwrapping, dehyphenation, symbol normalization, sentence
  segmentation: owned by core:pipeline. Extraction deliberately joins
  paragraph lines with `"\n"` and preserves raw wraps.
- OCR for scanned PDFs (no extractable text → `ImportError.NoMainContent`).
- Footnote narration (v1 drops footnotes entirely).
- Tables, code listings, figures (unspeakable; skipped).
- EPUB or other formats.

## Data / control flow

### Web (`web/WebPageExtractor`)

1. okhttp GET on `Dispatchers.IO`: browser-like User-Agent, 20 s
   connect/read/write/call timeouts, redirects followed.
   - `IllegalArgumentException` (bad URL) / `IOException` → `ImportError.Network`.
   - Non-2xx status → `ImportError.Network`.
   - Content type present but not `text/html` / `application/xhtml+xml` →
     `ImportError.Unreadable`.
2. `Readability4J(url, html).parse()`; `contentWithUtf8Encoding` null/blank →
   `ImportError.NoMainContent`.
3. `web/HtmlToBlocks.convert(articleHtml)` (pure function, jsoup):
   - `h1`–`h6` → `Heading(level)`; `p` → `Paragraph`; `li` → `ListItem`
     (nested lists produce their own items, parent text excludes them);
     `blockquote` → one `Quote`; `pre`/`figure`/`table` (+ script/style/
     noscript/iframe) → skipped; container tags flattened in document order;
     unknown leaf elements with text → `Paragraph`; blank blocks dropped.
4. Title: readability title, falling back to the original page `<title>`.
   Empty block list → `ImportError.NoMainContent`.

### PDF

Two layers, split so all layout intelligence is JVM-testable:

1. **`pdf/PdfTextCollector`** (Android-touching, thin): subclasses PdfBox
   `PDFTextStripper` with `sortByPosition`, overrides
   `writeString(String, List<TextPosition>)` to emit `pdf/TextFragment`
   (text + edges + per-glyph x-starts, from
   `getXDirAdj`/`getWidthDirAdj`/`getYDirAdj`), then hands each page to the pure
   `pdf/LineGrouper`. The app must call `PDFBoxResourceLoader.init(context)` once
   before use.
2. **`pdf/LineGrouper`** (pure Kotlin, JVM-tested): assembles fragments into
   `pdf/PdfLine`s. Groups fragments into rows by y-proximity (tolerance
   `max(2pt, 0.3 × fontSize)`), then **splits each row at column boundaries** so
   a side rail (e.g. a table-of-contents rail) sharing a baseline with body text
   is never glued into one line: a row splits at a wide horizontal gutter, where
   consecutive fragments straddle the body column's left edge, and — using the
   per-glyph x-starts — inside a single atomic run a PDF engine emitted spanning
   the rail-to-body boundary with no gap. Emits `PdfLine(text, x, y, width,
   height, fontSizePt, pageIndex, pageWidth, pageHeight)` (top-left origin, y
   grows downward).
3. **`pdf/layout/LayoutAnalyzer.analyze(pages: List<List<PdfLine>>):
   List<ContentBlock>`** (pure Kotlin), pipeline:
   1. `RepeatedFurnitureDetector.removeFurniture` — headers/footers/folios.
   2. Body font size = modal font size of remaining text, weighted by
      character count, bucketed to 0.5 pt.
   3. `ColumnDetector.detect` per page → `ColumnUnit`s in reading order
      (page → column left-then-right → y); the main column is the one with the
      most text (not the most lines, so a fragmented side rail cannot win), and
      narrow side-rail lines are dropped as marginalia.
   4. `FootnoteDetector.remove` per unit — trailing small-font runs.
   5. `ParagraphAssembler.assemble` — headings + paragraphs; paragraph lines
      joined with `"\n"`; cross-page/column continuation merge; non-repeating
      running-head removal (heals a sentence split across a page break when a
      short top-of-page title bleeds between the two halves).

`pdf/PdfExtractor` wires these; its constructor takes an
`(String) -> Outcome<InputStream, ImportError>` opener lambda so the app layer
resolves SAF content URIs and the extractor stays testable.
`PDDocument.load`: `InvalidPasswordException` → `ImportError.Encrypted`,
`IOException` → `ImportError.Unreadable`; zero pages or no extractable text →
`ImportError.NoMainContent`. Title from PDF document information when present.

## Heuristic thresholds (all named constants in their detectors)

| Heuristic | Threshold |
|---|---|
| Furniture y-bands | top 15% / bottom 15% of page height (repetition-based removal only) |
| Furniture repetition | normalized text (digits→`#`, collapsed spaces, lowercase) at similar y (cluster window 2% page height) on ≥ 60% of pages, min 3; **also** ≥ 60% (min 3) of even or odd pages, so verso/recto alternating headers are caught |
| Page-number lines | pure digits, strict roman numerals, `Page N`, `Page N of M`, `N of M`, optionally wrapped in dashes/dots; dropped anywhere, regardless of repetition |
| Line-grouping column split | within a same-baseline row, split at a horizontal gap > 1.5 × the smaller font size, where fragments straddle the body column's left edge (dominant x-start bucket by text mass), or inside one atomic run at the first glyph reaching that edge when the run starts ≥ 30pt out in the rail |
| Column x-clustering | greedy 1-D clustering of line x-starts, tolerance 3% page width (absorbs first-line indents) |
| Two columns | two clusters each ≥ 25% of page lines, x-starts ≥ 25% page width apart, left column's text not reaching into the right (2% tolerance), narrower column ≥ 40% the width of the wider (anti-sidebar guard) |
| Marginalia | x-range overlaps no main column **and** width < 40% of nearest column width → dropped; wide outside lines are assigned to the nearest column instead |
| Footnotes | trailing contiguous run with fontSize ≤ 0.75 × body font, run starting in bottom 25% of page with a digit/`*†‡§¶#` marker → dropped |
| Headings | fontSize ≥ 1.15 × body, < 90 chars, not ending in `.`; level 1 ≥ 1.6×, level 2 ≥ 1.35×, else 3 |
| Paragraph split | vertical gap > 1.8 × modal line spacing (mode of body-line gaps, 0.5 pt buckets; fallback 1.2 × body font); or first-line indent (> column start + 1% page width) after terminal punctuation; or terminal punctuation + uppercase start + previous line < 70% column width |
| Cross-unit merge | previous unit's last paragraph lacks terminal punctuation and next unit's first line starts lowercase |
| Running-head heal | previous unit's paragraph is unfinished, the next unit's first line is a short (≤ 70 char) non-terminal line in the top 15% band, and the *following* non-heading line starts lowercase → the top line is dropped as a running head and the sentence merges across it |
| Terminal punctuation | `. ! ? …`, with trailing closing quotes/brackets (`"'”’»)]`) ignored |

## Files

| File | Role |
|---|---|
| `core/extraction/src/main/kotlin/dev/nymph/aireader/extraction/Extractor.kt` | Module contract: `Extractor<S>.extract(source): Outcome<ExtractedDocument, ImportError>` |
| `…/extraction/web/WebPageExtractor.kt` | `Extractor<DocumentSource.WebPage>`: okhttp + Readability4J + HtmlToBlocks |
| `…/extraction/web/HtmlToBlocks.kt` | Pure `convert(html: String): List<ContentBlock>` |
| `…/extraction/pdf/PdfLine.kt` | Positioned-line boundary type between collector and layout analysis |
| `…/extraction/pdf/LineGrouper.kt` | Pure `TextFragment` → `List<PdfLine>`: y-row grouping + column-boundary splitting (gutter / body-edge / atomic-run glyph split) |
| `…/extraction/pdf/PdfTextCollector.kt` | PdfBox `PDFTextStripper` subclass → `TextFragment`s → `LineGrouper` → `List<List<PdfLine>>` |
| `…/extraction/pdf/PdfExtractor.kt` | `Extractor<DocumentSource.Pdf>`: opener lambda + collector + analyzer |
| `…/extraction/pdf/layout/LayoutAnalyzer.kt` | Orchestrates the layout pipeline; computes body font size |
| `…/extraction/pdf/layout/RepeatedFurnitureDetector.kt` | Header/footer repetition + page-number patterns |
| `…/extraction/pdf/layout/ColumnDetector.kt` | `ColumnUnit`; 1 vs 2 column detection, marginalia removal, reading order |
| `…/extraction/pdf/layout/FootnoteDetector.kt` | Trailing small-font footnote run removal |
| `…/extraction/pdf/layout/HeadingDetector.kt` | Font-size-tier heading classification |
| `…/extraction/pdf/layout/ParagraphAssembler.kt` | Lines → `Heading`/`Paragraph` blocks; split rules; cross-unit merge |

Tests (`core/extraction/src/test/kotlin/…`): `web/HtmlToBlocksTest`, and in
`pdf/layout/`: `PageFixtures` (synthetic page builder), plus one test class
per detector and `LayoutAnalyzerTest` covering: running header + centered
folio, alternating verso/recto headers, two-column reading order, sidebar
removal, footnote removal, heading levels, paragraph splits (gap / indent /
short-line), full-width counter-case, cross-page merge, roman-numeral folios.

## Invariants and constraints

- `pdf/layout/` and `web/HtmlToBlocks` are pure Kotlin: no Android, no
  PdfBox, no okhttp imports — unit-testable on the JVM with synthetic
  fixtures.
- Paragraph block text contains the original line wraps as `"\n"`; extraction
  never joins wrapped lines with spaces (the pipeline's unwrapping and
  dehyphenation depend on seeing the wraps).
- Block boundaries are semantic: a `Paragraph` never ends mid-sentence merely
  because a page or column ended (continuation merge), and never contains
  furniture text.
- `PdfLine` coordinates: top-left origin, y increases downward; all band
  thresholds assume this.
- All I/O failures are converted at the boundary to typed `ImportError`s;
  only specific exceptions (`IOException`, `InvalidPasswordException`,
  `IllegalArgumentException` for URLs) are caught.
- `PDFBoxResourceLoader.init(context)` must run once before `PdfExtractor`
  is used (app layer responsibility).

## Depends on

- `core:model` (`ContentBlock`, `ExtractedDocument`, `ImportError`,
  `Outcome`). Downstream consumer: `core:pipeline` (text-pipeline feature).
