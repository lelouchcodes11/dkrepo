package com.sevenmovies.extension

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.JsonAsString
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

@CloudstreamPlugin
class SevenMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SevenMovies())
    }
}

/**
 * Provider for 7movies.ac.
 *
 * Metadata comes from the site's TMDB-backed json api:
 *   /api/search?q= , /api/detail?id=&type= , /api/tv/{id}/season/{n} , /api/discover
 *
 * Playback uses the site's direct (vidrift) pipeline:
 *   1. POST /api/playback-token  {tmdbId,type,season,episode}  (needs 7movies.ac referer/origin)
 *   2. GET  https://embed.vidrift.net/api/boot/{movie/{id} | tv/{id}/{s}/{e}}
 *           (provides instant orionStreams, vexonUrl, evionUrl)
 *   3. GET  https://embed.vidrift.net/api/source/{movie/{id} | tv/{id}/{s}/{e}}
 *           ?token=...&provider={orion|titan|lyra|vela|atlas|vexon}&parent=7movies.ac
 *   4. Subtitles from subtitleListUrl (/api/orion/.../subtitles.json) and source subtitles
 *      forwarded with Referer: https://embed.vidrift.net/
 *   5. Fallback embed players resolved in parallel.
 */
class SevenMovies : MainAPI() {
    override var mainUrl = "https://7movies.ac"
    override var name = "7Movies"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "en"

    private val playerUrl = "https://embed.vidrift.net"

    // ---------- helpers ----------

