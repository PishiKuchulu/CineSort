# Review #5 — Fix Record (v1.0.5)

**Subject:** fixes for the findings verified in
[REVIEW-5-VERIFICATION.md](REVIEW-5-VERIFICATION.md) (external review of the
v1.0.4 source): 12 CONFIRMED + 3 PARTIAL findings, the one item the report
missed (§4.1 of the verification), and the test-gap items of its §3 that this
wave closes.

**Release:** versionName `1.0.5`, versionCode `6` (`app/build.gradle.kts`).

**Wave gates** (local harness — Gradle cannot run in the build sandbox, CI is
the full gate; counts grow monotonically as the wave added tests):

| Part | Scope | Gate result |
|---|---|---|
| 1 | Domain executor/journal (H-A domain half, M-B domain half, M-C) | run-tests 79/79 · detekt 0 |
| 2 | Data layer (M-A, M-B data half, M-D, worker items, L-F) | run-tests 77 · metadata 17 |
| 3 | App layer + parser (H-A app half, M-E app half, M-H, M-I, L-A) | run-tests 82 · metadata 23 |
| 4 | Data update/settings (M-E data half, M-D cache/similarity) | run-tests 88 · metadata 29 |
| 5 | M-G, M-F, L-B, version bump, this record | **final: run-tests 88/88 · run-metadata 29/29 · detekt (parser+domain) 0 findings · workflow YAML parses and is byte-identical in both locations** |

---

## H-A — Narrowed exception handling aborted whole runs

- `core/domain/.../execute/OrganizeExecutor.kt`, `UndoExecutor.kt`: per-item
  catches widened from `IOException`/`SecurityException` to `Exception`
  (rethrowing `CancellationException`), recording the item/op FAILED and
  continuing; the run now always reaches `markRunFinished`.
- `app/.../sources/SourcesViewModel.kt`, `app/.../onboarding/OnboardingViewModel.kt`:
  `onDestinationPicked` catches broadly instead of only `IOException`.
- **Tests:** `unexpectedExceptionInOneItemDoesNotAbortTheRun`
  (DomainAcceptanceTest).

## Missed-by-report §4.1 — Worker swallowed `CancellationException`

- `core/data/.../work/OrganizeWorker.kt`: `CancellationException` is now
  rethrown at the worker's catch sites (lines 160/190/241/269) instead of
  being converted to `Result.failure()`, matching the codebase convention.

## M-A — Poster downloads were not journaled; downloader was unbounded

- DB v4: `operations.isPoster` (`MIGRATION_3_4` in
  `core/data/.../di/DataModule.kt`; `CineSortDatabase` version 4); poster
  downloads are recorded as journal operations, and undo deletes journaled
  posters so created folders empty out and are removed.
- `OrganizeWorker.downloadPosters` now receives only items whose move
  succeeded (failed items no longer get a poster in an otherwise empty
  folder).
- New `core/data/.../work/PosterImagePolicy.kt` (pure, unit-tested):
  bounded reads (`readBounded`, cap enforced while streaming), JPEG/PNG
  magic-byte verification, poster host allow-list (`image.tmdb.org`,
  OMDb image host) over HTTPS only. `PosterDownloader.kt` delegates to it.
- **Tests:** `undoDeletesJournaledPosterAndRemovesCreatedFolder`;
  `PosterImagePolicyTest` (7: provider hosts allowed, non-HTTPS/other hosts
  refused, magic bytes accepted/refused, bounded read at/over cap, empty
  stream refused).

## M-B — Resume/retry was only partly implemented

- `OrganizeExecutor` resumes **per operation**: DONE ops of an item are
  skipped, only the remaining ops execute, so a DONE-video/FAILED-companion
  item retries just the companion.
- `OrganizeWorker` now calls `JournalStore.unfinishedRuns()` at startup and
  closes stale PENDING ops of other unfinished runs as FAILED("interrupted").
- `core/data/.../repository/RunDraftRepository.writeRetryDrafts` derives
  type/season/title from the recorded target segments instead of forcing
  `MOVIE`/`""`.
- **Tests:** `executeResumesPerOperationSkippingDoneOps`.

## M-C — No UNDONE operation state

