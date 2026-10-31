# CineSort — Architecture

## Modules

```
:app                  Application, Hilt wiring, navigation host, manifest, all feature packages
:core:parser          PURE Kotlin/JVM. Anchor-based filename parsing, title casing, normalized keys
:core:domain          PURE Kotlin/JVM. Models, scan rules, naming, planner, executor, undo, journal port
:core:data            Room, DataStore, Tink secrets, SAF gateway, TMDB/OMDb, WorkManager worker
:core:designsystem    Cinema Noir theme, tokens, shared Compose components, Vazirmatn fonts
```

Dependencies point inward: `app → designsystem, data → domain → parser`.
The parser depends on nothing; the domain depends only on the parser and
kotlinx-coroutines. All file-system access goes through `StorageGateway`
(domain interface); the SAF implementation lives in `core:data`, and an
in-memory fake (`InMemoryStorageGateway`) ships in `core:domain` for tests.

## Pipeline

`Scan → Parse → Group → Enrich (optional) → Plan → Review → Execute → Report`

1. **Scan** — `SafStorageGateway.scan` walks source trees with
   `ContentResolver` child-document queries (BFS, cancellable, `Flow<ScanEvent>`).
   `ScanRules` (domain) classifies temp/hidden/sample/recent files.
2. **Parse** — `AnchorMediaNameParser`: title is everything before the first
   anchor (episode marker, else release year); the tail is ignored, so unknown
   release groups can never confuse it. Folder context (Stage E) rescues
   bare-episode names like `E01.mkv`.
3. **Enrich** — `MetadataService` (TMDB/OMDb per the §9.1 matrix) fills genres,
   rating and missing years; results cached in Room (30 days, 7 for negatives).
   Enrichment never blocks organizing.
4. **Plan** — `OrganizePlanner` is a pure function of scan results, parse
   results, metadata, naming options, overrides and a destination snapshot.
   The preview is exactly what execution will do.
5. **Execute** — the preview confirmation writes `ExecutionDraftEntity` rows;
   `OrganizeWorker` (foreground, dataSync) rebuilds the plan items and runs
   `OrganizeExecutor`, which journals every state transition
   (`PENDING → DIRS_READY → MOVING/COPYING → VERIFIED → DONE`) and moves files
   same-volume via `moveDocument` or cross-volume via verified copy
   (`.cinesort-part` + size check + rename + delete source).
6. **Undo** — `UndoExecutor` replays the journal backwards: files return to
   their original parents; only run-created, still-empty directories are
   removed; problems are reported, never forced.

## State and storage

- Room: sources, destination, metadata cache, title overrides, runs,
  operations (the journal), execution drafts. Schema export is on.
- DataStore Preferences: all non-secret settings (`AppSettings`).
- Tink AEAD + Android Keystore: TMDB/OMDb API keys.
- UDF everywhere in the UI: each screen has an immutable `UiState`, events in,
  a `StateFlow` out.

## Privacy

Only a cleaned title and a year ever leave the device, and only to
`api.themoviedb.org` / `www.omdbapi.com` (+ poster CDN). No analytics, no
crash SDKs, no ads. Manifest permissions are exactly the four in spec §14.
