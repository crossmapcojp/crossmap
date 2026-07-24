package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class COTNJPDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "COTN_JP"
    override val denominationName = "日本ナザレン教団"
    override val sourceUrl = "https://www.nazarene.or.jp/cm/index.html"
    override val outputFileName = "cotn_jp-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("div.g-column.-col2").mapNotNull { card ->
            val name = card.selectFirst("h3")?.text()?.trim()
            if (name == null || !name.contains("教会")) return@mapNotNull null
            val info = card.selectFirst("p.c-body")
            val text = info?.text().orEmpty()
            val links = card.select("a[href]")
            val detail = links.firstOrNull { link ->
                val href = link.absUrl("href")
                href.contains("/cm/") && !href.endsWith("/cm/index.html")
            }?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.addressFromText(text),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.nazarene.or.jp"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = detail,
                ministers = ChurchMinisterParser.parse(text),
            )
        }.distinctBy { it.name }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,#contents,.contents,article") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text),
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.nazarene.or.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ChurchMinisterParser.parse(text),
        )
    }
}
