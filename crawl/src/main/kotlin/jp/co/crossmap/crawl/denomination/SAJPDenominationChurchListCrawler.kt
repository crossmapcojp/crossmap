package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class SAJPDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "SA_JP"
    override val denominationName = "救世軍"
    override val sourceUrl = "https://www.salvationarmy.or.jp/about-org/chapel/"
    override val outputFileName = "sa_jp-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("div.table-01__row").mapNotNull { row ->
            val name = row.selectFirst("h2.table-01__head")?.text()?.lineSequence()?.firstOrNull()?.trim()
                ?: return@mapNotNull null
            if (!name.contains(Regex("小隊|教会"))) return@mapNotNull null
            val text = row.text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            if (address.isBlank()) return@mapNotNull null
            val links = row.select("a[href]")
            val websiteLinks = links.filterNot { it.absUrl("href").contains(Regex("goo\\.gl/maps|google\\.com/maps")) }
            val detail = links.firstOrNull { it.absUrl("href").contains("salvationarmy.or.jp/") }?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(websiteLinks, "www.salvationarmy.or.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = detail,
            )
        }.distinctBy { it.name to it.address }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,.main-content,article") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.salvationarmy.or.jp").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links)).distinctBy { it.platform to it.url },
            ministers = ChurchMinisterParser.parse(text).ifEmpty { church.ministers },
        )
    }
}
