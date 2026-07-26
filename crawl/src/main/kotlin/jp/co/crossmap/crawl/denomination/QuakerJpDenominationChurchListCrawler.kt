package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class QuakerJpDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "QUAKER_JP"
    override val denominationName = "キリスト友会日本年会"
    override val outputFileName = "quaker_jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val localPages = document.select("a[href]").map { it.absUrl("href") }
        return document.select("section#comp-lbenz59n div[data-testid=richTextElement]")
            .mapNotNull { card ->
                val name = card.select(".backcolor_21")
                    .map { it.text().trim() }
                    .firstOrNull { it.endsWith("月会") }
                    ?: return@mapNotNull null
                val address = addressPattern.find(card.text())?.value
                    ?.let(DirectoryCrawlerSupport::normalizeAddress)
                    .orEmpty()
                val slug = localPageSlugs[name].orEmpty()
                val localPage = localPages.firstOrNull { url ->
                    slug.isNotBlank() && url.substringBefore('?').trimEnd('/').endsWith("/$slug")
                }.orEmpty()
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                    websiteUrl = localPage,
                )
            }
            .distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val localPageSlugs = mapOf(
            "日本年会/東京月会" to "tokyo",
            "下妻月会" to "shimotsuma",
            "大阪月会" to "osaka",
            "水戸月会" to "mito",
        )
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val addressPattern = Regex(
            """(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)[^\s\u200b]+""",
        )
    }
}
