# AI Reader

Android app that turns PDFs and web pages into audiobooks with on-device neural
TTS (Kokoro / Piper via sherpa-onnx). Built around two ideas existing readers
get wrong:

1. **Preprocess like you mean it** — no pauses on wrapped lines, no words glued
   across newlines, no "asterisk" read aloud. Punctuation is preserved
   deliberately so the model's prosody responds to it.
2. **Read the content, not the page** — PDF layout analysis drops headers,
   footers, page numbers, and sidebars; web import uses Readability.

See `docs/OVERVIEW.md` for architecture.
