# Cno (cno.to) CloudStream Extension

A fast, reliable [CloudStream](https://github.com/recloudstream/cloudstream) provider for **Cno.to**
(movies & TV shows with direct 4K/HD HLS streams).

Built against CloudStream **4.8.0** (`com.github.recloudstream:cloudstream:4.8.0`).

## Features

- **Home Shelves**: Curated rows ("Top 10 Movies Today", "Top 10 Shows Today", "New in Theaters", "Popular Shows", "Trending", "Sci-Fi", "Animation", etc.) via `/api/home`
- **Paginated Catalog**: Browse by media type, sort, and genres via `/api/browse`
- **Instant Search**: Fast full-text search via `/api/search?q=`
- **Rich Metadata**: TMDB-backed metadata, posters, backdrops, cast, ratings, and recommendations
- **Direct 4K / HD Playback**: Parses the site's native HLS master playlist (Server 21, etc.) into discrete qualities (4K 2160p, 1080p, 720p, 480p, 360p, Auto)
- **Multi-Language Subtitles**: Direct WebVTT subtitles with Closed Captions [CC] from `/api/subs/list/...`
- **Complete Seasons & Episodes**: Multi-season TV shows with episode titles, overviews, runtimes, still images, and air dates
- **Fallback Embed Players**: Supports site embed fallbacks via `loadExtractor`

## Build from Source

Requirements: JDK 17+ (JDK 21 recommended), Android SDK with platform **34+**.

```powershell
.\gradlew.bat :provider:assembleDebug
```

Built package output: `provider/build/outputs/apk/debug/provider-debug.apk` (renamed to `CnoTo.cs3`).
