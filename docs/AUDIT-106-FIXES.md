# CineSort v1.0.6 — Granular Audit Fix Record

Fix wave for the granular audit of v1.0.5 (`audit-105/section-1-uiux.md`,
`section-2-logic.md`, `section-3-4-perfsec.md`), applied in four batches
matching the audit's four layers. This file is the accounting: what was
fixed, what was deliberately left, and the judgment calls in between.

Totals across the audit: **1 critical · 26 major · 35 minor** findings.

- Section 1 (UI/UX): 29 weakness rows — **29 fixed, 0 left**
- Section 2 (logic/state + engine↔UI): 20 Part-A gaps + Part-B flow findings — **all addressed**
- Section 3 (performance): 10 rows carrying a weakness note — **7 fixed, 3 left with evidence**
- Section 4 (security/integrity): 5 rows — **3 fixed (all majors), 2 left with evidence (both minor)**

Local verification at the end of the wave: `run-tests.sh` **106/106**,
`run-metadata-tests.sh` **34/34**, detekt `:core:parser` + `:core:domain`
**0 findings**, EN/FA string parity **389/389 unique keys, zero duplicates**.
Android modules (`:app`, most of `:core:data`) have no local compile gate —
CI remains their first real compile, as before.

---

## Section 1 — UI/UX (batch 1)

All 29 weakness rows (1 critical, 13 major, 15 minor, of 47 component rows)
were fixed. Headlines:

- **Critical:** stepper +/− buttons had `contentDescription = null` in two
  duplicated implementations. Both now route through the shared
  designsystem `StepperRow` (`component/Controls.kt`) with labeled
  decrease/increase buttons.
- Light theme failed WCAG AA (white on primary `#F26A1B` = 3.06:1). The
  light primary was darkened to `#AC4500` (`theme/Color.kt`), restoring
  ≥4.5:1 for button text; badge/status tokens re-derived to match. The
  dark theme was already passing and is unchanged.
- The update dialog vanished silently on download failure: the NavHost
  render gate now includes the Failed state with retry/dismiss.
- A shared `LoadingState` (`component/States.kt`) was added and adopted
  where Report/History flashed an empty body or a premature EmptyState.
- EditSheet's episodes field no longer forces `KeyboardType.Number` on a
  comma-separated input; FreeSpaceBar on Sources gets a real total;
  neutral statuses (SKIPPED / ALREADY_ORGANIZED / running) no longer use
  the error color; `persianDigits` is honored in Run and Report; raw
  exception text in Scan/Report is mapped to localized messages;
  ApiKeyField gained an error state and a test-in-progress indicator;
  Preview group checkbox is tri-state; EditSheet radio rows are clickable
  on their text and "apply to all" ordering was fixed.

String parity after this batch was reported as 363/363 — by occurrence
count. Batch 2 found that count masked a duplicate key (see judgment
calls).

## Section 2 — Logic/state + engine↔UI gaps (batch 2)

**Part A — all 20 gaps addressed** (6 unsurfaced, 3 unwired, 11 partial):

- Surfaced: per-source scan-depth editor in Sources; destination removal
  with confirmation; a hint defining "samples" (<100 MB preview clips);
  History paging beyond the hard 10-run cap ("Show more", 10 → +10…);
  in-place editing of saved title corrections; parser limits documented
  in the README (50-episode range cap, 240-byte folder cap, domain-prefix
  rule) — documentation-only, per the audit's own proposal.
- Wired: the Settings `maxScanDepth` stepper, previously dead, is now a
  real cap via the pure rule `ScanRules.effectiveDepth(source, settings)
  = min(source ?: settings, settings)` coerced to 1..10, used by
  `startScan` (unit-tested). Movie/series decoration asymmetry was made
  intentional and documented, and Home chips now drive both flag pairs.
  The disabled "Checking" button is an intentional non-interactive
  placeholder (the updater API has no cancel-check) and is now marked as
  such in code — informational, per the audit.
- Completed: UNDONE got its own counting bucket everywhere
  (`countRunOperations`, priority DONE > UNDONE > SKIPPED > FAILED) and
  retry excludes undone items; poster operations are excluded from item
  counts and reported separately, and the worker publishes a posters
  progress phase; scan errors are accumulated and summarized on Scan and
  Preview; the planner's free-space check now produces a visible
  shortfall warning in Preview; Preview rows show "Matched as: …";
  EditSheet candidate search tries TMDB then OMDb and labels the
  provider; the `metadataUnavailable` flag reaches a Preview banner;
  SKIPPED is actually journaled (executor non-runnable items +
  conflict-skips) and the Preview filters separate SKIPPED /
  RECENTLY_MODIFIED / ALREADY_ORGANIZED.

