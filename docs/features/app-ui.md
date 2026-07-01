# App UI (app)

Compose UI and manual dependency wiring that turns the core modules into a
working app: library + import flow, player bound to the background playback
session, voice model management, and settings.

## Scope

- Manual DI container (`AppContainer`) with app-scoped lazy singletons for every
  core subsystem, installed as the playback service/worker dependency source.
- Single-activity navigation-compose UI: library (start), player, settings,
  voice models.
- Import flow (SAF PDF picker, URL dialog, share intents) through a single
  coordinator with one user-facing error mapping.
- Material3 dynamic-color theme (Android 12+), light/dark.

Non-scope: all policy implemented by the core modules (extraction quality,
synthesis scheduling, persistence shape); full-book render scheduling UI (the
worker exists in core:playback but no screen enqueues it yet); a global chunk
cache LRU.

## Screen map

```
library (start) ──tap ready item──► player/{docId}
   │  ├─ FAB: Import PDF (SAF OpenDocument, persistable grant → name dialog) / Add web page (URL + optional name)
   │  ├─ banner when no voice model installed ──► models
   │  ├─ failed row: error + retry / delete
   │  ├─ per-item overflow / long-press menu: Rename (dialog) / Delete (confirm)
   │  ├─ top bar ──► settings, models
player ─── inline "model missing" prompt ──► models
settings ◄──────────────────────────────────┘
```

Share intents (`ACTION_SEND` text/plain URL or application/pdf stream) land in
`MainActivity` (`onCreate` + `onNewIntent`, launchMode singleTask), are parsed
by the pure `parseShareIntent` helper, and feed the same import flow in the
app scope — no screen needs to be visible.

## DI graph (AppContainer, all lazy app-scoped singletons)

```
OkHttpClient ─┬─► WebPageExtractor (20 s timeouts via newBuilder)
              └─► ModelDownloader ─► ModelRepository ◄─ ModelStore(filesDir/models)
AiReaderDatabase ─► LibraryRepository                      │
DataStore("settings") ─► SettingsRepository           TtsEngineFactory
DefaultTextPipeline ─┐                                     │
PdfExtractor(opener) ┼─► ImportCoordinator ─► LibraryRepository
WebPageExtractor ────┘
ChunkCache(filesDir/chunks) ─┬─► PlaybackController(mainScope, SynthesisConfig(lookahead))
TtsEngineFactory ────────────┘          │
                                        ▼
                          PlaybackDependencies (anonymous impl)
                                        │ assigned in AiReaderApp.onCreate
                                        ▼
                AiReaderPlaybackService.dependencies (service + FullBookRenderWorker)
```

- The PDF opener lambda resolves `content://` URIs via `contentResolver`
  (persisted read grants from SAF; best-effort persistable grant for shares)
  and maps `FileNotFoundException` / `SecurityException` /
  `IllegalArgumentException` to `ImportError.Unreadable`.
- `PDFBoxResourceLoader.init` runs once in `AiReaderApp.onCreate`.
- Chunk cache root is `filesDir/chunks` (not `cacheDir`) so the OS can never
  purge synthesized audio mid-listen; deletion is app policy (per-doc delete,
  stale-voice eviction on load).
- `ModelInstallCoordinator` owns download jobs in the app scope (they survive
  leaving the Models screen) and exposes `activeInstalls` so a cancelled
  download (repo state stuck at `Downloading`) renders as "Paused — Resume".
- `activePlayback: StateFlow<ActivePlayback?>` records what the app-scoped
  `PlaybackController` has loaded, letting the player screen re-attach to a
  live background session instead of reloading.

## Import flow (create-then-fill)

`ImportCoordinator.import(source, customTitle? = null)`:
1. Insert placeholder row (0 sentences), flip to `IMPORTING` — the library shows
   the row immediately. The placeholder title is the non-blank `customTitle` if
   given, else `placeholderTitleFor(source)`.
2. Extract (`PdfExtractor` / `WebPageExtractor`) → `TextPipeline.process` (on
   `Dispatchers.Default`).
3. Success: insert the real document (status `READY`) — a non-blank `customTitle`
   overrides the extraction-derived title — then delete the placeholder (this
   order avoids an empty-library flash). Failure: placeholder flips to `FAILED`;
   `userMessageFor(ImportError)` is the single error-to-string mapping, persisted
   to the row and mirrored in the in-memory `failureMessages` map (core:data
   exposes no read-back of `errorMessage`).
Retry = delete failed row (+ chunk dir) and import the same source again.