- `core/domain/.../journal/Journal.kt`: `OperationState.UNDONE` added;
  `UndoExecutor` marks each op UNDONE as it is restored and skips UNDONE ops
  on later attempts (stored as the enum name — no DB migration needed).
- **Tests:** `undoTwiceIsACleanNoOp`, `partialUndoThenSecondUndoCompletesTheRest`.

## M-D — Metadata gaps (6 sub-items)

- **Circuit breaker:** `core/data/.../metadata/MetadataService.kt` stops
  live lookups after 3 consecutive network failures (a provider answer
  resets the count), so offline scans reach the preview quickly.
- **Re-enrichment:** `app/.../orchestration/OrganizeSession.kt`
  `refreshSettingsAndReplan` enriches when the metadata-relevant settings
  signature changed, not only when `metadataByKey.isEmpty()`.
- **Overrides before queries:** persisted title overrides are applied before
  metadata query keys are built, so corrected items get decorated.
- **Provider fallback:** a TMDB miss/outage falls back to OMDb when an OMDb
  key exists (and the rating still honours `ratingSource`).
- **Cache:** the cache key includes `ratingSource` (and genre-normalization
  setting); degraded results (fallback/poster-only hits) expire after 24 h
  instead of the 30-day success TTL.
- **Similarity:** exact normalized equality outranks subset matches in both
  directions (`TitleSimilarity.kt`), and `MetadataMatcher` prefers the exact
  match over a more popular subset match.
- **Tests:** MetadataServiceTest — `tmdb miss falls back to omdb when both
  keys exist`, `tmdb outage falls back to omdb when both keys exist`,
  `circuit breaker opens after three consecutive network failures`,
  `a provider answer resets the breaker failure count`,
  `degraded hits expire after a day while full hits survive`,
  `cache key separates rating source preferences`;
  TitleSimilarityTest — `subsetMatchScoresBelowExactInEitherDirection`,
  `moreSpecificQueryDoesNotPerfectlyMatchSubsetCandidate`;
  MetadataMatcherTest — `exactTitleBeatsMorePopularSubsetMatch`.

## M-E — In-app updater

- `app/.../feature/update/AppUpdate.kt`: `verifySameSigner` /
  `getPackageArchiveInfo` run on `Dispatchers.IO`; error messages are
  localized string resources (EN+FA) instead of hard-coded English or raw
  exception text.
- Opt-out: `AppSettings.autoCheckUpdates` (default on) in
  `core/data/.../settings/AppSettings.kt` + `SettingsRepository` mapping +
  a Settings → About switch (`SettingsScreen.kt`); `CineSortNavHost` gates
  the on-launch check on it.
- `core/data/.../update/AppUpdateChecker.kt`: `httpGet` body reads are
  size-bounded; the checksum-mismatch message speaks of a corrupted or
  incomplete download instead of "tampered".
- Tag↔version loop closed in CI (see M-F: the release job fails when the
  tag disagrees with `versionName`).
- **Tests:** `AppUpdateCheckerTest` (6: `VersionNumbers.parse/isNewer`,
  `isAllowedUpdateRedirectHost` exact-host matching) — the pure functions
  §3 of the verification flagged as testable-but-untested.

## M-F — CI/release pipeline

- `.github/workflows/build.yml` (mirrored at `~/workspace/cinesort/build-final.yml`):
  top-level `permissions: contents: read`; publishing moved to a separate
  `release` job (`needs: [build]`, tags only, `permissions: contents: write`)
  that downloads the `cinesort-release-apk` artifact from the build job.
- No `|| true` on `gh release create` (`gh release view … || gh release
  create …`; `--clobber` upload kept intentionally for re-runs).
- Tag↔`versionName` gate in the release job (extracted from
  `app/build.gradle.kts`, job fails on mismatch).
- Vazirmatn font downloads verified with `sha256sum -c` against the local
  tree's digests (regular `b69fd4c6…`, medium `b986623e…`, bold `f635fdbe…`).
- `./gradlew assembleRelease` runs in the build job's main stage on every
  build (R8 exercised without secrets), plus
  `./gradlew :core:data:assembleDebugAndroidTest` as the M-G compile gate.

