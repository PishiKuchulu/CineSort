# External Review #4 — Line-by-Line Verification (2026-11-01)

Source reviewed by external model: `CineSort_audited_source.zip` (pre-CI-fix tree).
Verified against the current tree (HEAD eaee842) by Pishi, finding by finding.

## Verdict summary

| Severity | Count | Confirmed | Partial | Rejected |
|---|---|---|---|---|
| Critical | 1 | 1 | 0 | 0 |
| High | 9 | 8 | 1 (H7 retry half) | 0 |
| Medium | 14 | 13 | 1 (M12 foreground part) | 0 |
| Low | 12 | 9 | 2 (L5, untraced L2/L9 counted partial) | 0 |

One of the most accurate external reviews received on this project. No finding was
rejected. Fix program is batched A–D (see goal timeline).

## Fix status (2026-11-01, batches A–D implemented locally; local suite 75/75)

- **FIXED:** C1 (segment-shape `SafUris` helpers; delete/move tree-root guards),
  H1 (depth comparator tiebreak), H2 (gateway methods switch to Dispatchers.IO),
  H3 (bootstrap-before-persist + error state in Sources/Onboarding), H4 (lazy
  `ExcludingList` siblings view), H5 (year search bound: first resolution token or
  first ≥2-run of noise), H6 (domain strip requires an anchor after a title token),
  H7 (companion sizes stat'ed in the worker; `operations.sourceFileId` recorded —
  DB v2 + migration — retry drafts prefer recorded ids), H8 (worker skips
  all-DONE items; executor closes stale ops as FAILED "interrupted"), H9
  (similarity subset guard), M1 (needs-review excluded from conflicts), M2
  (first duplicate keeps the plain name; companions renamed with the main file),
  M3 (folder names unified per normalized key; deterministic existing-folder pick),
  M4 (validator normalizes URIs to document ids), M5 (executor journals FAILED
  states incl. pre-operation failures), M6 (`StorageGateway.recreateDir`; undo
  recreates deleted source folders), M7 (TMDB/OMDb clients cached), M8 (local
  field state + 800 ms debounced key saves; SecretStore flowOn IO), M9 (replan
  mutex + cancel-previous), M10 (snapshot walk parallelized, bounded 8), M11
  (MainActivity is AppCompatActivity), M12 (worker notification strings moved to
  resources EN/FA), M13 (rename-null is an IOException), M14 (hyphenated episode
  forms), L1 (regexes hoisted), L3 (future mtime no longer pins RECENTLY_MODIFIED),
  L4 (`فصل N` season folders with Persian/Arabic digits; NormalizedKey folds
  Arabic Yeh/Kaf + digits), L5 (bidi/zero-width format controls stripped —
  ZWNJ/ZWJ kept; surrogate-safe truncation), L6 (persistable grants released on
  remove/replace), L7 (unique index on source_folders.uriString + add-dedupe),
  L8 (report counts skipped), L9 (group toggle in one pass).
- **L2 REJECTED on re-check:** the "Why?" sheet does render `trace.rules`
  (EditSheet.kt) with human-readable labels; no change.
- **DEFERRED with reasons:** L10 (toolchain upgrade — pinned baseline is a
  deliberate project decision, revisited at release time), L11 CI half (detekt on
  Android modules needs workflow edits; the App push route cannot write workflow
  files). M7's remaining sub-points (degraded-result cache TTL, cache-key
  components) noted as follow-ups, not fixed in this wave.

## Critical

- **C1 CONFIRMED** — `SafStorageGateway` lines 52–81: child refs are 4-segment
  `tree/<T>/document/<D>` URIs; `DocumentsContract.isTreeUri()` is true for them
  (AOSP checks only segment[0]=="tree"), so `documentIdOf` returns the TREE id and
  `documentUriOf` builds the TREE ROOT document URI. Call sites: listChildren (297),
  createDir (329), move (340–347), copyVerified (378/385), delete (431), freeBytes (436).
  Every non-root operation resolves to the tree root. Also `isValid` uses
  fromTreeUri on child URIs (checks the root). Same assumption in
  `RunDraftRepository.childDocumentUri`.

## High

- **H1 CONFIRMED** — `UndoExecutor`: `sortedSetOf(compareByDescending { depth })`
  treats equal-depth paths as duplicates; only one created dir per depth survives.
  Still present after the CI run-#4 refactor (carried over verbatim).
- **H2 CONFIRMED** — `UndoExecutor` called from `viewModelScope.launch` (Main) in
  Home/Report/History ViewModels; gateway methods do no dispatcher switch (only
  `scan()` uses `flowOn(IO)`). SAF I/O on the main thread.
- **H3 CONFIRMED** — `SourcesViewModel.onDestinationPicked`: `setDestination` persists
  BEFORE `ensureRoots`, which is unguarded in a bare launch (IOException → crash).
  Same pattern in OnboardingViewModel.
- **H4 CONFIRMED (quadratic part)** — `scan()` builds `siblingRefs.filter { it.id != … }`
  per file → O(n²) allocations per directory; `siblingRefs.first {}` linear per file.
  Emitting every file is intentional (session classifies with user settings), not a bug.
- **H5 CONFIRMED** — `NoiseVocabulary.source` contains `web`, `cam`; codec contains
  `avc`, `hdr`, `dv`. `detectMovie` rejects any year at/after the first such token:
  `Charlotte's.Web.2006…` → year lost, title `Charlotte's Web 2006`, MEDIUM.
- **H6 CONFIRMED** — `domainPrefixRegex` strips `The.Net.` from `The.Net.1995…`; the
  guard only requires SOME anchor afterwards (the year itself qualifies) → title `1995`.
