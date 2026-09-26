# Playback (core:playback)

Synthesize-ahead audiobook playback: sentence chunks are rendered by the TTS
engine ahead of the playhead into an on-disk WAV cache, and Media3 plays the
cached files. Synthesis runs at roughly 1x real-time on phones, so playback
must never wait on full-document synthesis — only on the next chunk, and
usually not even that.

## Scope

- Pure WAV encoding of engine float PCM (`WavCodec`).
- On-disk chunk cache keyed by `(docId, voiceKey, sentenceIndex)` with atomic
  writes (`ChunkCache` / `ChunkStore`).
- `SynthesisScheduler`: single-worker synthesize-ahead loop that follows the
  playhead, with windowed priority, byte-budgeted opportunistic fill,
  silence fallback on per-sentence failure, and an observable `SynthState`.
- `PlaybackController`: thin ExoPlayer wrapper mapping sentence indices to a
  contiguous playlist of cached chunk files; speed via `PlaybackParameters`.
- `AiReaderPlaybackService`: Media3 `MediaSessionService` (foreground, default
  media notification), wired through the `PlaybackDependencies` locator.
- `FullBookRenderWorker`: WorkManager job rendering every uncached chunk of a
  document start-to-end with progress reporting (the walk itself is the shared
  `renderMissingChunks`).
- The audiobook export pipeline built on the same cache (WAV decode, PCM
  resampling, M4A encode, `ExportAudiobookWorker`) is documented separately in
  `docs/features/audiobook-export.md`.

## Non-scope

- Producing sentences (core:pipeline) or persisting them / playback positions
  (core:data — the controller exposes `currentSentenceIndex`, the app persists).
- Engine construction and model install state (core:tts `TtsEngineFactory`).
- UI, notification customization, Android Auto/Wear, audio ducking policy
  beyond Media3 defaults.
- Cache size management across documents (only per-document `deleteDoc` /
  stale-voice eviction are provided; a global LRU would live in the app).
- Backward (behind-the-playhead) opportunistic fill; a backward seek moves the
  window there anyway.

## Data / control flow

```
UI / MediaController
   │ load(docId, sentences, voice, startIndex) · play/pause/seekToSentence/setSpeed
   ▼
PlaybackController (main thread, thin)
   │  playlist = contiguous cached chunks, mediaId = sentence index
   │  onMediaItemTransition ──► currentSentenceIndex ──► scheduler.setPlayhead(i)
   │  collects SynthState ──► appends newly cached chunks to the playlist
   ▼
SynthesisScheduler (one worker coroutine on a work dispatcher)
   │  loop: target = first uncached in [playhead, playhead+lookahead)
   │        else (within maxAheadBytes) first uncached beyond the window
   │        else idle-suspend on playhead StateFlow (no polling)
   │  renderChunk: engine.synthesize(text, voice@1.0)
   │               ── Ok ──► samples + pauseAfterMs silence ─► WavCodec.encode
   │               ── Err(SynthesisFailed) ─► fallback silence chunk + lastError
   ▼
ChunkStore.write(index, wav)  =  <root>/<docId>/<voiceKey>/<index>.wav.tmp → rename
   ▲                                                            │
   │ same primitives, start-to-end walk                         ▼
FullBookRenderWorker (WorkManager)                 ExoPlayer ◄─ file:// MediaItems
```

`AiReaderPlaybackService` wraps `controller().player` in a `MediaSession`;
Media3 handles foreground state and the default notification. The app sets
`AiReaderPlaybackService.dependencies` (a `PlaybackDependencies`) in
`Application.onCreate`; the worker reads the same holder.

## Files (under `core/playback/src/{main,test}/kotlin/dev/nymph/aireader/playback/`)

