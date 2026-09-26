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

## Building

Requires the Android SDK (set `sdk.dir` in `local.properties`) and a JDK 21
installation. The Gradle daemon is pinned to JDK 21 in
`gradle/gradle-daemon-jvm.properties`, because Gradle 8.x cannot run on JDK 25.
The JDK 17 toolchain used to compile the pure-JVM modules is downloaded
automatically if it isn't installed.

```sh
./gradlew assembleDebug        # first build downloads the sherpa-onnx AAR (~56 MB)
./gradlew testDebugUnitTest
```

The sherpa-onnx AAR is not committed. Gradle resolves it from the sherpa-onnx
GitHub releases (see `settings.gradle.kts`) and checks it against the SHA-256
pinned in `gradle/verification-metadata.xml`. Voice models are
downloaded by the app at runtime.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