Naming and renaming: the PDF picker defers the import behind a name dialog (blank
= use the PDF's own title); the web-page dialog has an optional name field.
Renaming an existing document goes through `LibraryRepository.rename(docId,
title)` (trims, ignores blank) via the per-item overflow/long-press menu.

## Player binding

- Load on enter at the persisted `currentSentenceIndex`; if
  `activePlayback.docId` already matches, re-attach without reloading (no seek,
  no cache eviction). Nothing is torn down on exit — playback continues under
  the Media3 service.
- The playback service is started (plain `startService`; Media3 promotes itself
  to foreground once playing) on every `play()`; the screen requests
  POST_NOTIFICATIONS lazily on Android 13+ before the first play (playback
  proceeds if denied — only the notification is gated).
- Position persistence: `currentSentenceIndex` debounced 1 s →
  `LibraryRepository.updatePosition`; final flush in `onCleared`.
- Speed (0.75/1/1.25/1.5/2) → `controller.setSpeed` + per-doc persist via
  `updateVoice` (cache is speed-invariant). Voice change → `updateVoice` +
  `controller.load` with the new voiceKey at the current sentence.
- Synthesis status from `SynthState`: "Synthesizing ahead… N ready"
  (N = cachedThrough − playhead + 1), buffering bar while
  `cachedThrough < playhead`, warning icon when `lastError` is set.
- Missing engine model → inline prompt to the Models screen (checked before
  load and again via `TtsError.ModelNotInstalled`).

## Files (under `app/src/`)

| file | role |
|---|---|
| `main/.../AiReaderApp.kt` | PDFBox init, builds `AppContainer`, assigns `AiReaderPlaybackService.dependencies`, starts settings collector. |
| `main/.../MainActivity.kt` | Single activity; nav host; share-intent intake on `onCreate`/`onNewIntent`; persistable-grant attempt for shared PDFs. |
| `main/.../di/AppContainer.kt` | All singletons, `PlaybackDependencies` impl, `ActivePlayback` holder, PDF stream opener, `startPlaybackService`. |
| `main/.../di/ModelInstallCoordinator.kt` | App-scoped download jobs: `install`/`cancel`, `activeInstalls`. |
| `main/.../di/ViewModelFactories.kt` | Manual `ViewModelProvider.Factory` per screen (narrow constructor deps, no container injection). |
| `main/.../importing/ShareIntents.kt` | Pure: `parseShareIntent`, `normalizeWebUrl`, `placeholderTitleFor` (JVM-tested). |
| `main/.../importing/ImportCoordinator.kt` | Create-then-fill import; `ImportResult`; `userMessageFor` (sole ImportError→string mapping); `failureMessages`. |
| `main/.../ui/AppNavHost.kt` | `Routes` + `NavHost` wiring screens to factories. |
| `main/.../ui/theme/Theme.kt` | Material3 dynamic dark/light theme. |
| `main/.../ui/library/LibraryViewModel.kt` | Library list, model-installed banner flag, import (with optional title)/retry/delete/rename actions in app scope. |
| `main/.../ui/library/LibraryScreen.kt` | List with source icons, progress %, status chips, FAB menu (SAF picker, URL dialog), per-item rename/delete menu, name dialogs, first-run banner. |
| `main/.../ui/player/PlayerViewModel.kt` | Session load/re-attach, transport, speed/voice (persist + reload), debounced position persistence, `PlayerUiState`. |
| `main/.../ui/player/PlayerScreen.kt` | Sentence list (highlight + auto-scroll, tap-to-seek), transport, synth status line, speed chips, voice picker, notification permission. |
| `main/.../ui/models/ModelsViewModel.kt` | Catalog rows: repo `ModelState` × active jobs → `ModelRowStatus` (incl. `Paused`). |
| `main/.../ui/models/ModelsScreen.kt` | Download/resume/cancel/uninstall, progress %, sizes, Wi-Fi hint. |
| `main/.../ui/settings/SettingsViewModel.kt` | Settings flow + setters; installed-engine gating; lookahead clamp 4..32. |
| `main/.../ui/settings/SettingsScreen.kt` | Engine/voice/speed defaults, lookahead slider, About (sherpa-onnx/Kokoro/Piper attribution). |
| `test/.../importing/ShareIntentsTest.kt` | JVM tests for the pure share/URL/title helpers. |

## Invariants and constraints

- Only `userMessageFor` turns `ImportError` into user-visible text; no other
  error-to-string mapping exists in the app layer.
- Imports, deletions, and final position writes run in the container's
  `appScope`, never `viewModelScope`, so navigation cannot abort them.
- `AiReaderPlaybackService.dependencies` is assigned in `Application.onCreate`
  before anything can start the service or the worker.
- The app never constructs a `TtsEngine` directly; everything goes through
  `TtsEngineFactory` via the container's `engineFor`.
- The playback service is never redeclared in the app manifest (merged from
  core:playback); WorkManager uses its default initializer.
- Synthesis lookahead is applied when the `PlaybackController` singleton is
  first constructed; changing the setting takes effect on the next app start
  (noted in the Settings UI).
- No bare try/catch: only specific exception types are caught (stream opening,
  persistable grant, URL decoding), each mapped to a typed state.
