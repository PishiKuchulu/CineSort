# 🎬 CineSort

**Your downloads folder is a mess. CineSort turns it into a cinema.**

CineSort is an offline-first Android app that takes a folder full of chaotic,
release-style file names —

```
Dexter.Resurrection.S02E01.1080p.WEB-DL.x265.HEVC.mkv
The.Dark.Knight.2008.2160p.UHD.BluRay.Remux.mkv
```

— understands them with a smart anchor-based parser, shows you a complete
dry-run preview, and then **moves** every file into a clean library:

```
<Destination>/
├── Movie/
│   └── The Dark Knight (2008) (Action, Crime) 9.0/
│       └── The.Dark.Knight.2008.2160p.UHD.BluRay.Remux.mkv   ← name untouched
└── Tv series/
    └── Dexter Resurrection/
        └── S02/
            └── Dexter.Resurrection.S02E01.1080p.WEB-DL.x265.HEVC.mkv
```

> 🔒 **The golden rule:** CineSort **never renames your media files.** It only
> moves them. Your carefully kept file names survive — always.

---

## ✨ Features

- 🧠 **Release-name parser** — years, seasons, episode ranges, quality tags,
  codecs, languages, group names: CineSort reads scene-style names like a
  human librarian, and marks anything unsure as *Needs review* instead of
  guessing.
- 👀 **Preview before anything moves** — a full dry run with per-file targets,
  conflicts and warnings. Nothing touches your files until you confirm.
- ↩️ **Undo with a journal** — every run is journaled operation-by-operation.
  Changed your mind? Undo restores files to their original folders, even after
  the app was closed mid-run.
- 📦 **Companion files travel along** — subtitles and sidecar files sitting
  next to a video move with it.
- 🖼️ **Poster download** *(optional)* — with a metadata key, CineSort can save
  each title's poster as `poster.jpg` right inside its folder, so media
  players and file managers show real artwork for your library.
- 🌐 **Fully bilingual** — English and Persian (فارسی) with full RTL support,
  Persian digits and Jalali dates when you want them.
- 🎨 **Atelier theme** — a warm, paper-and-ink design in light and dark.
- 🔄 **Built-in updates** — the app checks this repo's Releases and installs
  signed updates in place. Same signature, always — no reinstall, no data loss.
- 🛜 **Offline-first** — the core scan → preview → organize flow needs **no
  internet at all**. The network is used only for the optional metadata lookup
  and app updates.

## 🏷️ Folder names with genre & rating — read this!

Out of the box, CineSort names folders from what is already in the file name
(title + year). If you want folders enriched like this:

```
Movie/The Dark Knight (2008) (Action, Crime) 9.0/
```

…then the **genre** and **rating** have to come from a movie database — and
that needs a free API key **from TMDB or OMDb**:

| Service | Get a key | Cost |
|---|---|---|
| **TMDB** | [themoviedb.org → Settings → API](https://www.themoviedb.org/settings/api) | Free |
| **OMDb** | [omdbapi.com/apikey.aspx](https://www.omdbapi.com/apikey.aspx) | Free tier |

Then in the app: **Settings → API keys** → paste your TMDB and/or OMDb key,
and switch on the genre/rating options in **Settings → Naming**.

- Keys are stored **encrypted on your device** (Android Keystore + Tink) and
  are sent only to TMDB/OMDb themselves — never anywhere else.
- With a key, you also unlock **poster download** (Settings → Metadata) and
  smarter title matching for the *Needs review* items.
- No key? No problem — everything else works completely offline.

## 📲 Install

1. Go to the [**Releases**](../../releases) page.
2. Download the latest `CineSort-<version>.apk`.
3. Install it (Android will ask to allow installs from your browser/files app).
4. Pick a source folder, pick a destination, preview, organize. Done. 🍿

Requires Android 7.0 (API 24) or newer.

## 🔐 Privacy

- No accounts, no analytics, no ads, no tracking.
- Your folders are accessed only through Android's Storage Access Framework —
  you pick exactly which folders the app may see.
- The only network calls the app can make: TMDB/OMDb metadata lookups (with
  *your* key) and update checks against this repository's Releases.

## 🛠️ Tech stack

- Kotlin + Jetpack Compose (Material 3), Hilt, Room, DataStore, WorkManager
- Pure-Kotlin parser/planner core (`:core:parser`, `:core:domain`) — fully
  unit-tested on the JVM
- SAF-based storage layer with verified copy-then-delete cross-volume moves

## 👨‍💻 Build from source

```bash
git clone https://github.com/PishiKuchulu/CineSort.git
cd CineSort
./gradlew assembleDebug
```

Release builds are signed in CI from repository secrets; local release builds
fall back to unsigned when the `CINESORT_KEYSTORE_*` environment variables are
absent.

---

## فارسی

**سینه‌سورت** دانلودهای شلوغ و نامرتب شما را به یک کتابخانه‌ی تمیز فیلم و
سریال تبدیل می‌کند — **بدون اینکه اسم هیچ فایلی را عوض کند.**

اپلیکیشن پوشه‌ی مبدأ را اسکن می‌کند، اسم‌های ریلیزی را می‌فهمد، قبل از هر
جابه‌جایی یک پیش‌نمایش کامل نشان می‌دهد و بعد فایل‌ها را به پوشه‌های مرتب
`Movie` و `Tv series` منتقل می‌کند. هر اجرا در ژورنال ثبت می‌شود و قابل
برگرداندن (Undo) است.

> 🏷️ **ژانر و امتیاز در نام پوشه‌ها:** اگر می‌خواهید ژانر و امتیاز فیلم داخل
> نام پوشه نوشته شود، باید یک کلید API رایگان از سایت **TMDB** یا **OMDb**
> بگیرید و در بخش «تنظیمات ← کلیدهای API» وارد کنید. بدون کلید هم اپلیکیشن
> کاملاً آفلاین کار می‌کند. با کلید، قابلیت «دانلود پوستر در پوشه‌ی هر فیلم و
> سریال» هم فعال می‌شود.

دانلود نسخه‌ی امضاشده از بخش **Releases** همین مخزن.

---

**Developed by Farzad Sholeh**
