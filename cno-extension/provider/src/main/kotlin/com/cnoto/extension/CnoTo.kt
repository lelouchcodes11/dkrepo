package com.cnoto.extension

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

@CloudstreamPlugin
class CnoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CnoTo())
    }
}

/**
 * CloudStream provider for Cno.to.
 *
 * Direct REST/JSON API:
 *   - Search: /api/search?q={query}
 *   - Browse: /api/browse?type={movie|tv}&page={page}&genre={genre}&sort={sort}
 *   - Home: /api/home
 *   - Movie Details & Sources: /api/movie/{tmdbId}
 *   - TV Details & Season List: /api/tv/{tmdbId}
 *   - Season Episodes: /api/tv/{tmdbId}/season/{seasonNumber}
 *   - Episode Details & Sources: /api/tv/{tmdbId}/episode/{season}/{episode}
 *   - Subtitles List: /api/subs/list/{movie|tv}/{tmdbId}[/{season}/{episode}]
 *   - Subtitle File: /api/subs/file/{path}/{fileId}.vtt
 *   - Direct HLS Streams: /api/s/{family}/movie/{tmdbId} (Server 20, Server 21, etc.)
 *   - Embedded Players: VidFast, CineSrc, etc.
 */
class CnoTo : MainAPI() {
    override var mainUrl = "https://cno.to"
    override var name = "Cno"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "en"

    companion object {
        private const val TAG = "CnoTo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        private const val ENC_DEC_API = "https://enc-dec.app/api"
    }