**Part B — flows & ViewModels:** interrupted runs are finalized
(`JournalMaintenance.closeInterruptedRun` closes non-final ops as
FAILED("interrupted") **and** marks the run finished; History gained a
matching "close as interrupted" action); `refreshSettingsAndReplan` goes
through the same serialized gate as edit-replans (mutex) and Preview
re-plans on entry when settings changed; `applyEdit` reports dropped
edits and apply-to-series on an Unrecognized item degrades to that item;
Report `load()` always refreshes; undo is serialized process-wide by a
new `UndoCoordinator` and resolves the destination from the run's own
recorded destination (below); Settings' video-extensions and noise-words
fields got the API-key treatment (local state, 800 ms debounce, flush on
`onCleared`); the updater controller moved to an activity-scoped
`AppUpdateViewModel`, and an interrupted leftover `.part` now surfaces
as a note in Settings; onboarding step state is `rememberSaveable` and
source creation uses the shared default-depth constant.

**Schema:** Room DB **v4 → v5** — `runs` gains `destinationId` /
`destinationName` (`TEXT NOT NULL DEFAULT ''`), `MIGRATION_4_5` in
DataModule, domain `RunRecord` carries both, `CineSortMigrationTest`
extended to migrate 1→5 and assert the new columns and legacy-row
defaults.

## Section 3 — Performance (batch 3)

Fixed (7):

- **RunViewModel polling (major):** the 1-second loop materializing the
  whole run journal per tick is gone. New `JournalStore.operationSummary`
  → Room aggregate queries (`SELECT DISTINCT itemId, state …` for main
  ops + `COUNT(*)` for done posters), collapsed by the same priority
  definition as the full-list counter; poll interval relaxed to 2.5 s.
  History and Home last-run counting use the same summary with identical
  tallies (an equivalence test pins summary ≡ full-list counts).
- **Executor O(n²) on systemic failure (major):** `execute()` takes one
  journal snapshot; a live in-memory op index, maintained by routing
  every insert/update through helpers, serves `markItemFailed`. A test
  with 6 failing items asserts exactly one `operationsOf` call.
- Preview selection aggregates are `remember`-scoped (no four O(n)
  passes per recomposition/keystroke).
- All 12 `collectAsState()` sites migrated to
  `collectAsStateWithLifecycle()` (the lifecycle-compose artifact was
  already a dependency).
- Poster downloads parallelized with `Semaphore(3)` + async/awaitAll.
- Metadata cache is pruned at scan-batch start: negatives past the
  7-day TTL and everything past the 30-day TTL are deleted, with a
  2,000-row oldest-first cap.
- Update checks are throttled: automatic (launch) checks are no-ops
  within 24 h of the last performed check (pure `isAutoCheckDue`,
  unit-tested); manual checks from Settings and the dialog's Retry are
  never throttled.

Left with evidence (3) — see judgment calls:

- `OrganizeSession` retaining scanned → parsed → planned lists.
- Executor's ~5–6 journal writes per file.
- HomeScreen `Column` + `verticalScroll` instead of LazyColumn.

Note: the Report-retry repeated-fetch mentioned in early summaries no
longer existed after batch 2 (`refreshOperations` fetches once per load;
`retryFailed` fetches once) — verified in code by batch 3, nothing to do.

## Section 4 — Security & data integrity (batch 4)

**Row 4-5/1 — cross-volume copy verified by size only (major) — FIXED.**
`copyVerified` now verifies content, not just size:

- SAF gateway: the source is SHA-256-hashed as it streams to the
  `.cinesort-part` file (no second source read); after the existing size
  checks, the partial is re-read and hashed, and the digests are
  compared **before** the rename. A mismatch throws `IOException`, the
  existing catch deletes the partial, the executor marks the item FAILED
  with the hash-mismatch reason, and `gateway.delete(source)` is never
  reached. The same protection covers the SAF `move()` fallback and
  Undo's copy path, which share `copyVerified`.
- Same-volume renames are untouched (no copy, no hash).
- The domain contract (`StorageGateway.copyVerified`) documents the
  stronger guarantee; the in-memory fake models content identity per
  node and mirrors the verify-and-roll-back behavior, with a
  `corruptCopyContent` hook that writes same-size garbage.
- New tests (`Section4AuditFixesTest`, +3): matching hash → copy lands
  with the source's content identity and only then is the source
  deleted; corrupted copy → item FAILED, source kept, no destination
  file left; gateway-level rejection leaves nothing behind.

**Row 4-5/2 — non-transactional resume-draft rewrite (major) — FIXED.**
`ExecutionDraftDao.replaceRun(runId, entities)` is a single Room
`@Transaction` (clear + insert); `RunDraftRepository.writeDrafts` now
delegates to it. A crash mid-rewrite leaves either the old drafts or the
new ones — never an empty run. (`Daos.kt` is compiled by the local
metadata harness; the Room/KSP side is CI-gated as usual.)

**Row 4-1/4 — silent settings/secret loss on storage failure (major) —
FIXED.** "Read error" is now distinguishable from "empty":

