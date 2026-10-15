package sahin.anizium

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.text.SimpleDateFormat
import java.util.*

class AniziumProvider : MainAPI() {
    override var mainUrl = "https://anizium.co"
    private val apiHost = "https://api.anizium.co"
    override var name = "Anizium"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "tr"
    override val hasMainPage = true

    private val jsonMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TOKEN_KEY = "hlxjl1c2w281ax473rt1ofgrvhyjvi"
    }

    private fun generateApiHeaders(): Map<String, String> {
        val sdf = SimpleDateFormat("dddd", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
        val dayLower = sdf.format(Date()).lowercase(Locale.ENGLISH)

        val key = "${TOKEN_KEY}_${dayLower}"
        val timestamp = System.currentTimeMillis() / 1000
        val payload = "{\"date\":\"$timestamp\"}"

        val dataBytes = payload.toByteArray(Charsets.UTF_8)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val resultBytes = ByteArray(dataBytes.size)

        for (i in dataBytes.indices) {
            resultBytes[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }

        val cfControl = resultBytes.joinToString("") { "%02x".format(it) }

        return mapOf(
            "Cf-Control" to cfControl,
            "device" to "browser",
            "site" to "main",
            "User-Agent" to USER_AGENT
        )
    }

    override val mainPage = mainPageOf(
        "$apiHost/page/home" to "Öne Çıkan Animeler"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumAnimeItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("name_tr") val nameTr: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("sef") val sef: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumHomeResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("settlement_top") val settlementTop: List<AniziumAnimeItem>? = null,
        @JsonProperty("settlement_slider") val settlementSlider: List<AniziumAnimeItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val responseText = app.get(request.data, headers = generateApiHeaders()).text
        val homeItems = mutableListOf<SearchResponse>()

        try {
            val parsed: AniziumHomeResponse = jsonMapper.readValue(responseText)
            val combinedList = (parsed.settlementTop ?: emptyList()) + (parsed.settlementSlider ?: emptyList())

            combinedList.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.nameTr ?: item.name ?: return@forEach
                val poster = item.poster
                val url = "$mainUrl/anime/$id"

                homeItems.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumSearchPage(
        @JsonProperty("data") val data: List<AniziumAnimeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumSearchResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("page") val page: AniziumSearchPage? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$apiHost/page/search?value=$query&page=1"
        val responseText = try {
            app.get(searchUrl, headers = generateApiHeaders()).text
        } catch (e: Exception) {
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()

        try {
            val parsed: AniziumSearchResponse = jsonMapper.readValue(responseText)
            parsed.page?.data?.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.nameTr ?: item.name ?: return@forEach
                val poster = item.poster
                val url = "$mainUrl/anime/$id"

                results.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results.distinctBy { it.url }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumEpisode(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("order") val order: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumSeason(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<AniziumEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumAnimeDetail(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("name_tr") val nameTr: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("seasons") val seasons: List<AniziumSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniziumDetailResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AniziumAnimeDetail? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/anime/").substringBefore("?")
        val apiUrl = "$apiHost/anime/get?id=$animeId"

        val responseText = app.get(apiUrl, headers = generateApiHeaders()).text
        val parsed: AniziumDetailResponse = jsonMapper.readValue(responseText)
        val detail = parsed.data ?: throw ErrorLoadingException("Failed to load anime details")

        val title = detail.nameTr ?: detail.name ?: "Anizium Anime"
        val poster = fixUrlNull(detail.poster)
        val description = detail.overview

        val episodesList = mutableListOf<Episode>()

        detail.seasons?.forEachIndexed { index, season ->
            val sNum = season.seasonNumber ?: (index + 1)
            season.episodes?.forEach { ep ->
                val epId = ep.id ?: return@forEach
                val epNum = ep.episodeNumber ?: ep.order ?: 1
                val epName = ep.name ?: "$epNum. Bölüm"
                val epUrl = "$mainUrl/watch/$epId"

                episodesList.add(newEpisode(epUrl) {
                    this.name = epName
                    this.season = sNum
                    this.episode = epNum
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodesList.distinctBy { it.data })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            val src = fixUrlNull(element.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