    private fun tmdbUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "https://image.tmdb.org/t/p/$size$path"
        }
    }

    private suspend fun apiGet(url: String): String? {
        return try {
            app.get(
                url,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "Accept" to "application/json"
                )
            ).text
        } catch (e: Exception) {
            Log.w(TAG, "apiGet failed for $url: ${e.message}")
            null
        }
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "utf-8")
        val json = AppUtils.tryParseJson<SearchResponseJson>(
            apiGet("$mainUrl/api/search?q=$encoded")
        ) ?: return emptyList()

        return json.results.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val tmdbId = id ?: return null
        val itemTitle = title ?: return null
        val isTv = type.equals("tv", ignoreCase = true)
        val target = if (isTv) "$mainUrl/show/$tmdbId" else "$mainUrl/movie/$tmdbId"
        val poster = tmdbUrl(poster, "w500")
        val scoreValue = Score.from(rating, 10)

        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, target, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year
                this.score = scoreValue
                this.id = tmdbId.toInt()
            }
        } else {
            newMovieSearchResponse(itemTitle, target, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year
                this.score = scoreValue
                this.id = tmdbId.toInt()
            }
        }
    }

    // ---------- Main Page ----------

    override val mainPage = mainPageOf(
        "home" to "Home",
        "movie;sort=trending" to "Trending Movies",
        "tv;sort=trending" to "Trending TV Shows",
        "movie;genre=28" to "Action Movies",
        "movie;genre=35" to "Comedy Movies",
        "movie;genre=27" to "Horror Movies",
        "movie;genre=878" to "Sci-Fi & Fantasy",
        "movie;genre=16" to "Animation",
        "tv;genre=18" to "Drama Series",
        "tv;genre=35" to "Comedy Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == "home" && page == 1) {
            val json = AppUtils.tryParseJson<HomeResponseJson>(apiGet("$mainUrl/api/home"))
            val list = mutableListOf<SearchResponse>()
            val seen = mutableSetOf<Long>()

            json?.hero?.forEach { item ->
                val id = item.id ?: return@forEach
                if (seen.add(id)) {
                    item.toSearchResponse()?.let { list.add(it) }
                }
            }

            json?.shelves?.forEach { shelf ->
                shelf.items?.forEach { item ->
                    val id = item.id ?: return@forEach
                    if (seen.add(id)) {
                        item.toSearchResponse()?.let { list.add(it) }
                    }
                }
            }

            return newHomePageResponse(request, list, hasNext = false)
        }

        // Browse catalog with pagination
        val parts = request.data.split(";")
        val mediaType = parts[0]
        val extraParams = parts.drop(1).joinToString("&")
        val url = buildString {
            append("$mainUrl/api/browse?type=$mediaType&page=$page")
            if (extraParams.isNotBlank()) append("&$extraParams")
        }

        val json = AppUtils.tryParseJson<BrowseResponseJson>(apiGet(url))
        val items = json?.results.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = json?.totalPages?.let { page < it } ?: false

        return newHomePageResponse(request, items, hasNext)
    }

    // ---------- Load Details ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            loadImpl(url)
        } catch (e: Exception) {
            Log.e(TAG, "load failed for $url: ${e.message}", e)
            null
        }
    }

    private suspend fun loadImpl(url: String): LoadResponse? {
        val clean = url.removePrefix(mainUrl).trimStart('/')
        val isTv = clean.startsWith("show") || clean.startsWith("tv") || clean.contains("/show/") || clean.contains("/tv/")
        val tmdbId = Regex("""\d+""").find(clean)?.value?.toLongOrNull() ?: return null

        Log.d(TAG, "loadImpl: url=$url, tmdbId=$tmdbId, isTv=$isTv")

        if (!isTv) {
            // Load Movie
            val json = AppUtils.tryParseJson<MovieDetailJson>(apiGet("$mainUrl/api/movie/$tmdbId"))
                ?: return null

            val title = json.title ?: "Untitled"
            val poster = tmdbUrl(json.poster, "w500")
            val backdrop = tmdbUrl(json.backdrop, "original")
            val scoreVal = Score.from(json.rating, 10)

            return newMovieLoadResponse(
                name = title,
                url = "$mainUrl/movie/$tmdbId",
                type = TvType.Movie,
                dataUrl = "$mainUrl/movie/$tmdbId",
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = json.overview
                this.year = json.year
                this.tags = json.genres
                this.score = scoreVal
                this.duration = json.runtime
                this.actors = json.cast?.mapNotNull { c ->
                    val name = c.name ?: return@mapNotNull null
                    ActorData(
                        Actor(name, tmdbUrl(c.photo, "w185")),
                        roleString = c.role
                    )
                }
                this.recommendations = json.recommendations?.mapNotNull { it.toSearchResponse() }
                this.comingSoon = json.date.isFutureDate()
            }
        } else {
            // Load TV Series
            val json = AppUtils.tryParseJson<TvDetailJson>(apiGet("$mainUrl/api/tv/$tmdbId"))
                ?: return null

            val title = json.title ?: "Untitled"
            val poster = tmdbUrl(json.poster, "w500")
            val backdrop = tmdbUrl(json.backdrop, "original")
            val scoreVal = Score.from(json.rating, 10)

            val validSeasons = json.seasonList?.filter { (it.number ?: 0) > 0 }.orEmpty()
            val episodes = validSeasons.flatMap { seasonItem ->
                val sNum = seasonItem.number ?: return@flatMap emptyList()
                val seasonJson = AppUtils.tryParseJson<SeasonDetailJson>(
                    apiGet("$mainUrl/api/tv/$tmdbId/season/$sNum")
                )
                seasonJson?.episodes?.map { ep ->
                    val epNum = ep.episode ?: 0
                    newEpisode(
                        url = "$tmdbId|${sNum}_$epNum",
                        initializer = {
                            this.name = ep.title
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = tmdbUrl(ep.still, "w300")
                            this.description = ep.overview
                            this.score = Score.from(ep.rating, 10)
                            this.runTime = ep.runtime?.times(60)
                            this.date = ep.date.toUnixMillis()
                        },
                        fix = false
                    )
                }.orEmpty()
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = "$mainUrl/show/$tmdbId",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = json.overview
                this.year = json.year
                this.tags = json.genres
                this.score = scoreVal
                this.actors = json.cast?.mapNotNull { c ->
                    val name = c.name ?: return@mapNotNull null
                    ActorData(
                        Actor(name, tmdbUrl(c.photo, "w185")),
                        roleString = c.role
                    )
                }
                this.recommendations = json.recommendations?.mapNotNull { it.toSearchResponse() }
            }
        }
    }

    // ---------- Load Links / Playback ----------

    private data class PlaybackTarget(
        val type: String, // "movie" or "tv"
        val tmdbId: Long,
        val season: Int,
        val episode: Int
    )

    private fun parsePlaybackData(data: String): PlaybackTarget? {
        val clean = data.removePrefix(mainUrl).trimStart('/')

        // 1. Pipe format: 1396|1_2 or tv/1396|1_2
        val matchPipe = Regex("""(?:tv/)?(\d+)\|(\d+)_(\d+)""").find(clean)
        if (matchPipe != null) {
            val id = matchPipe.groupValues[1].toLongOrNull() ?: return null
            val s = matchPipe.groupValues[2].toIntOrNull() ?: 1
            val e = matchPipe.groupValues[3].toIntOrNull() ?: 1
            return PlaybackTarget("tv", id, s, e)
        }

        // 2. TV path: tv/1396/1/2 or show/1396/1/2 or watch/show/1396/1/2
        val matchTvPath = Regex("""(?:watch/)?(?:show|tv)/(\d+)/(\d+)/(\d+)""").find(clean)
        if (matchTvPath != null) {
            val id = matchTvPath.groupValues[1].toLongOrNull() ?: return null
            val s = matchTvPath.groupValues[2].toIntOrNull() ?: 1
            val e = matchTvPath.groupValues[3].toIntOrNull() ?: 1
            return PlaybackTarget("tv", id, s, e)
        }

        // 3. Movie path: movie/19995 or watch/19995 or movie/19995-avatar or watch/19995-avatar
        val matchMovie = Regex("""(?:watch/|movie/)?(\d+)(?:-[a-z0-9\-]+)?""").find(clean)
        if (matchMovie != null) {
            val id = matchMovie.groupValues[1].toLongOrNull() ?: return null
            return PlaybackTarget("movie", id, 1, 1)
        }

        // 4. Pure digits
        if (clean.all { it.isDigit() }) {
            val id = clean.toLongOrNull() ?: return null
            return PlaybackTarget("movie", id, 1, 1)
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = parsePlaybackData(data)
        if (target == null) {
            Log.e(TAG, "loadLinks: Could not parse playback data: $data")
            return false
        }

        val isTv = target.type == "tv"
        Log.d(TAG, "loadLinks: target=$target (isTv=$isTv)")

        var foundLinks = false
        val seenStreamUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubUrls = Collections.synchronizedSet(mutableSetOf<String>())

        val sourcesUrl = if (isTv) {
            "$mainUrl/api/tv/${target.tmdbId}/episode/${target.season}/${target.episode}"
        } else {
            "$mainUrl/api/movie/${target.tmdbId}"
        }

        val subListUrl = if (isTv) {
            "$mainUrl/api/subs/list/tv/${target.tmdbId}/${target.season}/${target.episode}"
        } else {
            "$mainUrl/api/subs/list/movie/${target.tmdbId}"
        }

        val subFilePrefix = if (isTv) {
            "$mainUrl/api/subs/file/tv/${target.tmdbId}/${target.season}/${target.episode}"
        } else {
            "$mainUrl/api/subs/file/movie/${target.tmdbId}"
        }

        // 1. Fetch subtitles from Cno API (limited to prevent UI overload)
        try {
            val subJson = AppUtils.tryParseJson<SubtitlesListJson>(apiGet(subListUrl))
            val seenLangs = mutableSetOf<String>()
            subJson?.files?.forEach { sub ->
                val subId = sub.id ?: return@forEach
                val lang = sub.lang ?: "en"
                val hiKey = if (sub.hi == true) "$lang-hi" else lang
                // Only take top 2 tracks per language
                if (seenLangs.count { it.startsWith(hiKey) } < 2) {
                    seenLangs.add(hiKey + seenLangs.size)
                    val subUrl = "$subFilePrefix/$subId.vtt"
                    if (seenSubUrls.add(subUrl)) {
                        val langLabel = buildString {
                            append(sub.label ?: sub.lang ?: "English")
                            if (sub.hi == true) append(" [CC]")
                        }
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = langLabel,
                                url = subUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch subtitles: ${e.message}")
        }

        // 2. Fetch direct sources from Cno API
        val sources: List<SourceItem>? = try {
            if (isTv) {
                AppUtils.tryParseJson<EpisodeSourcesJson>(apiGet(sourcesUrl))?.sources
            } else {
                AppUtils.tryParseJson<MovieDetailJson>(apiGet(sourcesUrl))?.sources
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch sources from $sourcesUrl: ${e.message}")
            null
        }

        Log.d(TAG, "Received ${sources?.size ?: 0} sources from Cno API")

        for (source in sources.orEmpty()) {
            val rawUrl = source.url ?: continue
            val label = source.label ?: "Stream"

            if (source.kind.equals("hls", ignoreCase = true)) {
                val fullHlsUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    "$mainUrl$rawUrl"
                }

                Log.d(TAG, "Processing HLS source [$label]: $fullHlsUrl")

                try {
                    val playlistText = app.get(
                        fullHlsUrl,
                        referer = "$mainUrl/",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        )
                    ).text

                    if (playlistText.contains("#EXTM3U")) {
                        val lines = playlistText.lines()
                        for (i in lines.indices) {
                            val line = lines[i].trim()
                            if (line.startsWith("#EXT-X-STREAM-INF")) {
                                val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                                if (nextLine.isNotBlank() && !nextLine.startsWith("#")) {
                                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                                        .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        ?: 1080

                                    // Correctly resolve relative or absolute URL using URI.resolve
                                    val variantUrl = try {
                                        URI.create(fullHlsUrl).resolve(nextLine).toString()
                                    } catch (_: Exception) {
                                        if (nextLine.startsWith("http://") || nextLine.startsWith("https://")) {
                                            nextLine
                                        } else {
                                            "$mainUrl/$nextLine".replace("//", "/")
                                        }
                                    }

                                    if (seenStreamUrls.add(variantUrl)) {
                                        Log.d(TAG, "Emitting HLS variant: $label ${height}p -> $variantUrl")
                                        callback.invoke(
                                            newExtractorLink(
                                                source = "$name $label",
                                                name = "$name $label ${height}p",
                                                url = variantUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = "$mainUrl/"
                                                this.quality = heightToQuality(height)
                                                this.headers = mapOf(
                                                    "Referer" to "$mainUrl/",
                                                    "Origin" to mainUrl
                                                )
                                            }
                                        )
                                        foundLinks = true
                                    }
                                }
                            }
                        }

                        // Also emit the master playlist (Auto quality)
                        if (seenStreamUrls.add(fullHlsUrl)) {
                            Log.d(TAG, "Emitting HLS master: $label (Auto) -> $fullHlsUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = "$name $label",
                                    name = "$name $label (Auto)",
                                    url = fullHlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "Origin" to mainUrl
                                    )
                                }
                            )
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "HLS playlist parse failed: ${e.message}, falling back to direct master link")
                    if (seenStreamUrls.add(fullHlsUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name $label",
                                name = "$name $label (Auto)",
                                url = fullHlsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "Origin" to mainUrl
                                )
                            }
                        )
                        foundLinks = true
                    }
                }
            } else if (source.kind.equals("embed", ignoreCase = true)) {
                // If it's a VidFast embed, extract using our direct VidFast decryptor
                if (rawUrl.contains("vidfast")) {
                    Log.d(TAG, "Extracting VidFast embed: $rawUrl")
                    val ok = extractVidFast(rawUrl, subtitleCallback, callback)
                    if (ok) foundLinks = true
                } else {
                    // Other generic embed players
                    try {
                        val ok = loadExtractor(rawUrl, referer = "$mainUrl/", subtitleCallback, callback)
                        if (ok) foundLinks = true
                    } catch (e: Exception) {
                        Log.w(TAG, "loadExtractor failed for $rawUrl: ${e.message}")
                    }
                }
            }
        }

        // 3. Always attempt VidFast directly if TV or no direct HLS links found
        if (isTv || !foundLinks) {
            val vfUrl = if (isTv) {
                "https://vidfast.vc/tv/${target.tmdbId}/${target.season}/${target.episode}"
            } else {
                "https://vidfast.vc/movie/${target.tmdbId}"
            }
            Log.d(TAG, "Querying VidFast directly: $vfUrl")
            val vfOk = extractVidFast(vfUrl, subtitleCallback, callback)
            if (vfOk) foundLinks = true
        }

        Log.d(TAG, "loadLinks finished. foundLinks=$foundLinks")
        return foundLinks
    }

    // ---------- VidFast Extractor ----------

    private suspend fun extractVidFast(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageRes = app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text

            val match = Regex("""\\?"(?:en|token)\\?":\\?"(.*?)\\?""").find(pageRes)
            if (match == null) {
                Log.w(TAG, "VidFast: No token found in page $pageUrl")
                return false
            }
            val enToken = match.groupValues[1]

            // Stage 1
            val s1Url = "$ENC_DEC_API/enc-vidfast?text=${URLEncoder.encode(enToken, "utf-8")}&stage=1"
            val s1Text = app.get(s1Url).text
            val s1Json = AppUtils.tryParseJson<EncDecStage1Res>(s1Text)
            val parts1 = s1Json?.result
            val stage1Url = parts1?.stage1
            val token1 = parts1?.token

            if (stage1Url.isNullOrBlank() || token1.isNullOrBlank()) {
                Log.w(TAG, "VidFast: Stage 1 failed")
                return false
            }

            val apiHeaders = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://vidfast.vc/",
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-Token" to token1
            )

            val tText = app.post(stage1Url, headers = apiHeaders).text
            if (tText.isBlank()) {
                Log.w(TAG, "VidFast: Empty stage1 response")
                return false
            }

            // Stage 2
            val s2Url = "$ENC_DEC_API/enc-vidfast?text=${URLEncoder.encode(tText, "utf-8")}&stage=2"
            val s2Text = app.get(s2Url).text
            val s2Json = AppUtils.tryParseJson<EncDecStage2Res>(s2Text)
            val parts2 = s2Json?.result
            val serversUrl = parts2?.servers
            val streamBase = parts2?.stream
            val token2 = parts2?.token

            if (serversUrl.isNullOrBlank() || streamBase.isNullOrBlank() || token2.isNullOrBlank()) {
                Log.w(TAG, "VidFast: Stage 2 failed")
                return false
            }

            apiHeaders["X-CSRF-Token"] = token2

            val srvEnc = app.post(serversUrl, headers = apiHeaders).text
            if (srvEnc.isBlank()) {
                Log.w(TAG, "VidFast: Empty servers response")
                return false
            }

            // Decrypt servers list
            val escapedSrv = srvEnc.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
            val decSrvText = app.post(
                "$ENC_DEC_API/dec-vidfast",
                json = JsonAsString("""{"text":"$escapedSrv"}"""),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/json"
                )
            ).text
            val decSrvJson = AppUtils.tryParseJson<EncDecServersRes>(decSrvText)
            val servers = decSrvJson?.result.orEmpty()

            Log.d(TAG, "VidFast: Decrypted ${servers.size} streaming servers")

            var vidfastFound = false
            for (srv in servers) {
                val srvData = srv.data ?: continue
                val srvName = srv.name ?: "Stream"
                try {
                    val streamReqUrl = "$streamBase/$srvData"
                    val stEnc = app.post(streamReqUrl, headers = apiHeaders).text
                    if (stEnc.isBlank()) continue

                    val escapedSt = stEnc.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
                    val decStText = app.post(
                        "$ENC_DEC_API/dec-vidfast",
                        json = JsonAsString("""{"text":"$escapedSt"}"""),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Content-Type" to "application/json"
                        )
                    ).text
                    val decStJson = AppUtils.tryParseJson<EncDecStreamRes>(decStText)
                    val streamRes = decStJson?.result
                    val streamUrl = streamRes?.url

                    if (!streamUrl.isNullOrBlank()) {
                        Log.d(TAG, "VidFast [$srvName]: Found stream: $streamUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = "VidFast ($srvName)",
                                name = "VidFast $srvName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://vidfast.vc/"
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "Referer" to "https://vidfast.vc/"
                                )
                            }
                        )
                        vidfastFound = true

                        streamRes.tracks?.forEach { trk ->
                            val subUrl = trk.file ?: return@forEach
                            val subLabel = trk.label ?: trk.kind ?: "Subtitle"
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = subLabel,
                                    url = subUrl
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "VidFast: Failed server $srvName: ${e.message}")
                }
            }
            vidfastFound
        } catch (e: Exception) {
            Log.e(TAG, "extractVidFast error: ${e.message}", e)
            false
        }
    }
}