    private suspend fun apiGet(url: String, referer: String? = null): String? {
        return try {
            val ref = referer ?: mainUrl
            app.get(
                url,
                referer = ref,
                headers = mapOf(
                    "Referer" to ref,
                    "Origin" to (if (url.startsWith(playerUrl)) playerUrl else mainUrl),
                )
            ).text
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getDetail(id: Long, type: String): DetailJson? {
        return AppUtils.tryParseJson<DetailResponse>(
            apiGet("$mainUrl/api/detail?id=$id&type=$type")
        )?.detail
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val json = AppUtils.tryParseJson<SearchResponseJson>(
            apiGet("$mainUrl/api/search?q=${URLEncoder.encode(query, "utf-8")}")
        ) ?: return emptyList()
        return json.results.map { it.toSearchResponse() }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse {
        val poster = poster
        val yearValue = year?.toIntOrNull()
        val scoreValue = Score.from(rating, 10)
        val tmdbId = id.toInt()
        val target = if (mediaType == "tv") "$mainUrl/tv/$id" else "$mainUrl/movie/$id"
        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title ?: "Untitled", target, TvType.TvSeries, fix = false) {
                posterUrl = poster
                year = yearValue
                score = scoreValue
                this.id = tmdbId
            }
        } else {
            newMovieSearchResponse(title ?: "Untitled", target, TvType.Movie, fix = false) {
                posterUrl = poster
                year = yearValue
                score = scoreValue
                this.id = tmdbId
            }
        }
    }

    private fun RecommendationItem.toSearchResponse(defaultType: String): SearchResponse? {
        val tmdbId = id ?: return null
        val titleValue = title ?: name ?: return null
        val resolvedType = (mediaType ?: mediaTypeSnake ?: defaultType).lowercase()
        val isTv = resolvedType == "tv"
        val target = if (isTv) "$mainUrl/tv/$tmdbId" else "$mainUrl/movie/$tmdbId"
        val posterUrlResolved = (posterPath ?: poster)?.imagePath("w500")
        val yearValue = (releaseDate ?: firstAirDate)?.yearOf() ?: year?.toIntOrNull()
        val scoreValue = Score.from(voteAverage, 10)
        return if (isTv) {
            newTvSeriesSearchResponse(titleValue, target, TvType.TvSeries, fix = false) {
                posterUrl = posterUrlResolved
                year = yearValue
                score = scoreValue
                this.id = tmdbId.toInt()
            }
        } else {
            newMovieSearchResponse(titleValue, target, TvType.Movie, fix = false) {
                posterUrl = posterUrlResolved
                year = yearValue
                score = scoreValue
                this.id = tmdbId.toInt()
            }
        }
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "movie" to "Movies",
        "tv" to "TV Shows",
        "movie;28" to "Action",
        "movie;35" to "Comedy",
        "movie;27" to "Horror",
        "movie;878" to "Sci-Fi & Fantasy",
        "movie;16" to "Animation",
        "movie;18" to "Drama",
        "tv;35" to "Comedy TV",
        "tv;18" to "Drama TV",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split(";")
        val mediaType = parts[0]
        val genre = parts.getOrNull(1)
        val url = buildString {
            append("$mainUrl/api/discover?type=$mediaType&page=$page")
            if (genre != null) append("&genre=$genre")
        }
        val json = AppUtils.tryParseJson<DiscoverJson>(apiGet(url))
        val items = json?.items.orEmpty()
        val hasNext = json?.totalPages?.let { page < it } ?: false
        return newHomePageResponse(request, items.map { it.toSearchResponse() }, hasNext)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            loadImpl(url)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadImpl(url: String): LoadResponse? {
        // url is either "movie/<id>", "tv/<id>" or the full "https://7movies.ac/movie/<id>"
        val path = url.substringAfter(mainUrl, url).substringBefore("?").trim('/')
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1].toLongOrNull() ?: return null
        if (type != "movie" && type != "tv") return null

        val detail = getDetail(id, type) ?: return null
        val title = detail.title ?: detail.name ?: "Untitled"
        val poster = detail.posterPath?.imagePath("w500")
        val backdrop = detail.backdropPath?.imagePath("w1280")
        val genres = detail.genres?.mapNotNull { it.name }
        val scoreValue = Score.from(detail.voteAverage, 10)

        if (type == "movie") {
            return newMovieLoadResponse(
                name = title,
                url = "$mainUrl/movie/$id",
                type = TvType.Movie,
                dataUrl = "$mainUrl/movie/$id",
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = detail.overview
                year = detail.releaseDate.yearOf()
                tags = genres
                score = scoreValue
                duration = detail.runtime
                actors = detail.credits.castToActorData()
                val recs = if (detail.recommendations?.results.isNullOrEmpty()) detail.similar else detail.recommendations
                recommendations = recs.toRecList(defaultType = "movie")
                comingSoon = detail.releaseDate.isFutureDate()
            }
        }

        // tv series: build episodes per season
        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 }.orEmpty()
        val episodes = seasons.flatMap { season ->
            val seasonNumber = season.seasonNumber ?: return@flatMap emptyList()
            val seasonJson = AppUtils.tryParseJson<SeasonJson>(
                apiGet("$mainUrl/api/tv/$id/season/$seasonNumber")
            )
            seasonJson?.episodes?.map { ep ->
                // episode data carries "<tmdbId>|<season>_<episode>" used by loadLinks
                newEpisode(
                    url = "$id|${seasonNumber}_${ep.episodeNumber ?: 0}",
                    initializer = {
                        name = ep.name
                        this.season = seasonNumber
                        episode = ep.episodeNumber
                        posterUrl = ep.stillPath?.imagePath("w300")
                        description = ep.overview
                        date = ep.airDate.toUnixMillis()
                        runTime = ep.runtime?.times(60)
                        score = Score.from(ep.voteAverage, 10)
                    },
                    fix = false,
                )
            }.orEmpty()
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = "$mainUrl/tv/$id",
            type = TvType.TvSeries,
            episodes = episodes,
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = detail.overview
            year = detail.firstAirDate.yearOf()
            tags = genres
            score = scoreValue
            duration = detail.episodeRunTime?.lastOrNull()
            nextAiring = detail.nextEpisodeToAir?.let {
                NextAiring(
                    it.episodeNumber ?: 0,
                    it.airDate.toUnixSeconds() ?: 0L,
                    it.seasonNumber,
                )
            }
            actors = detail.credits.castToActorData()
            val recs = if (detail.recommendations?.results.isNullOrEmpty()) detail.similar else detail.recommendations
            recommendations = recs.toRecList(defaultType = "tv")
        }
    }

