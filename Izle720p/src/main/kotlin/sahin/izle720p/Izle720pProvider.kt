package sahin.izle720p

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class Izle720pProvider : MainAPI() {
    override var mainUrl = "https://720pizle.my"
    override var name = "720pizle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/en-iyiler" to "En İyi Filmler",
        "$mainUrl/seri-filmler" to "Film Serileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = parseIzleCards(document)

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun parseIzleCards(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        document.select("a[href*='/izle/'], article, .movie-card, li").forEach { element ->
            val a = if (element.tagName() == "a") element else element.selectFirst("a[href*='/izle/']") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/izle/")) return@forEach

            val title = element.selectFirst("strong, .tt strong, .title, h3")?.text()?.trim()
                ?: a.attr("title").ifEmpty { a.text() }.trim()
            val poster = fixUrlNull(element.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            })

            val isTv = href.contains("/dizi") || href.contains("-sezon-") || element.text().contains("Sezon", ignoreCase = true)
            val tvType = if (isTv) TvType.TvSeries else TvType.Movie

            if (title.isNotEmpty()) {
                if (isTv) {
                    items.add(newTvSeriesSearchResponse(title, href, tvType) {
                        this.posterUrl = poster
                    })
                } else {
                    items.add(newMovieSearchResponse(title, href, tvType) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return parseIzleCards(document).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace(" izle", "")?.replace(" - filmin bilgileri, konusu, oyuncuları, tüm serileri bu sayfada - 720pizle", "")?.trim() ?: "720pizle"

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.poster, .th")?.attr("data-src"))

        val description = document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")

        val isTv = url.contains("/dizi") || url.contains("-sezon-") || document.select("a[href*='sezon'], .episode-item").isNotEmpty()

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            document.select("a[href*='sezon'], .episode-item a").forEach { link ->
                val epUrl = fixUrlNull(link.attr("href")) ?: return@forEach
                val text = link.text().trim()

                val seasonMatch = Regex("""(\d+)\s*[-.]?\s*sezon""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""(\d+)\s*[-.]?\s*sezon""", RegexOption.IGNORE_CASE).find(text)
                val episodeMatch = Regex("""(\d+)\s*[-.]?\s*bolum""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""(\d+)\s*[-.]?\s*bolum""", RegexOption.IGNORE_CASE).find(text)

                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(newEpisode(epUrl) {
                    this.name = text.ifEmpty { "S${season}E${episode}" }
                    this.season = season
                    this.episode = episode
                })
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val iframes = mutableListOf<String>()

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val fixed = fixUrlNull(src)
            if (fixed != null) iframes.add(fixed)
        }

        iframes.distinct().forEach { iframeUrl ->
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
