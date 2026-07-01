# Library (core:data)

## Scope

Persistence for the document library and app settings:

- Room database storing imported documents and their TTS-ready sentences.
- Playback position, per-document voice choice, and import status on the document row.
- DataStore-backed app settings (default engine/voice/speed, synthesis lookahead).
- Model-typed repositories (`LibraryRepository`, `SettingsRepository`) so other modules
  never see Room entities or preference keys.

Non-scope: extraction and text normalization (core:extraction / core:pipeline produce
the `SpeakableDocument` that is persisted here), audio chunk caching (core:playback owns
the on-disk cache, keyed by document id + sentence idx), TTS model files (core:tts).

## Data flow

Import: UI runs extraction + pipeline → `LibraryRepository.importDocument(source, speakableDocument)`
→ pure mappers build `DocumentEntity` (status `ready`, position 0, no voice) +
`SentenceEntity` rows → `DocumentDao.insertDocumentWithSentences` writes both in one
`@Transaction` → new doc id returned to the caller.

Playback: service calls `getSpeakable(docId)` (or `SentenceDao.getRange` for lookahead
windows) → synthesizes → on sentence advance calls `updatePosition(docId, idx)`, which
also stamps `lastPlayedAtEpochMs` from the repository clock.

Library UI: `observeLibrary()` maps `DocumentDao.observeAll()` (Room Flow, re-emits on
any table change) through `libraryItemFrom` to `LibraryItem` summaries.

Settings UI: `SettingsRepository.settings` Flow + suspend setters; values are parsed and
sanitized by pure helpers in both directions.

## Schema (version 1, exportSchema = false)

`documents`
- `id` INTEGER PK autogenerate
- `title` TEXT — extraction title, or `"Untitled"` when none
- `sourceType` TEXT — `"pdf"` | `"web"`; with `sourceRef` (SAF URI / URL) round-trips
  to model `DocumentSource`
- `addedAtEpochMs` INTEGER, `lastPlayedAtEpochMs` INTEGER NULL
- `sentenceCount` INTEGER, `currentSentenceIndex` INTEGER (default 0)
- `engineId` TEXT NULL, `voiceId` TEXT NULL, `speed` REAL (default 1.0) — all-or-nothing
  voice choice; both ids null ⇒ no voice chosen ⇒ model `VoiceSpec?` is null
- `status` TEXT — `"ready"` | `"importing"` | `"failed"`; `errorMessage` TEXT NULL only
  meaningful for `failed`

`sentences`
- composite PK (`docId`, `idx`); FK `docId` → `documents.id` ON DELETE CASCADE; index on `docId`
- `text` (normalized, sent to TTS), `displayText` (UI), `blockIndex`, `pauseAfterMs`

Library ordering: `lastPlayedAtEpochMs IS NULL ASC, lastPlayedAtEpochMs DESC, addedAtEpochMs DESC`
(recently played first, never-played after, newest added first).

## Files

All under `core/data/src/main/kotlin/dev/nymph/aireader/data/`:

| File | Role / key exports |
| --- | --- |
| `db/DocumentEntity.kt` | `DocumentEntity` Room entity (`documents`). |
| `db/SentenceEntity.kt` | `SentenceEntity` Room entity (`sentences`), composite PK + cascade FK. |
| `db/DocumentDao.kt` | `DocumentDao`: `observeAll`, `observeById`, `getById`, `insert`, `update`, `deleteById`, `updatePosition`, `updateVoice`, `updateStatus`, `updateTitle`, `@Transaction insertDocumentWithSentences(document, sentencesFor)`. |
| `db/SentenceDao.kt` | `SentenceDao`: `insertAll`, `getForDocument`, `getRange(docId, fromIdx, count)`, `deleteForDocument`. |
| `db/AiReaderDatabase.kt` | `AiReaderDatabase` (version 1) + `build(context)` factory. |
| `db/Mappers.kt` | Pure, JVM-tested mapping: `sourceTypeOf`/`sourceRefOf`/`documentSourceFromColumns`, `VoiceColumns`/`voiceColumnsOf`/`voiceSpecFromColumns`, `documentEntityFor`, `sentenceEntitiesFor`, `speakableDocumentFrom`, `progressFraction`, `libraryItemFrom`, `DocumentStatus` enum, `SOURCE_TYPE_PDF`/`SOURCE_TYPE_WEB`/`UNTITLED_TITLE`. |
| `db/LibraryRepository.kt` | `LibraryItem` summary type; `LibraryRepository(documentDao, sentenceDao, clock)`: `observeLibrary`, `importDocument`, `getSpeakable`, `updatePosition`, `updateVoice`, `updateStatus`, `rename`, `delete`. |
| `settings/AppSettings.kt` | `AppSettings`, `SettingsDefaults`, pure parsers `engineIdFromStored`, `voiceIdFromStored`, `speedFromStored`, `lookaheadFromStored`, `appSettingsFromStored`. |
| `settings/SettingsRepository.kt` | `SettingsRepository(dataStore)`: `settings: Flow<AppSettings>` + suspend setters; private preference keys. |

JVM tests: `core/data/src/test/kotlin/dev/nymph/aireader/data/db/MappersTest.kt`,
`.../settings/AppSettingsTest.kt`. DAO/Database classes are compile-only here
(no Robolectric); instrumented coverage would live in `androidTest`.

## Invariants and constraints

- Sentence rows are immutable after import. A document's sentences are only written as a
  complete set: inserted in the import transaction, removed by FK cascade on document
  delete (or `deleteForDocument`). Re-import = delete + insert; never row updates.
- `idx` is contiguous from 0 for every document (enforced upstream by
  `SpeakableDocument`'s init check) and equals `SpeakableSentence.index`; it is the
  stable playback/seek key and the audio-chunk cache key.
- `sentenceCount` on the document row always equals the number of sentence rows; both
  are written in the same transaction and never updated afterwards.
- Voice columns are all-or-nothing: `engineId`/`voiceId` are either both set or both
  null; `speed` defaults to 1.0 when no voice is chosen.
- Mappers never throw on persisted data: unknown `sourceType` → row dropped from
  library; unknown `engineId` → voice treated as unset; unknown `status` → `FAILED`;
  out-of-range stored speed clamped to 0.5–3.0, non-finite → 1.0 (keeps `VoiceSpec`'s
  range `require` unreachable from the DB path).
- Settings parsing never throws: invalid engine name → KOKORO, blank voice → `af_heart`,
  bad speed → clamped/1.0, lookahead < 1 → 8. Setters sanitize through the same helpers
  so invalid values are never persisted.
- `LibraryRepository` is the module's public API; Room entities and DAOs should not
  leak past core:data consumers.
