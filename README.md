# 🎬 DK Repo — CloudStream Extensions

A fast, reliable [CloudStream 3](https://github.com/recloudstream/cloudstream) extension repository maintained by **Lelouch**.

<p align="left">
  <a href="cloudstreamrepo://https://raw.githubusercontent.com/lelouchcodes11/dkrepo/builds/repo.json">
    <img src="https://img.shields.io/badge/📲%20Add%20to-CloudStream-6200EE?style=for-the-badge&logo=android&logoColor=white" alt="Add to CloudStream" />
  </a>
  <a href="https://github.com/recloudstream/cloudstream">
    <img src="https://img.shields.io/badge/CloudStream-v4.8.0+-blue?style=for-the-badge&logo=android" alt="CloudStream v4.8.0+" />
  </a>
  <img src="https://img.shields.io/badge/Status-Active-brightgreen?style=for-the-badge" alt="Status Active" />
</p>

---

## ⚡ How to Add to CloudStream

### Option 1: 🚀 1-Click Install (Android Phone / Tablet / TV)

If you are browsing this on an Android device with CloudStream installed, tap the button below or click the link:

👉 **[📲 Click Here to Add DK Repo to CloudStream](cloudstreamrepo://https://raw.githubusercontent.com/lelouchcodes11/dkrepo/builds/repo.json)**

*(Backup link: [Add via main branch](cloudstreamrepo://https://raw.githubusercontent.com/lelouchcodes11/dkrepo/main/repo.json))*

---

### Option 2: 📋 Manual In-App Setup (Copy & Paste)

1. **Copy** this repository URL:
   ```text
   https://raw.githubusercontent.com/lelouchcodes11/dkrepo/builds/repo.json
   ```
2. Open **CloudStream** on your device.
3. Go to **Settings** (⚙️) ➡️ **Extensions** ➡️ **Add Repository** (or tap the `+` button in the top right).
4. Enter the repository details:
   - **Repository Name**: `DK Repo`
   - **Repository URL**: `https://raw.githubusercontent.com/lelouchcodes11/dkrepo/builds/repo.json`
5. Tap **Download** / **Add**.
6. In the **Extensions** list under **DK Repo**, click **Install** next to **7Movies**.

---

### Option 3: 📦 Manual File / Offline Install

If you prefer to install without adding the repository index, download the compiled extension package directly:

- 📥 **[Download SevenMovies.cs3](https://raw.githubusercontent.com/lelouchcodes11/dkrepo/builds/SevenMovies.cs3)**

**To install the downloaded file:**
1. Open CloudStream ➡️ **Settings** ➡️ **Extensions**.
2. Tap the menu (**⋮**) in the top-right corner.
3. Select **Install from file** and choose `SevenMovies.cs3`.
4. *(Or push directly via ADB: `adb install -r SevenMovies.cs3`)*

---

## 🧩 Available Extensions

| Extension | Content Types | Playback Features | Status |
|---|---|---|---|
| **7Movies** (`com.sevenmovies.extension`) | 🎬 Movies & 📺 TV Shows | Direct HLS streaming via **Vidrift** (Orion 1080p HEVC, Titan, Lyra, Vexon, Evion) + Multi-audio + Subtitles | 🟢 **Active / Working** |
| **Cno** (`com.cnoto.extension`) | 🎬 Movies & 📺 TV Shows | Direct 4K / 1080p / 720p HLS streaming (Server 21), multi-language subtitles (.vtt), full seasons/episodes catalog | 🟢 **Active / Working** |

### ✨ Cno (Cno.to) Highlights:
- **Clean REST API**: Instant search, paginated browse by genres & trending, and rich home shelves.
- **Direct 4K / HD HLS Streaming**: Native master playlist parsing (4K 2160p, 1080p, 720p, 480p, 360p) with zero ad gates.
- **Multi-Language Subtitles**: Direct WebVTT subtitles with Closed Captions [CC] from `/api/subs/list/...`.
- **Complete Seasons & Episodes**: Full episode lists with runtimes, overviews, ratings, and stills.

### ✨ 7Movies Highlights:
- **Rich TMDB Catalog**: Complete metadata, posters, backdrops, cast, ratings, and episode lists.
- **Direct Playback**: Uses site-direct vidrift token minting — zero third-party extractor dependencies needed.
- **Subtitles**: Full WebVTT/SRT subtitles automatically forwarded to CloudStream player.
- **High-Speed Streams**: Orion provider serves 1080p HEVC streams with multi-audio tracks and fallback mirrors.

---

## 🛠️ Project Structure & Building

```text
dkrepo/
├── repo.json                   # CloudStream repository index
├── plugins.json                # Extension list & checksums
├── SevenMovies.cs3             # Prebuilt extension package
├── 7movies-extension/          # Source code & Gradle project
│   ├── provider/               # Kotlin extension module
│   │   ├── src/main/...        # Extension implementation
│   │   └── build.gradle.kts    # AGP 9.1.1 + CloudStream 4.8.0 API
│   ├── build.gradle.kts        # Root build configuration
│   └── gradlew                 # Gradle wrapper
└── .github/workflows/build.yml # CI/CD automated build workflow
```

### Build from Source

Requirements: **JDK 17+** (JDK 21 recommended) and **Android SDK 36**.

```powershell
cd 7movies-extension
.\gradlew.bat :provider:assembleDebug
```

Built package output: `7movies-extension/provider/build/outputs/apk/debug/provider-debug.apk`

---

## ⚖️ Disclaimer

These extensions act as client scrapers for publicly available websites. No video streams or copyrighted media files are hosted, stored, or distributed on this repository. All playback streams originate directly from the third-party website servers.
