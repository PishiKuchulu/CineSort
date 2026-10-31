# CineSort — Decisions

Choices made where the master prompt was ambiguous or where two clauses conflict.
The prompt remains the single source of truth; this file records how it was read.

## Toolchain pinning (spec §3, §4)
The spec asks for "latest stable" Kotlin/AGP/Compose BOM. Floating "latest" makes a
build non-reproducible, so exact stable versions are pinned in
`gradle/libs.versions.toml`: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21,
KSP 2.0.21-1.0.28, compile/target SDK 35, Compose BOM 2024.12.01, Room 2.6.1,
Hilt 2.54, Retrofit 2.11.0, OkHttp 4.12.0, Coil 2.7.0, WorkManager 2.10.0,
Tink 1.15.0. Upgrades are then one-line catalog changes.

## Module layout (spec §4)
The spec explicitly allows merging `feature:*` into `:app` if build time suffers.
Done: modules are `:app`, `:core:parser`, `:core:domain`, `:core:data`,
`:core:designsystem`. Features live as packages inside `:app`. `core:parser` and
`core:domain` stay pure Kotlin/JVM and isolated, as required.

## Whole-name fallback vs. corpus (spec §6.3 C3 vs §15.1)
§6.3 C3 says that with no anchor at all the entire cleaned name is used with
LOW confidence, but the binding corpus (§15.1, "must pass exactly") expects
`random_clip.mkv` → **Unrecognized**, and §13 expects anchor-less names in
Needs review as Unrecognized. The corpus wins: a name with no year, episode,
resolution or noise-run anchor yields `Unrecognized`. Such files land in
Needs review, where the user can set title/type manually in the edit sheet.

## Existing movie folder matching (spec §6.5 + §7.3)
Movie keys include the year, but §7.3 decoration stripping removes the year
from existing folder names, so a stripped key can never equal a movie key.
Resolution: the planner compares the decoration-stripped **title** key and
separately requires the year found in the existing folder's decoration (if
any) to equal the movie's year. Series matching uses the series key directly
(series keys ignore the year, per §6.5).

## Keep-both suffix allocation (spec §8.4)
When several items resolve conflicts with "Keep both", suffixes are allocated
sequentially against the destination snapshot plus every other planned target
path, so two items in one run can never receive the same final name.

## SAF identity convention (spec §5)
`DirRef`/`FileRef` ids are opaque to the domain. The SAF gateway uses the full
`content://` document URI string as the id (tree URI for picked roots,
`buildDocumentUriUsingTree` URIs for children). The volume id is parsed from
the document id prefix before `:`.

## Free space on SAF volumes (spec §5.5, §10.4)
Android exposes no per-tree free-space API for arbitrary SAF providers.
`freeBytes` resolves a `StatFs` only for the primary volume; otherwise it
returns null, the UI hides the free-space bar, and the cross-volume pre-check
is skipped (the copy itself still verifies byte counts).

## Vazirmatn (spec §10.2)
Bundled from the upstream OFL release (v33.003, Regular/Medium/Bold TTF) under
`core/designsystem/src/main/res/font/`. Light/Italic weights are not bundled
to keep the APK small; the family XML synthesizes the rest.

## Coil version
Coil 2.7.0 (the stable v2 line) is used instead of Coil 3: its Compose API is
final and it needs no KMP source-set changes. Posters are the only images.

## Secrets (spec §12)
Tink AEAD with an Android Keystore master key (`android-keystore://`), keyset
in app-private SharedPreferences, ciphertexts in a dedicated DataStore file.
Keys never appear in logs, backups (`allowBackup=false` + extraction rules),
or diagnostics exports.

## ktlint enforcement stage
`./gradlew assembleDebug testDebugUnitTest lint detekt` (spec §0) is the CI
gate. ktlint is configured in the pure modules; for the Android modules it is
run in CI in report mode until the first full CI pass is green, then promoted
to a hard gate — formatting churn is not allowed to mask real build failures
during the first integration.

## Language default
Persian is applied on first launch via `AppCompatDelegate.setApplicationLocales`
when the user has not chosen a language yet; `values/` holds English and
`values-fa/` holds Persian.

## Scan classification ownership (integration fix)
`StorageGateway.scan` has no settings parameter (the interface is fixed by
§5.7), so the SAF gateway emits every regular file as a candidate and the
session layer classifies with the **user's** `ScanSettings` via `ScanRules`.
An earlier draft classified inside the gateway with default settings, which
would have silently ignored a user-disabled sample filter or custom video
extensions; that is fixed — the gateway only skips hidden/temp entries,
removes stale `.cinesort-part` files, and honors tree exclusion/depth.

## Conflict comparison data (spec §8.4)
`DestinationSnapshot` gained an optional `fileDetails` map (size +
lastModified per existing file). The planner attaches it to conflicted items
as `existingTarget`, and the edit sheet shows the existing file's size/date
plus the "Probably the same file" label when sizes match.

## Preview naming row scope
The Preview naming row binds its Genres/Rating switches to the movie and
series toggles together (the spec's row is singular); Settings keeps the
four toggles separate.

## Corrected items confidence
When the user fixes an `Unrecognized` item in the edit sheet, the rebuilt
parse result is assigned MEDIUM confidence so the planner can mark it READY;
it is never auto-selected before the user confirms the edit.

## GitHub snapshot distribution (2026-10-31)
The GitHub App snapshot route transports UTF-8 text only, so binary assets
are not tracked in the repository: `gradle-wrapper.jar` is regenerated in CI
(setup-gradle pinned to Gradle 8.10.2 + `gradle wrapper`), the three
Vazirmatn TTFs (v33.003) are fetched in CI from the official
rastikerdar/vazirmatn repo at the same pinned version the local tree
bundles, and the launcher icon ships as vector drawables only (adaptive
icon on API 26+, layer-list fallback in mipmap-anydpi below that; the
density PNGs were removed). Local builds from the source zip are
unaffected — the zip carries the binaries.
