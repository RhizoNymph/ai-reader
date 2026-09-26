# Audiobook Export (core:playback `export/` + app UI)

"Download the audiobook": render the whole document to speech and hand the
user one M4A (AAC-LC mono) file at a destination they pick. Reuses the
playback chunk cache in both directions — chunks already synthesized for
listening are not re-rendered, and chunks rendered for an export make later
playback instant.

## Scope

- Pure WAV chunk decoding (`WavCodec.decode` / `decodeHeader`) mirroring the
  cache's canonical 16-bit mono PCM encoding.
- Pure linear PCM resampling (`PcmResampler`) and target-rate selection
  (`chooseTargetRate`) so mixed-rate chunks (engine rate vs. 24 kHz fallback
  silence) stitch into one fixed-rate track.
- `M4aEncoder`: thin MediaCodec + MediaMuxer AAC-LC/M4A streaming encoder.
- `ExportAudiobookWorker`: long-running foreground WorkManager job —
  synthesize missing chunks, stitch/encode to a temp file, copy to the
  user-chosen SAF document; phase + per-sentence progress; deletes the
  destination document on failure/cancel.
- Player screen UI: top-bar download action → SAF `CreateDocument` picker →
  progress/cancel row (`ExportUiState` in `PlayerViewModel`).

## Non-scope

- Baking the listening speed into the file: exports are always 1.0× (the
  cache invariant; listening speed is a player-side rate shift).
- Chapter markers, cover art, or metadata tags beyond the container defaults.
- MP3 or Opus output; WAV export (an order of magnitude larger for no fidelity
  gain over AAC at speech bitrates).
- Export of documents whose voice model is not installed (the player screen
  only offers the action once the session loaded successfully).
- A library-screen entry point; export lives on the player screen.

## Data / control flow

```
PlayerScreen (top bar)
   │ Download tap ──► SAF CreateDocument("audio/mp4", "<title>.m4a")
   │ uri result ──► takePersistableUriPermission ──► PlayerViewModel.startExport(uri)
   ▼
WorkManager unique work "export-audiobook-<docId>"  (ExistingWorkPolicy.KEEP)
   ▼
ExportAudiobookWorker (foreground, dataSync)
   │ 1 synthesizing: renderMissingChunks(...) — shared with FullBookRenderWorker;
   │                 progress (phase, completed, total) per sentence
   │ 2 encoding:     scan chunk headers (44 bytes each) ─► chooseTargetRate
   │                 per chunk: readBytes ─► WavCodec.decode ─► PcmResampler ─► M4aEncoder
   │                 ─► cacheDir/export-<docId>.m4a
   │ 3 saving:       contentResolver.openOutputStream(uri, "wt") ◄─ temp file copy;
   │                 temp deleted; on failure/cancel the SAF doc is deleted too
   ▼
PlayerViewModel.exportState ◄── getWorkInfosForUniqueWorkFlow
   │ Idle / Queued / InProgress(phase, completed, total) / Saved / Failed
   ▼
ExportStatusLine (progress + cancel) · ExportAction (button disabled while busy)
```

## Files

| file | role / key exports |
|---|---|
| `core/playback/.../audio/WavCodec.kt` | + `decode(bytes)` / `decodeHeader(bytes)` → `PcmAudio` / `WavFormat`, typed `WavError` (Malformed/Unsupported). |
| `core/playback/.../export/PcmResampler.kt` | Pure: `resample(ShortArray, fromRate, toRate)` — linear interpolation, identity on equal rates, end clamped. |
| `core/playback/.../export/TargetRate.kt` | Pure: `chooseTargetRate(rates)` — most common rate, ties to the higher. |
| `core/playback/.../export/M4aEncoder.kt` | Thin: streaming AAC-LC/M4A encode, `writePcm`/`finish`/`release`; sync MediaCodec loop, muxer starts on first output format. |
| `core/playback/.../synth/ChunkSynthesis.kt` | + `renderMissingChunks(sentences, engine, voice, store, config, onProgress)` — the shared whole-book walk. |
| `core/playback/.../service/ExportAudiobookWorker.kt` | The worker: input keys, `uniqueWorkName(docId)`, `inputData(...)`, progress `PROGRESS_PHASE/COMPLETED/TOTAL`, phases `synthesizing/encoding/saving`, failure `KEY_ERROR`; foreground notification with cancel action. |
| `core/playback/src/main/AndroidManifest.xml` | + `FOREGROUND_SERVICE_DATA_SYNC`; WorkManager's `SystemForegroundService` merged with `dataSync` type. |
| `app/.../ui/player/PlayerViewModel.kt` | `ExportUiState`/`ExportPhase`, `exportState` (WorkInfo flow → UI state), `startExport(uri)`, `cancelExport()`. |
| `app/.../ui/player/PlayerScreen.kt` | `ExportAction` (picker + persistable grant), `ExportStatusLine`, `exportFileName` sanitizer. |
| tests | `audio/WavDecodeTest`, `export/PcmResamplerTest`, `export/TargetRateTest`, `synth/RenderMissingChunksTest` — all pure JVM. |

## Invariants and constraints

- **Exports are always speed-1.0 audio** — same invariant as the cache; the
  worker pins `VoiceSpec.speed` before doing anything.
- **The export never partially overwrites the destination.** The M4A is fully
  encoded into an app-private temp file first; the SAF document is only
  written in the final copy phase, and on any failure or cancellation the
  worker best-effort-deletes the (empty or partial) destination document.
- **One export per document at a time**: unique work name
  `export-audiobook-<docId>` with `ExistingWorkPolicy.KEEP`; the UI disables
  the button while work is queued/running.
- The output sample rate is the document's dominant chunk rate; minority-rate
  chunks (in practice: 24 kHz fallback silence in a 22.05 kHz Piper document)
  are linearly resampled, never the majority.
- The worker only reads complete chunk files (cache writes are atomic
  renames), and chunk indices are the contiguous-from-0 sentence indices, so
  `0 until sentences.size` enumerates exactly the book.
- Terminal export states (`Saved`/`Failed`) are only shown for exports started
  in the current screen session; running/queued states re-surface on re-entry.
- Foreground promotion is best-effort: if the foreground service start is
  restricted the job still runs (subject to WorkManager's ~10-minute
  non-foreground budget); the only launch path is a foreground activity, so
  the restriction does not occur in practice.
- MediaCodec/MediaMuxer failures surface as `IllegalStateException`/
  `IOException` caught narrowly in the worker and mapped to a typed failure
  (`KEY_ERROR`); the pure layers use `Outcome` (`WavError`, `CacheError`)
  throughout.
