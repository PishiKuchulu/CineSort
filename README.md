# 🎬 CineSort / سینه‌سورت

**Your downloads folder is a mess. CineSort turns it into a cinema.**  
**پوشه دانلودهای شما یک کابوسه؟ سینه‌سورت اون رو به یک سینمای شخصی تبدیل می‌کنه!**

---

## 🇬🇧 English

CineSort is an offline-first Android app that acts as your personal media librarian. It scans your chaotic download folders, intelligently parses scene-style release names (e.g., `The.Dark.Knight.2008.2160p.UHD.BluRay.Remux.mkv`), shows you a precise dry-run preview, and seamlessly organizes your library.

> 🔒 **The Golden Rule:** CineSort **never renames your media files**. It only moves them. Your carefully kept file names survive — always.

### ✨ Key Features
* 🧠 **Smart Parser:** Reads years, seasons, episodes, quality tags, and codecs like a human. Unrecognized files are safely marked as *Needs review*.
* 👀 **Safe Preview:** A full dry-run with per-file targets. Nothing touches your files until you confirm.
* ↩️ **Undo Journal:** Every run is logged. Changed your mind? Undo restores files to their original folders, even if the app was closed mid-run.
* 📦 **Companion Files:** Subtitles and sidecar files automatically travel with their matching videos.
* 🖼️ **Metadata & Posters:** (Optional) Use a TMDB/OMDb API key to fetch ratings, genres, and auto-save `poster.jpg` directly into the movie folders!
* 🌐 **Fully Bilingual:** Beautiful RTL support, Persian digits, and Jalali dates.
* 🎨 **Atelier Theme:** A warm, distraction-free paper-and-ink design (Light & Dark mode).
* 🛜 **100% Offline-First:** The core scan → preview → organize flow needs absolutely no internet.
* 🔄 **Seamless Updates:** Built-in updater downloads and installs signed APKs directly from this repo.

### 📂 How it organizes
```text
<Destination>/
├── Movie/
│   └── The Dark Knight (2008) (Action, Crime) 9.0/
│       └── The.Dark.Knight.2008.2160p.UHD.BluRay.Remux.mkv
└── Tv series/
    └── Dexter Resurrection/
        └── S02/
            └── Dexter.Resurrection.S02E01.1080p.WEB-DL.x265.mkv
```

### 📲 Installation
1. Go to the [Releases](https://github.com/PishiKuchulu/CineSort/releases) page.
2. Download the latest `CineSort-<version>.apk`.
3. Install it (Requires Android 7.0+).
4. Pick a source folder, pick a destination, preview, and organize! 🍿

---

## 🇮🇷 فارسی

**سینه‌سورت** یک اپلیکیشن اندرویدی آفلاین‌محور است که مثل یک کتابدار هوشمند عمل می‌کند. این برنامه پوشه‌های شلوغ دانلود شما را اسکن می‌کند، نام‌های پیچیده‌ی ریلیز (مثل `Dexter.Resurrection.S02E01...`) را می‌فهمد، یک پیش‌نمایش دقیق به شما نشان می‌دهد و در نهایت فیلم‌ها و سریال‌ها را در یک کتابخانه‌ی منظم و تمیز دسته‌بندی می‌کند.

> 🔒 **قانون طلایی:** سینه‌سورت **هرگز نام فایل‌های مدیای شما را تغییر نمی‌دهد**. فقط آن‌ها را جابه‌جا می‌کند تا نام‌های با دقت انتخاب شده‌ی شما همیشه دست‌نخورده باقی بمانند.

### ✨ ویژگی‌های کلیدی
* 🧠 **پارسر هوشمند:** سال ساخت، فصل، قسمت، کیفیت، کدک و گروه ریلیز را مثل یک انسان درک می‌کند. فایل‌های مبهم برای بررسی دستی به بخش *Needs review* می‌روند.
* 👀 **پیش‌نمایش امن:** قبل از جابه‌جایی هر فایلی، مقصد دقیق آن را می‌بینید. تا تایید نکنید، هیچ فایلی جابه‌جا نمی‌شود.
* ↩️ **ژورنال بازگشت (Undo):** تک‌تک عملیات‌ها ثبت می‌شوند. پشیمان شدید؟ با یک کلیک همه‌چیز را به حالت اول برگردانید (حتی اگر برنامه را بسته باشید!).
* 📦 **فایل‌های همراه:** زیرنویس‌ها و فایل‌های ضمیمه به صورت خودکار با فیلم مربوطه جابه‌جا می‌شوند.
* 🖼️ **پوستر و اطلاعات (اختیاری):** با وارد کردن کلید API رایگان از TMDB یا OMDb، امتیاز، ژانر و پوستر (`poster.jpg`) را مستقیماً داخل پوشه‌ی هر فیلم ذخیره کنید تا گالری پلیر شما زیباتر شود!
* 🌐 **کاملاً دوزبانه:** پشتیبانی بی‌نقص از زبان فارسی، راست‌چین (RTL)، اعداد فارسی و تاریخ شمسی.
* 🎨 **تم Atelier:** طراحی گرم، مینیمال و چشم‌نواز با حالت‌های روشن و تاریک.
* 🛜 **۱۰۰٪ آفلاین:** برای مرتب‌سازی فایل‌ها به هیچ اینترنتی نیاز ندارید.
* 🔄 **آپدیت خودکار:** بررسی نسخه‌های جدید و نصب آپدیت‌ها به صورت مستقیم و امن از داخل خود برنامه.

### 📲 نصب و راه‌اندازی
۱. به بخش [Releases](https://github.com/PishiKuchulu/CineSort/releases) در همین صفحه بروید.
۲. آخرین نسخه (`CineSort-<version>.apk`) را دانلود کنید.
۳. اپلیکیشن را نصب کنید (نیازمند اندروید ۷.۰ به بالا).
۴. پوشه مبدأ و مقصد را انتخاب کرده، پیش‌نمایش را تایید کنید و لذت ببرید! 🍿

---

**Developed with ❤️ by Farzad Sholeh**
