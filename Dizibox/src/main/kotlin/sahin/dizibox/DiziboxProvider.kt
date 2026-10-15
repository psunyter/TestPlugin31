package sahin.dizibox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class DiziboxProvider : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "Dizibox"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/diziler/" to "Tüm Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = mutableListOf<SearchResponse>()

        if (request.data.contains("/diziler")) {
            document.select(".alphabetical-category-list li a").forEach { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@forEach
                val name = link.attr("title").replace(" izle", "").trim()
                if (name.isNotEmpty()) {
                    homeItems.add(newTvSeriesSearchResponse(name, href, TvType.TvSeries))
                }
            }
        } else {
            document.select("article.article-episode-small-grid, article.article-series-small-grid").forEach { article ->
                val a = article.selectFirst("a.series-details, a.tv-title, a.figure") ?: article.selectFirst("a") ?: return@forEach
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val title = article.selectFirst(".tv-title, strong.archive")?.text()?.trim() ?: a.text().trim()
                val poster = fixUrlNull(article.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                    ?: article.selectFirst("img")?.attr("src"))

                if (title.isNotEmpty()) {
                    homeItems.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = try {
            app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        } catch (e: Exception) {
            app.get("$mainUrl/diziler/", headers = mapOf("User-Agent" to USER_AGENT)).document
        }

        val results = mutableListOf<SearchResponse>()

        document.select("article").forEach { article ->
            val a = article.selectFirst("a") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val title = article.selectFirst(".tv-title, h2, h3, .title")?.text()?.trim() ?: a.text().trim()
            val poster = fixUrlNull(article.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                ?: article.selectFirst("img")?.attr("src"))

            if (title.isNotEmpty()) {
                results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }

        if (results.isEmpty()) {
            document.select(".alphabetical-category-list li a").forEach { link ->
                val title = link.attr("title").replace(" izle", "").trim()
                if (title.contains(query, ignoreCase = true)) {
                    val href = fixUrlNull(link.attr("href")) ?: return@forEach
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val isEpisodePage = url.contains("-bolum-izle") || url.contains("-sezon-")
        val showUrl = if (isEpisodePage) {
            val seriesLink = document.selectFirst("a.series-details, .tv-title a, a[href*='/diziler/']")?.attr("href")
            if (!seriesLink.isNullOrEmpty()) fixUrl(seriesLink) else url
        } else {
            url
        }

        val showDoc = if (showUrl != url) {
            app.get(showUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        } else {
            document
        }

        val title = showDoc.selectFirst("h1, .tv-title, .series-title, .archive")?.text()
            ?.replace(" izle", "")?.trim() ?: "Dizibox Series"
        val poster = fixUrlNull(showDoc.selectFirst("img.afis, .box-image img, meta[property='og:image']")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("content") }
        })
        val description = showDoc.selectFirst(".series-description, .series-summary, meta[name='description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val episodes = mutableListOf<Episode>()

        showDoc.select("a[href*='-bolum-izle']").forEach { link ->
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

        if (episodes.isEmpty() && isEpisodePage) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val iframes = document.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }

        for (iframeUrl in iframes) {
            if (iframeUrl.contains("king.php") || iframeUrl.contains("/player/")) {
                try {
                    val playerDoc = app.get(iframeUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).document
                    val innerIframes = playerDoc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
                    for (innerUrl in innerIframes) {
                        loadExtractor(innerUrl, iframeUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
