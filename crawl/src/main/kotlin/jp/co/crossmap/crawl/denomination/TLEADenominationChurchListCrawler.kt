package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TLEADenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TLEA"
    override val denominationName = "The Light of Eternal Agape"
    override val sourceUrl = "https://tlea.tokyoantioch.com/ourchurch/all-tlea-link/"
    override val outputFileName = "tlea-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val contactRows = DirectoryCrawlerSupport.blocks(document, "tr, article, li, .wp-block-group, .elementor-widget-container")
            .mapNotNull { DirectoryCrawlerSupport.churchFromBlock(it, "tlea.tokyoantioch.com") }
        val linkedChurches = document.select("tr td a[href]").mapNotNull { link ->
            val name = link.text().trim()
            if (!Regex("教会|チャーチ|チャペル").containsMatchIn(name)) return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.startsWith("http") }.orEmpty()
            OfficialDenominationChurch(name = name, websiteUrl = href)
        }
        return (contactRows + linkedChurches)
            .groupBy(OfficialDenominationChurch::name)
            .map { (_, rows) -> rows.maxByOrNull { it.address.length + it.ministers.size * 100 }!!
                .let { best -> best.copy(websiteUrl = rows.firstNotNullOfOrNull { it.websiteUrl.takeIf(String::isNotBlank) }.orEmpty()) }
            }
    }
}
