# Feature: tts-engine

sherpa-onnx on-device TTS: voice model catalog, resumable download + install
management, and `TtsEngine` implementations for Kokoro-82M and Piper.

## Scope

- Hardcoded catalog of supported voice models (one Kokoro, one Piper) with
  verified download URLs, archive layouts, and speaker-id tables.
- Resumable HTTP download of model archives, tar.bz2 extraction, install
  lifecycle (`NotInstalled → Downloading(pct) → Installed | Failed`).
- `SherpaTtsEngine`: sherpa-onnx v1.13.2 `OfflineTts` wrapper implementing
  `TtsEngine` (synthesize one sentence → `SynthesizedAudio`).
- `TtsEngineFactory`: only way to obtain an engine; enforces install state.

## Non-scope

- Text normalization (core:pipeline), playback/caching (core:playback),
  persistence of the user's voice choice (core:data), any UI.
- Checksum (SHA) verification of archives — only byte-count verification
  against `Content-Length`/`Content-Range` (release assets carry no digests).
- Streaming synthesis (`generateWithCallback`) — playback synthesizes whole
  sentences ahead of the playhead, so blocking `generate` is sufficient.

## Models (verified against the sherpa-onnx `tts-models` GitHub release)

| | Kokoro | Piper |
|---|---|---|
| modelId | `kokoro-int8-multi-lang-v1_0` | `vits-piper-en_US-libritts_r-medium` |
| URL | `…/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2` | `…/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2` |
| archive | ~126 MB | ~78 MB |
| sample rate | 24000 | 22050 |
| voices | 53 speakers; English subset exposed (af_heart=3, af_sky=10, am_adam=11, bf_emma=21, …) per the documented v1_0 sid table | 904 speakers; curated subset (sids 0,1,2,3,10,20) |
| key files | `model.int8.onnx`, `voices.bin`, `tokens.txt`, `espeak-ng-data/`, `lexicon-us-en.txt`, `lexicon-gb-en.txt`, `dict/` | `en_US-libritts_r-medium.onnx`, `tokens.txt`, `espeak-ng-data/` |