- New `StorageHealth` / `StorageHealthStore` (`core:data`): two
  persisted flags — `settingsReset`, `secretsUnreadable` — in a small
  dedicated SharedPreferences file (deliberately not in either DataStore:
  the flag must survive the corruption it reports). Instances sync via a
  preference-change listener.
- `SettingsRepository`'s DataStore `catch (IOException)` still falls
  back to defaults, but now also marks `settingsReset`.
- `SecretStore` marks `secretsUnreadable` when a stored ciphertext
  cannot be decrypted or its keyset/master key is unavailable (the
  Keystore-invalidation case), and when the secrets DataStore itself
  fails to read. Truly empty storage never signals. The signal is raised
  at most once per process; the persisted flag lasts until dismissed.
- Settings shows a one-time dismissible notice card (error-container)
  naming exactly what was lost — settings reset and/or keys unreadable —
  via `SettingsViewModel.storageHealth`. Strings:
  `settings_storage_notice_title/_settings/_keys/_dismiss` (EN + FA).

**Row 4-3/2 — TMDB v3 key in the query URL (minor) — LEFT, inherent.**
This is TMDB's own v3 design. The mitigation the audit asks for already
exists: `TmdbAuthInterceptor` (MetadataService.kt) sends v4 read-access
tokens in the `Authorization` header and only falls back to the
`api_key` query parameter for v3 keys, and the HTTP logger redacts both
forms from logs. Whether a given account can use v4 is the account
holder's choice at key-entry time; there is no code change that removes
the v3 URL parameter without dropping v3-key support.

**Row 4-3/4 — path/URI values in a few Timber warnings (minor) — LEFT,
as the audit proposes.** The audit's proposal is to keep the current
pattern and sanitize those two call sites only if crash reporting is
ever added. No crash reporting exists, and release builds plant no
Timber tree at all (`CineSortApp` plants `DebugTree` only under
`BuildConfig.DEBUG`), so in the shipped app these logs go nowhere.

Rows the audit verified clean (Tink/Keystore storage, path traversal,
HTTPS-only, exported components, backup exclusion, permissions, journal
transitions, undo) were not churned.

## Judgment calls

1. **Unique work: REPLACE, not KEEP (B.6).** The audit text proposes
   `enqueueUniqueWork(…, REPLACE/KEEP)` plus a session phase guard. The
   implementation uses `ExistingWorkPolicy.REPLACE` together with a
   `runStartMutex` and a hard refusal while the phase is RUNNING: a
   second confirm supersedes a stale *queued* request instead of
   queueing a duplicate run, and the phase guard makes it impossible to
   disturb an *active* run. KEEP would have silently dropped a
   legitimate re-confirm behind an already-queued request.
2. **Hash verification is always on for copies, not a setting.** The
   audit offered "a settings option for full hash verification, or at
   least sampled hashing". The fix order specified full SHA-256
   verification, so it is unconditional on copy paths (cross-volume and
   rename-fallback); renames pay nothing. The documented trade-off is
   one extra read of the destination per copied file.
3. **Three performance rows left (Section 3).** (a) The session's
   retained scanned/parsed/planned lists are load-bearing — replans
   (settings edits, EditSheet edits, conflict choices) rebuild from
   them and worker drafts are built from `PlannedItem`s; dropping them
   would force a full rescan per settings tweak. The raw scan list is
   already scan-local and released. (b) The executor's intermediate
   journal states (DIRS_READY/COPYING/VERIFIED) are exactly what resume
   hygiene and per-operation resume read; the audit itself rates the
   cost acceptable and makes the proposal conditional ("if needed").
   Copy-progress bytes are already memory-only until VERIFIED/DONE.
   (c) HomeScreen's `Column` is marked "acceptable" by the audit itself
   and conditioned on content growth; the content is still the bounded
   checklist + stats + last-run set.
4. **Duplicate string key found and removed (batch 2).**
   `history_open_report` existed twice in each strings.xml with two
   different FA translations — AAPT2 rejects duplicates, and batch 1's
   363/363 parity count (occurrences, not unique keys) had masked it.
   The later copy was removed from both files (the history-section entry
   kept): 362 unique + 23 new keys from batch 2 = 385/385 unique.
   Batch 4 adds 4 more per locale: **389/389 unique, zero duplicates**.

## Version & test totals

- Version: **1.0.6 / versionCode 7** (`app/build.gradle.kts`).
- DB schema: **v5** (batch 2, `MIGRATION_4_5`).
- Local tests: `run-tests.sh` 88 → 98 (batch 2) → 103 (batch 3) →
  **106** (batch 4, +3 hash-verification); `run-metadata-tests.sh`
  29 → 31 → **34** (batch 3; unchanged in batch 4).
- detekt `:core:parser` and `:core:domain`: 0 findings throughout.