- **H7 CONFIRMED / PARTIAL** — `OrganizeWorker.decodeCompanions` builds
  `FileRef(size = 0)` → `copyVerified` size check always fails cross-volume companions.
  Retry-draft half PARTIAL: `childDocumentUri` reconstructs URIs from parent id + name
  with a catch-fallback to the parent URI itself (design flaw real; the exact
  "points at a directory" trigger is provider-dependent). Extra defect found during
  verification: `writeRetryDrafts` forces `type = MOVIE` and drops season/episodes.
- **H8 CONFIRMED** — `JournalStore.unfinishedRuns()` has zero callers; worker re-runs
  all drafts on restart.
- **H9 CONFIRMED** — `TitleSimilarity.tokenSetRatio` is classic token-set ratio:
  subset ⇒ ratio(core, combined) = 1.0; `similarity()` takes the max ⇒ subset titles
  score a perfect 1.0 (`Elite Force` ⇢ `Elite`).

## Medium

- **M1 CONFIRMED** — planner conflict pass excludes only SKIPPED; NEEDS_REVIEW items
  with empty targetSegments share targetPath = bare file name → bogus conflicts;
  KEEP_BOTH marks them READY with empty segments (move to destination root).
- **M2 CONFIRMED** — KEEP_BOTH suffixes EVERY source-vs-source duplicate (`(1)`, `(2)`)
  even when the destination is empty; first item should keep its name. The
  `else → CONFLICT` branch is unreachable (candidate always differs after suffixing).
- **M3 CONFIRMED** — folder names derive per item from its own title string, not
  unified by NormalizedKey (`Spider-Man` vs `Spider.Man` → two folders);
  `findExistingMovie` takes `firstOrNull` over a Set (arbitrary when year unknown).
- **M4 CONFIRMED** — `FolderSetValidator.isInside` expects bare tree document ids
  (`primary:Movies/Library`) but callers pass full `content://` URIs (URL-encoded),
  so nested-source dedupe/validation never matches with real ids. Unit tests pass
  doc-id-shaped ids, masking it.
- **M5 CONFIRMED** — executor transitions ops DIRS_READY→…→DONE but never FAILED and
  never sets `failureReason`; failures live only in the in-memory report.
  Pre-op failures (ensureDirs) leave no journal record.
- **M6 CONFIRMED** — worker cleanup deletes emptied source folders; Undo then reports
  "original folder is gone" instead of recreating parents. Junk mode deletes
  non-video files permanently.
- **M7 CONFIRMED (main)** — `buildTmdbApi`/`buildOmdbApi` construct a new
  OkHttpClient + Retrofit per call (7 call sites; one inside the retry lambda, so
  per retry too). Sub-points (degraded-result caching, cache key, refresh gate,
  override-title queries, TMDB-fail → no OMDb) consistent with the code layout;
  not each traced end-to-end.
- **M8 CONFIRMED** — `onTmdbKeyChange`/`onOmdbKeyChange` launch an encrypted
  DataStore write per keystroke; field is bound to the store-backed value.
- **M9 CONFIRMED (structure)** — `OrganizeSession` keeps plain mutable fields
  (`lastSettings`, `selectionOverrides`, `metadataUnavailable`) and launches
  unserialized replans on a multi-threaded IO scope; no job/mutex guards ordering.
- **M10 CONFIRMED** — destination snapshot walks folders with per-folder SAF
  queries up to depth 3 on every scan (OrganizeSession line 316, MAX_SNAPSHOT_DEPTH=3).
- **M11 CONFIRMED** — `MainActivity : ComponentActivity`; per-app locales on
  API 24–32 need AppCompatActivity (appcompat is already a dependency).
- **M12 CONFIRMED / PARTIAL** — hard-coded English notification channel name
  ("Organize"), title ("CineSort — Organizing") and "Cancel" action in
  `OrganizeWorker` (lines 226–247). setForeground/Android-15 timeout points PARTIAL
  (theoretical without a device).
- **M13 CONFIRMED** — `copyVerified`: `renameDocument(...) ?: partialUri` returns a
  FileRef named as the target while the document is still `*.cinesort-part`.
- **M14 CONFIRMED** — tokenizer does not split on `-` inside tokens; episode
  regexes can't match `S01E01-E03` / `S01-E01` → parsed as movie.

## Low

- **L1 CONFIRMED** — `Regex(...)` compiled inline in hot paths (parser lines 118,
  134, 167, 458; TitleSimilarity.normalize).
- **L2 NOT TRACED** — plausible (ParseTrace usage); cosmetic. Counted partial.
- **L3 CONFIRMED** — future `lastModified` makes `now - lastModified` negative →
  RECENTLY_MODIFIED forever (ScanRules age check).
- **L4 CONFIRMED** — no `فصل` handling anywhere in parser/domain; season-folder
  regexes are Latin-only.
- **L5 PARTIAL** — NameSanitizer has no bidi/zero-width stripping (verified);
  empty-name and surrogate-split claims not individually reproduced.
- **L6 CONFIRMED** — `releasePersistableUriPermission` never called (0 hits).
- **L7 CONFIRMED** — `source_folders` entity has no unique index on `uriString`.
- **L8 CONFIRMED** — `ExecutionReport.skipped` is initialized 0 and never incremented.
- **L9 NOT TRACED** — plausible from session code shape. Counted partial.
- **L10 CONFIRMED (factual)** — toolchain is late-2024 baseline per spec deviation.
- **L11 CONFIRMED (factual)** — CI is debug-only; detekt/ktlint only on JVM modules.
- **L12 CONFIRMED** — `scan()` deletes stale `*.cinesort-part` files on sight.
