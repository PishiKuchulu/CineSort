# CineSort

Offline-first Android app that turns a messy downloads folder into a clean
movie & series library — **without ever renaming a media file**.

CineSort scans one or more source folders you pick, understands release-style
file names (`Dexter.Resurrection.S02E01.1080p.WEB-DL.x265…mkv`) with an
anchor-based parser, shows you a full dry-run preview, and then moves each file
into:

```
<Destination>/
├── Movie/
│   └── Fall 2 Deadpoint (2026)/
│       └── Fall.2.Deadpoint.2026.1080p.… .mkv      ← name untouched
└── Tv series/
    └── Dexter Resurrection/
        └── S02/
            └── Dexter.Resurrection.S02E01.… .mkv
```

Optionally, movie folder names are decorated with genres and a rating from
TMDB and/or OMDb — with **your own** API keys; both are optional and the core
function works fully offline.

## Highlights

- **Never renames, never deletes** your media (the only exception is the
  explicit "Keep both" conflict choice, which suffixes the incoming file).
- **Dry-run first**: nothing moves before you confirm the preview.
- **Everything is undoable** via the operation journal (last 10 runs).
- **SAF only** — no broad storage permissions; you pick every folder.
- **Persian (RTL) by default**, English available; Jalali dates; Vazirmatn UI.
- **Private by design**: only a cleaned title + year are ever sent to metadata
  APIs. No analytics, no ads, no crash SDKs.

## Build

Requires JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew assembleDebug testDebugUnitTest lint detekt
```

CI (`.github/workflows/build.yml`) runs the same command and uploads the
debug APK.

## Project layout

| Module | Contents |
|---|---|
| `:core:parser` | Pure-Kotlin anchor-based filename parser (fully unit-tested against the spec corpus) |
| `:core:domain` | Pure-Kotlin planner, naming, executor, undo, journal port, in-memory storage fake |
| `:core:data` | SAF gateway, Room, DataStore settings, Tink-encrypted API keys, TMDB/OMDb, WorkManager worker |
| `:core:designsystem` | "Cinema Noir" Material 3 theme, tokens, shared components, Vazirmatn |
| `:app` | Single-activity Compose app: Onboarding, Home, Sources, Scan, Preview, Run, Report, History, Settings |

See `docs/ARCHITECTURE.md` for the pipeline and `docs/DECISIONS.md` for the
choices recorded where the spec left room.

## Metadata keys (optional)

- TMDB: https://www.themoviedb.org/settings/api (v3 key or v4 read token)
- OMDb: https://www.omdbapi.com/apikey.aspx

Enter them in Settings → Metadata. Without keys, genre/rating decoration is
disabled and everything else works.

## License

Fonts: Vazirmatn is licensed under the SIL Open Font License 1.1.
App source: all rights reserved by the developer unless a license file is
added later.
