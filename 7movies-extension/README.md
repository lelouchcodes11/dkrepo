# 7Movies CloudStream Extension

A [CloudStream](https://github.com/recloudstream/cloudstream) provider for **7movies.ac**
(movies + TV shows, TMDB-backed catalog).

Built against CloudStream **4.8.0** (`com.github.recloudstream:cloudstream:4.8.0`).

## Features

- Home rows: Movies, TV Shows and genre rows via `/api/discover`
- Search via `/api/search?q=`
- Detail pages with posters, backdrop, cast, recommendations and per-season episodes
- **Direct playback**: uses the site's own vidrift pipeline (no external extractor needed)
- Fallback to the site's legacy embed players through `loadExtractor` (only useful if you
  have extractor packs installed)
- Subtitles from the vidrift source response are forwarded to CloudStream

## How playback works

The site's web player uses a short-lived token plus a player-side API. The provider mirrors it:

1. `POST https://7movies.ac/api/playback-token`
   body `{tmdbId, type, season, episode}` with `Referer`/`Origin: https://7movies.ac`
   (mints a token; 403 without those headers)
2. `GET https://embed.vidrift.net/api/source/{movie/{id} | tv/{id}/{season}/{episode}}?token=..&provider=orion&parent=7movies.ac`
   for providers `orion`, `titan`, `lyra`, `vela`, `atlas`
3. Each returned stream (`url` or `proxyUrl`) is emitted as an HLS `ExtractorLink` with
   `Referer: https://embed.vidrift.net/` (required by the CDN)

Orion usually carries the multi-audio 1080p (HEVC) copies; the remaining providers act as
backups.

## Build

Requirements: JDK 17+ (JDK 21 recommended), Android SDK with platform **36** and build-tools 36.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :provider:assembleDebug
```

Output: `provider/build/outputs/apk/debug/provider-debug.apk`

The debug APK is signed with the local debug key, so it installs directly:

```powershell
adb install -r provider/build/outputs/apk/debug/provider-debug.apk
```

Or copy the APK to the device and use **CloudStream -> Settings -> Extensions -> Install from file**.

## Project layout

```
build.gradle.kts                  AGP 9.1.1, repositories
settings.gradle.kts               includes :provider
gradle.properties                 AndroidX / JVM args
local.properties                  sdk.dir (not committed)
provider/
  build.gradle.kts                application module, compileSdk 36, cs 4.8.0
  gradle.properties               extension metadata (name/id/version)
  src/main/AndroidManifest.xml    minimal manifest (extensions are plain APKs)
  src/main/resources/manifest.json
                                  plugin manifest read by CloudStream at load time
  src/main/kotlin/com/sevenmovies/extension/SevenMovies.kt
                                  plugin entry point + provider + JSON models
```

## Site API reference

| Endpoint | Purpose |
|---|---|
| `GET /api/search?q=` | search |
| `GET /api/discover?type=movie\|tv&page=&genre=` | home rows (`genre` is a TMDB genre id) |
| `GET /api/detail?id=&type=movie\|tv` | metadata, credits, recommendations, seasons |
| `GET /api/tv/{id}/season/{n}` | episode list |
| `POST /api/playback-token` | playback token (requires 7movies.ac referer/origin) |
| `GET embed.vidrift.net/api/source/...` | HLS streams + subtitles |

All ids are plain TMDB ids, which is why posters come from `image.tmdb.org`.

## Notes

- CloudStream 4.8 reads episode dates as **unix milliseconds**; `NextAiring` still uses seconds.
- The extension uses CloudStream's built-in Kotlin support (AGP 9), so there is no separate
  `org.jetbrains.kotlin.android` plugin.
- `library-jvm` is excluded from the dependency because the `library-android` AAR already
  contains those classes.