Note: `kokoro-int8-multi-lang-v1_1` was deliberately NOT used — it is the
Kokoro v1.1-zh checkpoint whose only English voices are af_maple/af_sol/bf_vale
(verified via sherpa-onnx PR #1942); v1_0 carries the full English voice set.

## Data / control flow

```
UI (settings)            playback service
   │ install(descriptor)     │ create(descriptor)
   ▼                         ▼
ModelRepository          TtsEngineFactory ── .complete missing ──► Err(ModelNotInstalled)
   │ per-engine StateFlow<ModelState>│
   ▼                         ▼
ModelDownloader          SherpaTtsEngine (OfflineTts, native)
   │ okhttp GET (Range resume)        │ synthesize(text, VoiceSpec)
   ▼                                  │   voiceId ──VoiceModelDescriptor──► sid
<root>/<id>.tar.bz2.part              │   generate(text, sid, speed) under Mutex
   │ TarBz2Extractor (path-traversal guarded, top dir stripped)
   ▼                                  ▼
<root>/<id>/… + .complete         SynthesizedAudio(FloatArray, sampleRate)
```

Install: `ModelRepository.install` takes the per-engine mutex, flips state to
`Downloading(0)`, delegates to `ModelInstaller.downloadAndInstall`. The
downloader resumes from an existing `.part` via `Range: bytes=<offset>-`
(200 → restart, 206 → verify `Content-Range` start matches the offset,
416 → delete `.part` and retry once from scratch), streams to disk emitting
`(bytesOnDisk, expectedTotal)` progress, verifies received byte count against
`Content-Length` (mismatch → `ChecksumMismatch`), extracts, deletes the
archive, writes `.complete`. Any `IOException`/extraction failure →
`TtsError.DownloadFailed`; state becomes `Failed(error)` and the `.part` file
survives network failures for later resume (but is deleted when the archive
itself is corrupt).

Synthesis: `TtsEngineFactory.create` → `SherpaTtsEngine` builds the native
`OfflineTtsConfig` from the pure `SherpaModelConfig` (built and unit-tested in
`SherpaConfigBuilder`): Kokoro → `OfflineTtsKokoroModelConfig(model, voices,
tokens, dataDir, lexicon, dictDir)`; Piper → `OfflineTtsVitsModelConfig(model,
tokens, dataDir)`. `synthesize` maps `VoiceSpec.voiceId → sid`, calls
`OfflineTts.generate(text, sid, speed)` under a `Mutex`, and wraps the result;
empty sample arrays → `TtsError.SynthesisFailed`.

## Files (all under `core/tts/src/{main,test}/kotlin/dev/nymph/aireader/tts/`)

| file | role / key exports |
|---|---|
| `../TtsEngine.kt` | (pre-existing) `TtsEngine` interface this feature implements |
| `models/VoiceModelCatalog.kt` | `VoiceInfo`, `ModelFilesOfInterest`, `VoiceModelDescriptor` (+`speakerIdFor`), `VoiceModelCatalog.{kokoro,piper,all,forEngine}` |
| `models/ModelStore.kt` | `ModelStore`: rootDir layout (`modelDir`, `partFile`, `completionMarker`, `isInstalled`, `markInstalled`, `uninstall`, `resolve`) |
| `models/TarBz2Extractor.kt` | `TarBz2Extractor.extract(archive, dest, stripTopLevelDir)`, `ExtractionError.{PathTraversal,Io}` |
| `models/ModelDownloader.kt` | `ModelInstaller` (interface), `ModelDownloader`: resumable download + verify + extract + finalize |
| `models/ModelRepository.kt` | `ModelState` sealed interface, `ModelRepository.{observe,install,uninstall}` |
| `engine/SherpaConfigBuilder.kt` | pure `SherpaModelConfig.{Kokoro,Vits}` + `SherpaConfigBuilder.build(descriptor, modelDir, numThreads)` |
| `engine/SherpaTtsEngine.kt` | `SherpaTtsEngine` (native `OfflineTts` wrapper) + private mapper to `OfflineTtsConfig` |
| `engine/TtsEngineFactory.kt` | `TtsEngineFactory.create(descriptor): Outcome<TtsEngine, TtsError>` |
| tests | `models/{VoiceModelCatalogTest, ModelStoreTest, TarBz2ExtractorTest, ModelDownloaderTest, ModelRepositoryTest}`, `engine/{SherpaConfigBuilderTest, TtsEngineFactoryTest}`; `TarBz2ExtractorTest` also exports `writeTarBz2`/`TestArchiveEntry` helpers used by the downloader tests (MockWebServer) |

## Invariants and constraints

- An engine is never instantiated unless the model's `.complete` marker exists;
  `TtsEngineFactory` is the only construction path and checks it.
- `.complete` is written only after full extraction succeeds; `isInstalled`
  ignores directories without the marker (half-extracted ⇒ not installed).
- Archive entries containing `..` or resolving outside the destination are
  rejected; symlink/hardlink entries are never materialized.
- sherpa release archives wrap content in a top-level dir; extraction strips
  the first path segment, so `filesOfInterest` paths are relative to the
  install dir itself.
- Native `OfflineTts` is not thread-safe: all `generate` calls are serialized
  behind a `Mutex`; `release()` must not race an in-flight `synthesize`.
- `SherpaTtsEngine` is Android-only (loads `libsherpa-onnx-jni`); everything
  else in the module is pure JVM and covered by unit tests.
- No exceptions cross the module boundary: all failures surface as
  `Outcome.Err(TtsError.…)`; `CancellationException` propagates untouched.
- Voice ids in `VoiceSpec` must match a `VoiceInfo.id` from the engine's
  catalog descriptor; unknown ids fail with `SynthesisFailed`, not a crash.
