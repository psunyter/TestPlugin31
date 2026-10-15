package sahin.animpow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class AnimpowProvider : MainAPI() {
    override var mainUrl = "https://animpow.com"
    override var name = "Animpow"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Öne Çıkan Animeler",
        "$mainUrl/catalog" to "Anime Kataloğu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = parseAnimesFromDoc(document)

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun parseAnimesFromDoc(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val html = document.html()

        val animeRegex = Regex("""\\*"animpow_core_id\\*":\\*"([^"\\]+)\\*",.*?\\*"name\\*":\\*"([^"\\]+)\\*",.*?\\*"poster\\*":\\*"([^"\\]+)\\*"""")
        animeRegex.findAll(html).forEach { match ->
            val id = match.groupValues[1]
            val name = match.groupValues[2]
                .replace("\\u0027", "'")
                .replace("\\", "")
            val poster = match.groupValues[3].replace("\\", "")

            val href = "$mainUrl/anime/$id"
            if (id.isNotEmpty() && name.isNotEmpty()) {
                items.add(newAnimeSearchResponse(name, href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
        }

        document.select("a[href*='/anime/'], .poster-card").forEach { element ->
            val a = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val title = element.selectFirst("strong, .title, h3")?.text()?.trim()
                ?: a.attr("title").ifEmpty { a.text() }.trim()
            val poster = fixUrlNull(element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("img")?.attr("srcset")?.split(" ")?.firstOrNull())

            if (title.isNotEmpty() && href.contains("/anime/")) {
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
        return parseAnimesFromDoc(document).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val html = document.html()

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?.replace(" | AnimPow", "")?.replace(" Türkçe Altyazılı İzle", "")?.trim()
            ?: document.selectFirst("h1, title")?.text()?.trim()
            ?: "Anime"

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")

        val animpowCoreId = url.substringAfterLast("/anime/").substringBefore("?")

        val episodes = mutableListOf<Episode>()

        val totalEpisodesMatch = Regex("""\\*"numberOfEpisodes\\*":\s*(\d+)""").find(html)
        val totalEp = totalEpisodesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 12

        for (ep in 1..totalEp) {
            val epUrl = "$mainUrl/watch/$animpowCoreId/$ep"
            episodes.add(newEpisode(epUrl) {
                this.name = "$ep. Bölüm"
                this.episode = ep
                this.season = 1
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
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
