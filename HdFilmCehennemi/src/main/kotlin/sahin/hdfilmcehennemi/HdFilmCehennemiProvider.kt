package sahin.hdfilmcehennemi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class HdFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HdFilmCehennemi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/en-cok-izlenenler/" to "En Çok İzlenenler",
        "$mainUrl/dizi-izle/" to "Son Eklenen Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = parseMediaCards(document)

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun parseMediaCards(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        document.select(".poster, .card, .poster-card, .movie-box, article").forEach { card ->
            val a = card.selectFirst("a[href]") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val title = card.selectFirst(".title, .poster-title, h2, h3, strong")?.text()?.trim()
                ?: a.attr("title").ifEmpty { a.text() }.trim()
            val poster = fixUrlNull(card.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            })

            val isTv = href.contains("/dizi") || href.contains("-sezon-")
            val tvType = if (isTv) TvType.TvSeries else TvType.Movie

            if (title.isNotEmpty() && !href.endsWith("/dizi-izle/")) {
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
        val searchUrl = "$mainUrl/search?q=$query"
        val document = try {
            app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        } catch (e: Exception) {
            app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to USER_AGENT)).document
        }

        return parseMediaCards(document).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst("h1, .poster-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace(" izle", "")?.trim() ?: "HdFilmCehennemi"

        val poster = fixUrlNull(document.selectFirst("img.poster, .poster img, meta[property='og:image']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("data-src").ifEmpty { it.attr("src") }
        })

        val description = document.selectFirst(".overview, .post-description, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val isTv = url.contains("/dizi/") || url.contains("-sezon-") || document.select(".season-item, .episode-item, a[href*='sezon']").isNotEmpty()

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            document.select("a[href*='sezon'], .episode-list a").forEach { link ->
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

        document.select("[data-video], [data-player], [data-url]").forEach { element ->
            val src = element.attr("data-video")
                .ifEmpty { element.attr("data-player") }
                .ifEmpty { element.attr("data-url") }
            val fixed = fixUrlNull(src)
            if (fixed != null) iframes.add(fixed)
        }

        iframes.distinct().forEach { iframeUrl ->
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
