package sahin.anizume

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class AnizumeProvider : MainAPI() {
    override var mainUrl = "https://www.anizume.com"
    override var name = "Anizume"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "tr"
    override val hasMainPage = true

    private val jsonMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Öne Çıkan Animeler",
        "$mainUrl/catalog" to "Anime Kataloğu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = parseAnizumeCards(document)

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun parseAnizumeCards(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        document.select(".poster-card, a[href*='/anime/']").forEach { card ->
            val a = if (card.tagName() == "a") card else card.selectFirst("a") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/anime/") || href.contains("/sezon-")) return@forEach

            val title = card.selectFirst("strong, .title, h3")?.text()?.trim()
                ?: a.attr("title").ifEmpty { a.text() }.trim()
            val poster = fixUrlNull(card.selectFirst("img")?.let {
                it.attr("data-original-src")
                    .ifEmpty { it.attr("src") }
                    .ifEmpty { it.attr("srcset")?.split(" ")?.firstOrNull() }
            })

            if (title.isNotEmpty()) {
                items.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }

        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return parseAnizumeCards(document).distinctBy { it.url }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OfflineSeason(
        @JsonProperty("Number") val number: Int? = null,
        @JsonProperty("Episodes") val episodes: List<OfflineEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OfflineEpisode(
        @JsonProperty("ID") val id: Int? = null,
        @JsonProperty("Order") val order: Int? = null,
        @JsonProperty("Title") val title: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OfflineOptions(
        @JsonProperty("animeName") val animeName: String? = null,
        @JsonProperty("seasons") val seasons: List<OfflineSeason>? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace(" İzle - Türkçe Altyazılı Tüm Bölümler | Anizume", "")?.trim() ?: "Anizume Anime"

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")

        val slug = url.substringAfterLast("/anime/").substringBefore("?")
        val episodesList = mutableListOf<Episode>()

        val optionsScript = document.selectFirst("script#offline-episode-options")?.data()
        if (!optionsScript.isNullOrEmpty()) {
            try {
                val parsedOptions: OfflineOptions = jsonMapper.readValue(optionsScript)
                parsedOptions.seasons?.forEach { season ->
                    val sNum = season.number ?: 1
                    season.episodes?.forEach { ep ->
                        val epNum = ep.order ?: 1
                        val epName = ep.title ?: "Bölüm $epNum"
                        val epUrl = "$mainUrl/anime/$slug/sezon-$sNum/bolum-$epNum"

                        episodesList.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = sNum
                            this.episode = epNum
                        })
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (episodesList.isEmpty()) {
            document.select("a[href*='/sezon-'][href*='/bolum-']").forEach { link ->
                val epUrl = fixUrlNull(link.attr("href")) ?: return@forEach
                val text = link.text().trim()

                val seasonMatch = Regex("""sezon-(\d+)""").find(epUrl)
                val episodeMatch = Regex("""bolum-(\d+)""").find(epUrl)

                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodesList.add(newEpisode(epUrl) {
                    this.name = text.ifEmpty { "Bölüm $episode" }
                    this.season = season
                    this.episode = episode
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodesList.distinctBy { it.data })
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AnimeSource(
        @JsonProperty("ID") val id: Int? = null,
        @JsonProperty("SourceUrl") val sourceUrl: String? = null,
        @JsonProperty("Name") val name: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document

        val sourcesScript = document.selectFirst("script#anime-sources")?.data()
        if (!sourcesScript.isNullOrEmpty()) {
            try {
                val sources: List<AnimeSource> = jsonMapper.readValue(sourcesScript)
                for (source in sources) {
                    val streamUrl = fixUrlNull(source.sourceUrl) ?: continue
                    val sourceName = source.name ?: "Alfa"

                    if (streamUrl.contains(".m3u8")) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name - $sourceName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value
                            )
                        )
                    } else {
                        loadExtractor(streamUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        for (iframe in document.select("iframe[src]")) {
            val src = fixUrlNull(iframe.attr("src")) ?: continue
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