| file | role / key exports |
|---|---|
| `audio/WavCodec.kt` | Pure: `encode(FloatArray, sampleRate)` → 16-bit LE mono PCM WAV bytes (clamping, NaN→0); `appendSilence(samples, rate, ms)`; `silenceSampleCount`; `decode`/`decodeHeader` back to PCM with typed `WavError` (used by export). |
| `cache/ChunkCache.kt` | `CacheError`; `voiceKey(engineId, voiceId, speed)` + `playbackVoiceKey(voice)` (pins speed 1.00); `ChunkStore` interface (`has`/`fileFor`/`write`/`totalBytes`); `ChunkCache(rootDir)`: `forDocument`, `deleteDoc`, `totalBytes(docId)`, `evictOthers(docId, keepVoiceKey)`. |
| `synth/SynthWindow.kt` | Pure windowing policy: `nextTarget(playhead, size, lookahead, usedBytes, maxAheadBytes, isCached)`, `cachedThrough(playhead, size, isCached)`. |
| `synth/ChunkSynthesis.kt` | `renderChunk(engine, sentence, voice, fallbackSampleRate, fallbackSilenceMs)` → `ChunkRender(wavBytes, sampleRate, error?)`; appends `pauseAfterMs`; silence fallback on failure. `renderMissingChunks(...)`: the start-to-end walk shared by both workers. |
| `synth/SynthesisScheduler.kt` | `SynthesisConfig` (lookahead 8, 256 MiB ahead budget, fallback 24 kHz / 300 ms, write-retry 1 s), `SynthState(playhead, cachedThrough, isSynthesizing, isComplete, lastError)`, `SynthesisScheduler(sentences, engine, voice, cache, scope, config)`: `start(initialIndex)`, `setPlayhead`, `state`/`playhead` StateFlows, `cancel`/`stop`. |
| `service/PlaybackDependencies.kt` | Locator interface: `controller()`, `chunkCache()`, `engineFor(voice)`, `sentencesFor(docId)`. |
| `service/PlaybackController.kt` | Thin ExoPlayer wrapper: `player`, `load`, `play`/`pause`, `seekToSentence`, `skipNext`/`skipPrevious`, `setSpeed`, `release`, `currentSentenceIndex`/`synthState` StateFlows. |
| `service/AiReaderPlaybackService.kt` | `MediaSessionService` + `companion var dependencies: PlaybackDependencies?`. Registered in the module manifest (`mediaPlayback` foreground type). |
| `service/FullBookRenderWorker.kt` | `CoroutineWorker`; inputs `KEY_DOC_ID`/`KEY_ENGINE_ID`/`KEY_VOICE_ID` (`inputData(docId, voice)` helper); progress `PROGRESS_COMPLETED`/`PROGRESS_TOTAL`; failure data `KEY_ERROR`. |
| tests | `audio/WavCodecTest`, `cache/ChunkCacheTest` (temp dirs), `synth/{SynthWindowTest, ChunkSynthesisTest, SynthesisSchedulerTest}` with `synth/Fakes.kt` (`FakeTtsEngine` with gate/failure controls, `InMemoryChunkStore`). All pure JVM; no Android/Media3 in tests. |

## Invariants and constraints

- **Cache chunks are always speed-1.0 audio.** The scheduler and worker pin
  `VoiceSpec.speed` to 1.0 before synthesis, and `playbackVoiceKey` writes
  `_1.00` into the directory name regardless of the user's speed. Listening
  speed is applied by the player (`setPlaybackSpeed` / PlaybackParameters), so
  speed changes never invalidate cached audio.
- **The scheduler never synthesizes the same index twice concurrently.** There
  is exactly one worker coroutine per scheduler, and every target is checked
  against the cache (with a grow-only memo) before rendering. The only repeat
  render is a deliberate retry after a *cache write* failure, after a back-off
  delay.
- **Playback only ever reads complete files.** `ChunkStore.write` writes to
  `<index>.wav.tmp` and renames into place; `has` and the playlist only ever
  reference the final `.wav` name, so a file's existence implies it is fully
  written. The worker and scheduler can run concurrently for the same document
  without corruption for the same reason.
- The playhead window `[playhead, playhead+lookahead)` is exempt from the
  `maxAheadBytes` budget — the budget only limits opportunistic fill beyond
  the window — so playback can never be starved by the budget.
- A per-sentence `SynthesisFailed` never stalls the queue: a short silence
  chunk (fallback 300 ms + the sentence's `pauseAfterMs`) is cached in its
  place and `SynthState.lastError` records the failure (sticky).
- The scheduler idles by suspending on its playhead `StateFlow` — no busy
  polling. `setPlayhead` interrupts an idle wait immediately; an in-flight
  synthesis is never cancelled (the result is still useful and native
  synthesis is not interruptible), it finishes and the next target is computed
  from the new playhead.
- `SynthState.cachedThrough` is the contiguous cached prefix from the current
  playhead; the controller may enqueue exactly the files up to that index.
- `stop()` joins the worker before returning, so `TtsEngine.release()` can be
  called safely afterwards (core:tts forbids releasing during synthesize).
- Sentence `index` is the cache key and must be the contiguous-from-0 index
  guaranteed by `SpeakableDocument` / core:data's `sentences.idx`.
- A voice change makes old chunks stale: callers must use
  `evictOthers(docId, keepVoiceKey)` on load (the controller does).
- No exceptions cross boundaries: failures surface as `Outcome.Err`
  (`TtsError`, `CacheError`); only `IOException` is caught, narrowly, in the
  cache write path.
- `PlaybackController`, the service, and the worker are deliberately thin and
  not unit-tested; all policy lives in the pure, tested layers.