// ==================== Data Models ====================

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResponseJson(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null,
    @JsonProperty("totalResults") val totalResults: Int? = null,
    @JsonProperty("results") val results: List<SearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("votes") val votes: Long? = null,
    @JsonProperty("playable") val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomeResponseJson(
    @JsonProperty("hero") val hero: List<SearchItem>? = null,
    @JsonProperty("shelves") val shelves: List<ShelfItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShelfItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("items") val items: List<SearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BrowseResponseJson(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null,
    @JsonProperty("totalResults") val totalResults: Int? = null,
    @JsonProperty("results") val results: List<SearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieDetailJson(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("cast") val cast: List<CastItem>? = null,
    @JsonProperty("recommendations") val recommendations: List<SearchItem>? = null,
    @JsonProperty("sources") val sources: List<SourceItem>? = null,
    @JsonProperty("playable") val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TvDetailJson(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("seasonList") val seasonList: List<SeasonListItem>? = null,
    @JsonProperty("cast") val cast: List<CastItem>? = null,
    @JsonProperty("recommendations") val recommendations: List<SearchItem>? = null,
    @JsonProperty("playable") val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonListItem(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("playable") val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonDetailJson(
    @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeItem(
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still") val still: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("playable") val playable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeSourcesJson(
    @JsonProperty("sources") val sources: List<SourceItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("origin") val origin: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CastItem(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("role") val role: String? = null,
    @JsonProperty("photo") val photo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitlesListJson(
    @JsonProperty("files") val files: List<SubtitleItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("hi") val hi: Boolean? = null
)

// ---------- VidFast Decrypt Models ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecStage1Res(
    @JsonProperty("status") val status: Int? = null,
    @JsonProperty("result") val result: EncDecStage1Result? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecStage1Result(
    @JsonProperty("stage1") val stage1: String? = null,
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecStage2Res(
    @JsonProperty("status") val status: Int? = null,
    @JsonProperty("result") val result: EncDecStage2Result? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecStage2Result(
    @JsonProperty("servers") val servers: String? = null,
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecServersRes(
    @JsonProperty("status") val status: Int? = null,
    @JsonProperty("result") val result: List<VidFastServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidFastServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("data") val data: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncDecStreamRes(
    @JsonProperty("status") val status: Int? = null,
    @JsonProperty("result") val result: VidFastStreamResult? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidFastStreamResult(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("tracks") val tracks: List<VidFastTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidFastTrack(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("kind") val kind: String? = null
)

// ---------- Helpers ----------

private fun heightToQuality(maxHeight: Int): Int =
    when {
        maxHeight <= 0 -> Qualities.Unknown.value
        maxHeight >= 2160 -> Qualities.P2160.value
        maxHeight >= 1440 -> Qualities.P1440.value
        maxHeight >= 1080 -> Qualities.P1080.value
        maxHeight >= 720 -> Qualities.P720.value
        maxHeight >= 480 -> Qualities.P480.value
        maxHeight >= 360 -> Qualities.P360.value
        else -> maxHeight
    }

private fun String?.isFutureDate(): Boolean {
    if (this.isNullOrBlank()) return false
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = sdf.parse(this.trim()) ?: return false
        date.time > System.currentTimeMillis()
    } catch (_: Exception) {
        false
    }
}

private fun String?.toUnixMillis(): Long? {
    if (this.isNullOrBlank()) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sdf.parse(this.trim())?.time
    } catch (_: Exception) {
        null
    }
}
