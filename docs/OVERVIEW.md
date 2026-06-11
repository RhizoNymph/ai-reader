# AI Reader — Overview

```yaml
Overview:
  description: >
    Android app that imports PDFs and web pages and turns them into audiobooks
    using on-device neural TTS (sherpa-onnx running Kokoro-82M and Piper).
    Differentiators: aggressive text preprocessing so speech never pauses on
    line wraps, never glues words across newlines, and never narrates
    punctuation names; PDF layout analysis that strips headers, footers, page
    numbers, and sidebars so only main content is read; punctuation-driven
    prosody preserved deliberately so the model's intonation responds to it.
  subsystems:
    core:model: Pure-Kotlin shared domain types (ContentBlock, SpeakableSentence, VoiceSpec, typed errors via Outcome).
    core:extraction: Source → ExtractedDocument. Web via Readability4J; PDF via PdfBox-Android positioned text + pure-Kotlin layout analysis.
    core:pipeline: ExtractedDocument → SpeakableDocument. Dehyphenation, line unwrapping, symbol normalization, speakable expansion, sentence segmentation, pause hints.
    core:tts: TtsEngine contract + sherpa-onnx implementations (Kokoro, Piper) + voice model download manager.
    core:playback: Foreground media service; synthesizes sentences ahead of the playhead into a per-document audio chunk cache; Media3 playback; WorkManager full-book render.
    core:data: Room (documents, sentences, positions, voice prefs) + DataStore settings.
    app: Compose UI (library, import, player, settings) + manual DI wiring.
  data_flow: >
    Import (SAF picker / share-sheet URL) → core:extraction produces
    ExtractedDocument (main content only) → core:pipeline produces
    SpeakableDocument (normalized sentences + pause hints) → core:data persists
    document + sentences → playback service pulls sentences, core:tts
    synthesizes ahead of playhead → chunk cache on disk → Media3 plays chunks →
    UI observes position via Room/StateFlow and highlights the current sentence.

Features Index:
  text-pipeline:
    description: Normalization pipeline fixing newline/punctuation TTS failure modes; produces TTS-ready sentences.
    entry_points: [core/pipeline TextPipeline.process]
    depends_on: []
    doc: docs/features/text-pipeline.md
  extraction:
    description: Main-content extraction; web Readability and PDF layout analysis (header/footer/sidebar removal, reading order).
    entry_points: [core/extraction Extractor.extract]
    depends_on: [text-pipeline]
    doc: docs/features/extraction.md
  tts-engine:
    description: sherpa-onnx Kokoro/Piper engines and voice model download management.
    entry_points: [core/tts TtsEngine]
    depends_on: []
    doc: docs/features/tts-engine.md
  playback:
    description: Synthesis-ahead playback service, chunk cache, Media3 session, full-book render job.
    entry_points: [core/playback]
    depends_on: [tts-engine, text-pipeline]
    doc: docs/features/playback.md
  library:
    description: Document library persistence and app settings.
    entry_points: [core/data]
    depends_on: []
    doc: docs/features/library.md
  app-ui:
    description: Compose UI (library/player/models/settings), manual DI container, import flow, share-intent intake, playback session binding.
    entry_points: [app AiReaderApp, app MainActivity]
    depends_on: [extraction, text-pipeline, tts-engine, playback, library]
    doc: docs/features/app-ui.md
```

## Build

- Toolchain: JDK 17 (`~/.local/toolchains/jdk17`), Android SDK (`~/Android/Sdk`), Gradle wrapper.
- `./gradlew test` — JVM unit tests (pipeline + extraction layout analysis are pure Kotlin).
- `./gradlew :app:assembleDebug` — debug APK.

## Key decisions

- Play Store target ⇒ permissive licenses only (PdfBox-Android, not MuPDF); voice models downloaded on first run, never bundled in the APK.
- minSdk 26, compile/target 35.
- TTS runs at ~1× real-time on phones (Kokoro) ⇒ playback never waits on full synthesis; we synthesize ahead of the playhead and cache chunks.
- No DI framework; manual wiring in AiReaderApp.