## M-G — Room migrations untested

- New `core/data/src/androidTest/.../db/CineSortMigrationTest.kt`: builds a
  v1 database from hand-written v1 DDL (current entities minus the v2/v3/v4
  additions), seeds it including duplicate `source_folders` rows, then opens
  it through Room with `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` —
  Room's own schema validation runs on open — and asserts the v2/v3/v4
  columns, the unique `index_source_folders_uriString`, `user_version = 4`,
  and that seeded rows survived with duplicates collapsed.
- Deps: `androidTestImplementation` for `androidx.room:room-testing`
  (already in the catalog) and androidx.test core/runner/ext-junit (new
  catalog entries `androidxTest = 1.6.1`, `androidxTestExtJunit = 1.2.1`);
  `testInstrumentationRunner` set in `core/data/build.gradle.kts`.
- `room.schemaLocation` already pointed at `$projectDir/schemas`
  (build.gradle.kts:30) — verified, left unchanged.
- Local gate note: the sandbox has no Android test classpath, so the file
  received a kotlinc syntax check only (all diagnostics were unresolved
  androidx references); the CI step above is the compile gate.

## M-H — Partially-fixed residuals

- **H6 residual:** `core/parser/.../AnchorMediaNameParser.kt` domain-prefix
  stripping restricted to `www.`-prefixed names or a domain token followed
  by a spaced dash — `The.Net.Of.Lies.2020` keeps "The Net Of Lies".
  Tests: `domainLikeTitleIsKept`, `domainStripIsRestrictedToWwwOrSpacedDash`.
- **H4 residual:** `core/data/.../saf/SafStorageGateway.kt` builds the
  sibling index once instead of `indexOfFirst` per file (O(n²) → O(n)).
- **M9 residual:** `OrganizeSession` shared fields are `@Volatile` and
  mutations are guarded by the session's replan mutex.
- **M14 residual:** `S01E01-02` parses as an episode range.
  Tests: `hyphenatedEpisodeForms`, `episodeRangeWithoutSecondE`.

## M-I — Debounced API-key save could be lost

- `app/.../settings/SettingsViewModel.kt`,
  `app/.../onboarding/OnboardingViewModel.kt`: pending debounced key writes
  are flushed from `onCleared()` on the application-scoped coroutine scope
  (`@ApplicationScope` in `DataModule`, which outlives `viewModelScope`),
  and flushed before "Test key"/scan actions read the store.

## L-A — `unifyFolderNames` split series folders on the year hint

- `core/domain/.../planner/Planner.kt`: the unification key no longer
  includes the series year hint; the unified folder uses the group's
  canonical (first non-null) hint when "add year to series folder" is on.
- **Test:** `seriesYearHintDoesNotSplitSeriesFolder`.

## L-B — Documentation drift

- `docs/DECISIONS.md`: the GitHub-distribution passage now describes the
  v1.0.4+ truth — full-bleed artwork in `app/art/launcher-art.b64`, PNG
  mipmaps generated by the CI JDK step, aperture vector drawables retired
  (the README sub-claim was rejected during verification: the README
  already named update checks; unchanged).
- Master prompt §14: permission list gains `REQUEST_INSTALL_PACKAGES`
  (in-app updater), and the allowed-hosts list gains `api.github.com` and
  GitHub's release-asset hosts, noting the Settings opt-out toggle.

## L-C — `dataSync` foreground-service risks

- `OrganizeWorker` guards its foreground promotion (start-from-background /
  `dataSync` timeout failures no longer crash the run start); the platform
  behaviour itself remains device-dependent, as the verification noted.

## L-D — Toolchain baseline — **accepted, no change**

AGP 8.7.3 / Kotlin 2.0.21 / targetSdk 35 remain the deliberate pinned
baseline (owner decision); revisit before any store release.

## L-E — Default language English — **accepted, no change**

Owner decision (v1.0.1): English is the fresh-install default; the
onboarding language step offers Persian.

## L-F — Scan deleted stale `.cinesort-part` files

- `SafStorageGateway` scans are read-only now; stale-part cleanup moved to
  `OrganizeWorker`, which removes `*.cinesort-part` leftovers under the
  destination root at run start only.