    private fun Credits?.castToActorData(): List<ActorData>? =
        this?.cast?.mapNotNull { member ->
            val actorName = member.name ?: return@mapNotNull null
            ActorData(
                Actor(actorName, member.profilePath?.imagePath("w185")),
                roleString = member.character,
            )
        }

    private fun Recommendations?.toRecList(defaultType: String): List<SearchResponse>? =
        this?.results
            ?.filter { (it.id ?: 0) > 0 && !(it.title ?: it.name).isNullOrEmpty() }
            ?.mapNotNull { it.toSearchResponse(defaultType) }

    // ---------- links ----------

    private data class PlaybackArgs(
        val tmdbId: Long,
        val type: String,
        val season: Int?,
        val episode: Int?,
    )

    private fun parsePlaybackData(data: String): PlaybackArgs? {
        val clean = data.removePrefix(mainUrl).trimStart('/')
        if (clean.contains("movie")) {
            val id = Regex("""movie/(\d+)""").find(clean)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: Regex("""(\d+)""").find(clean)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (id != null) return PlaybackArgs(id, "movie", null, null)
        }
        val matchTvPath = Regex("""tv/(\d+)/(\d+)/(\d+)""").find(clean)
        if (matchTvPath != null) {
            val id = matchTvPath.groupValues[1].toLongOrNull() ?: return null
            val s = matchTvPath.groupValues[2].toIntOrNull() ?: 1
            val e = matchTvPath.groupValues[3].toIntOrNull() ?: 1
            return PlaybackArgs(id, "tv", s, e)
        }
        val matchPipe = Regex("""(?:tv/)?(\d+)\|(\d+)_(\d+)""").find(clean)
        if (matchPipe != null) {
            val id = matchPipe.groupValues[1].toLongOrNull() ?: return null
            val s = matchPipe.groupValues[2].toIntOrNull() ?: 1
            val e = matchPipe.groupValues[3].toIntOrNull() ?: 1
            return PlaybackArgs(id, "tv", s, e)
        }
        val matchTvPlain = Regex("""tv/(\d+)""").find(clean)
        if (matchTvPlain != null) {
            val id = matchTvPlain.groupValues[1].toLongOrNull() ?: return null
            return PlaybackArgs(id, "tv", 1, 1)
        }
        if (clean.all { it.isDigit() }) {
            val id = clean.toLongOrNull() ?: return null
            return PlaybackArgs(id, "movie", null, null)
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val args = parsePlaybackData(data) ?: return false
        val sourcePath = if (args.type == "movie") {
            "movie/${args.tmdbId}"
        } else {
            "tv/${args.tmdbId}/${args.season}/${args.episode}"
        }

        var found = false
        val seenStreamUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubListUrls = Collections.synchronizedSet(mutableSetOf<String>())

        val token = getPlaybackToken(args)

        suspend fun handleStream(stream: StreamInfo, fallbackProvider: String) {
            val rawLink = stream.url?.takeIf { it.isNotBlank() }
                ?: stream.proxyUrl?.takeIf { it.isNotBlank() }
                ?: return

            val resolved = if (rawLink.startsWith("/")) playerUrl + rawLink else rawLink
            if (seenStreamUrls.add(resolved)) {
                val providerName = stream.provider?.takeIf { it.isNotBlank() }
                    ?: fallbackProvider.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                val streamName = stream.name?.takeIf { it.isNotBlank() } ?: ""
                val label = if (streamName.isNotEmpty() && !streamName.contains(providerName, ignoreCase = true)) {
                    "$providerName - $streamName"
                } else if (streamName.isNotEmpty()) {
                    streamName
                } else {
                    providerName
                }

                val linkType = if (stream.type.equals("mp4", ignoreCase = true) || resolved.contains(".mp4")) {
                    ExtractorLinkType.VIDEO
                } else {
                    ExtractorLinkType.M3U8
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = resolved,
                        type = linkType,
                    ) {
                        referer = "$playerUrl/"
                        quality = qualityOf(stream.rungs)
                        headers = mapOf("Referer" to "$playerUrl/")
                    }
                )
                found = true
            }

            val subListUrl = stream.subtitleListUrl?.takeIf { it.isNotBlank() }
            if (subListUrl != null && seenSubListUrls.add(subListUrl)) {
                val resolvedSubList = if (subListUrl.startsWith("/")) playerUrl + subListUrl else subListUrl
                val subRes = apiGet(resolvedSubList, referer = "$playerUrl/embed2/play")
                if (subRes != null) {
                    val tracksJson = AppUtils.tryParseJson<SubtitlesJsonResponse>(subRes)
                    tracksJson?.tracks.orEmpty().forEach { track ->
                        val subUrl = track.url?.takeIf { it.isNotBlank() } ?: return@forEach
                        val resolvedSub = if (subUrl.startsWith("/")) playerUrl + subUrl else subUrl
                        if (seenSubUrls.add(resolvedSub)) {
                            val langName = track.label?.takeIf { it.isNotBlank() }
                                ?: track.lang?.takeIf { it.isNotBlank() }
                                ?: track.code?.takeIf { it.isNotBlank() }
                                ?: "Unknown"
                            subtitleCallback(
                                newSubtitleFile(
                                    langName,
                                    resolvedSub,
                                ) {
                                    headers = mapOf("Referer" to "$playerUrl/")
                                }
                            )
                        }
                    }
                }
            }
        }

        suspend fun handleDirectSubtitles(subtitles: List<SubtitleInfo>?) {
            subtitles.orEmpty().forEach { sub ->
                val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@forEach
                val resolvedSub = if (subUrl.startsWith("/")) playerUrl + subUrl else subUrl
                if (seenSubUrls.add(resolvedSub)) {
                    val langName = sub.lang?.takeIf { it.isNotBlank() }
                        ?: sub.code?.takeIf { it.isNotBlank() }
                        ?: "Unknown"
                    subtitleCallback(
                        newSubtitleFile(
                            langName,
                            resolvedSub,
                        ) {
                            headers = mapOf("Referer" to "$playerUrl/")
                        }
                    )
                }
            }
        }

        if (token != null) {
            // 1. Fetch boot metadata (gives vexonUrl, evionUrl, and orionStreams immediately)
            runCatching {
                val bootRes = apiGet(
                    "$playerUrl/api/boot/$sourcePath?token=${URLEncoder.encode(token, "utf-8")}&parent=7movies.ac",
                    referer = "$playerUrl/embed2/play"
                )
                if (bootRes != null) {
                    val bootJson = AppUtils.tryParseJson<BootResponse>(bootRes)
                    val meta = bootJson?.meta
                    if (meta != null) {
                        val vexon = meta.vexonUrl?.takeIf { it.isNotBlank() }
                        if (vexon != null && seenStreamUrls.add(vexon)) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Vexon 1080p",
                                    url = vexon,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    referer = "$playerUrl/"
                                    quality = Qualities.P1080.value
                                    headers = mapOf("Referer" to "$playerUrl/")
                                }
                            )
                            found = true
                        }
                        val evion = meta.evionUrl?.takeIf { it.isNotBlank() }
                        if (evion != null && seenStreamUrls.add(evion)) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Evion",
                                    url = evion,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    referer = "$playerUrl/"
                                    quality = Qualities.Unknown.value
                                    headers = mapOf("Referer" to "$playerUrl/")
                                }
                            )
                            found = true
                        }
                        meta.orionStreams.orEmpty().forEach { s ->
                            handleStream(s, "Orion")
                        }
                    }
                }
            }

            // 2. Fetch all providers in parallel (orion, titan, lyra, vela, atlas, vexon)
            val providers = listOf("orion", "titan", "lyra", "vela", "atlas", "vexon")
            providers.amap { provider ->
                runCatching {
                    val res = apiGet(
                        "$playerUrl/api/source/$sourcePath?token=${URLEncoder.encode(token, "utf-8")}" +
                            "&provider=$provider&parent=7movies.ac",
                        referer = "$playerUrl/embed2/play",
                    ) ?: return@runCatching
                    val json = AppUtils.tryParseJson<SourceResponse>(res) ?: return@runCatching

                    json.streams.orEmpty().forEach { stream ->
                        handleStream(stream, provider)
                    }
                    handleDirectSubtitles(json.subtitles)
                }
            }
        }

        // ---- fallback: legacy embed players (resolved in parallel by installed extractor packs) ----
        val embeds: List<String> = if (args.type == "movie") {
            val id = args.tmdbId
            listOf(
                "https://vidlink.pro/movie/$id",
                "https://player.cinezo.live/embed/movie/$id?autoplay=true",
                "https://atlantic.st/embed/movie/$id",
                "https://vidstuck.xyz/embed/movie/$id?color=e8e6e1&autoplay=true&back=false",
                "https://www.vidy.st/movie/$id?color=e8e6e1&autoplay=true",
                "https://vaplayer.ru/embed/movie/$id",
                "https://vidbolt.xyz/movie/$id?autoPlay=true",
                "https://bingr.one/watch/movie/$id",
            )
        } else {
            val id = args.tmdbId
            val season = args.season ?: 1
            val episode = args.episode ?: 1
            listOf(
                "https://vidlink.pro/tv/$id/$season/$episode",
                "https://player.cinezo.live/embed/tv/$id/$season/$episode?autoplay=true",
                "https://atlantic.st/embed/tv/$id/$season/$episode",
                "https://vidstuck.xyz/embed/tv/$id/$season/$episode?color=e8e6e1&autoplay=true&back=false",
                "https://www.vidy.st/tv/$id/$season/$episode?color=e8e6e1&autoplay=true",
                "https://vaplayer.ru/embed/tv/$id/$season/$episode",
                "https://vidbolt.xyz/tv/$id/$season/$episode?autoPlay=true",
                "https://bingr.one/watch/tv/$id/$season/$episode",
            )
        }
        embeds.amap { embed ->
            runCatching {
                if (loadExtractor(embed, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    /**
     * Mints a short-lived playback token bound to the requested media.
     */
    private suspend fun getPlaybackToken(args: PlaybackArgs): String? {
        val body = if (args.type == "movie") {
            """{"tmdbId":${args.tmdbId},"type":"movie"}"""
        } else {
            """{"tmdbId":${args.tmdbId},"type":"tv","season":${args.season},"episode":${args.episode}}"""
        }
        return try {
            val text = app.post(
                "$mainUrl/api/playback-token",
                json = JsonAsString(body),
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                ),
            ).text
            AppUtils.tryParseJson<TokenResponse>(text)?.token
        } catch (_: Exception) {
            null
        }
    }

    private fun qualityOf(rungs: List<Rung>?): Int {
        val maxHeight = rungs?.maxOfOrNull { it.height ?: 0 } ?: 0
        return when {
            maxHeight <= 0 -> Qualities.Unknown.value
            maxHeight >= 2160 -> Qualities.P2160.value
            maxHeight >= 1440 -> Qualities.P1440.value
            maxHeight >= 1080 -> Qualities.P1080.value
            maxHeight >= 720 -> Qualities.P720.value
            maxHeight >= 480 -> Qualities.P480.value
            maxHeight >= 360 -> Qualities.P360.value
            else -> maxHeight
        }
    }
}

// ---------- json models ----------

private data class SearchItem(
    @JsonProperty("id") val id: Long,
    @JsonProperty("title") val title: String?,
    @JsonProperty("year") val year: String?,
    @JsonProperty("rating") val rating: Double?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("mediaType") val mediaType: String,
)

private data class SearchResponseJson(
    @JsonProperty("results") val results: List<SearchItem>,
)

private data class DiscoverJson(
    @JsonProperty("items") val items: List<SearchItem>?,
    @JsonProperty("page") val page: Long?,
    @JsonProperty("totalPages") val totalPages: Long?,
)

private data class Genre(
    @JsonProperty("id") val id: Long?,
    @JsonProperty("name") val name: String?,
)

private data class CastMember(
    @JsonProperty("name") val name: String?,
    @JsonProperty("character") val character: String?,
    @JsonProperty("profile_path") val profilePath: String?,
)

private data class Credits(
    @JsonProperty("cast") val cast: List<CastMember>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RecommendationItem(
    @JsonProperty("id") val id: Long?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("mediaType") val mediaType: String?,
    @JsonProperty("media_type") val mediaTypeSnake: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("first_air_date") val firstAirDate: String?,
    @JsonProperty("year") val year: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Recommendations(
    @JsonProperty("results") val results: List<RecommendationItem>?,
)

private data class NextEpisode(
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("air_date") val airDate: String?,
)

private data class Season(
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("name") val name: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DetailJson(
    @JsonProperty("id") val id: Long,
    @JsonProperty("title") val title: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("first_air_date") val firstAirDate: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>?,
    @JsonProperty("genres") val genres: List<Genre>?,
    @JsonProperty("credits") val credits: Credits?,
    @JsonProperty("recommendations") val recommendations: Recommendations?,
    @JsonProperty("similar") val similar: Recommendations?,
    @JsonProperty("next_episode_to_air") val nextEpisodeToAir: NextEpisode?,
    @JsonProperty("seasons") val seasons: List<Season>?,
)

private data class DetailResponse(
    @JsonProperty("detail") val detail: DetailJson,
)

private data class SeasonEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("still_path") val stillPath: String?,
    @JsonProperty("air_date") val airDate: String?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?,
)

private data class SeasonJson(
    @JsonProperty("episodes") val episodes: List<SeasonEpisode>?,
)

// vidrift /api/source response

private data class TokenResponse(
    @JsonProperty("token") val token: String?,
    @JsonProperty("expiresAt") val expiresAt: Long?,
)

private data class Rung(
    @JsonProperty("width") val width: Int?,
    @JsonProperty("height") val height: Int?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class StreamInfo(
    @JsonProperty("url") val url: String?,
    @JsonProperty("proxyUrl") val proxyUrl: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("provider") val provider: String?,
    @JsonProperty("direct") val direct: Boolean?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("rungs") val rungs: List<Rung>?,
    @JsonProperty("subtitleListUrl") val subtitleListUrl: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TrackInfo(
    @JsonProperty("code") val code: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("lang") val lang: String?,
    @JsonProperty("url") val url: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubtitlesJsonResponse(
    @JsonProperty("tracks") val tracks: List<TrackInfo>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BootMeta(
    @JsonProperty("tmdbId") val tmdbId: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("vexonUrl") val vexonUrl: String?,
    @JsonProperty("evionUrl") val evionUrl: String?,
    @JsonProperty("orionStreams") val orionStreams: List<StreamInfo>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BootResponse(
    @JsonProperty("meta") val meta: BootMeta?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SourceResponse(
    @JsonProperty("streams") val streams: List<StreamInfo>?,
    @JsonProperty("subtitles") val subtitles: List<SubtitleInfo>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubtitleInfo(
    @JsonProperty("code") val code: String?,
    @JsonProperty("lang") val lang: String?,
    @JsonProperty("url") val url: String?,
)

// ---------- helpers ----------

/**
 * Tmdb image path to full url with the given size.
 */
private fun String.imagePath(size: String = "w500"): String {
    if (startsWith("http")) return this
    return "https://image.tmdb.org/t/p/$size/${removePrefix("/")}"
}

private fun String?.yearOf(): Int? =
    this?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

/**
 * CloudStream 4.8 stores episode dates as unix epoch milliseconds.
 */
private fun String?.toUnixMillis(): Long? = this?.let { date ->
    runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(date)?.time
    }.getOrNull()
}

private fun String?.toUnixSeconds(): Long? = toUnixMillis()?.div(1000)

private fun String?.isFutureDate(): Boolean =
    toUnixMillis()?.let { it > System.currentTimeMillis() } ?: false
