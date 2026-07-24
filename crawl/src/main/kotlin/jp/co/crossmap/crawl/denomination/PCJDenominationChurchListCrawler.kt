package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class PCJDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "PCJ"
    override val denominationName = "日本長老教会"
    override val sourceUrls = listOf("https://chorokyokai.jp/churches/")
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "pcj-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select("a[href]").mapNotNull { link ->
            val name = link.text().trim()
            val detail = link.absUrl("href")
            if (!name.contains("教会") || name.contains("教会一覧") || !detail.contains("chorokyokai.jp")) null
            else OfficialDenominationChurch(name = name, denominationChurchListDetailPage = detail)
        }.distinctBy { it.name }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,article,.entry-content") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text),
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "chorokyokai.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ChurchMinisterParser.parse(text),
        )
    }
}
