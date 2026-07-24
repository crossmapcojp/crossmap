package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class BCCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BCC"
    override val denominationName = "基督兄弟団"
    override val sourceUrl = "https://kyodaidan.org/church/"
    override val outputFileName = "bcc-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("div.swell-block-column").mapNotNull { card ->
            val name = card.selectFirst("p strong")?.text()?.trim()
            if (name == null || !name.contains("教会")) return@mapNotNull null
            val links = card.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "kyodaidan.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }.distinctBy { it.name }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        return church
    }
}
