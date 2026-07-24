package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JBUDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JBU"
    override val denominationName = "日本バプテスト同盟"
    override val sourceUrl = "http://www.jbu.or.jp/chs/"
    override val outputFileName = "jbu-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("tr,li,article,div[class*=church],dl")
            .mapNotNull { churchFromElement(it) }
            .distinctBy { it.name to it.address }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        return churchFromElement(document.selectFirst("main,article,#contents") ?: document.body(), church.name)
            ?.copy(denominationChurchListDetailPage = church.denominationChurchListDetailPage)
            ?: church
    }

    private fun churchFromElement(element: Element, forcedName: String? = null): OfficialDenominationChurch? {
        val text = element.text()
        val address = DirectoryCrawlerSupport.addressFromText(text)
        if (address.isBlank()) return null
        val name = forcedName ?: element.select("h1,h2,h3,h4,strong,b,a").firstOrNull { it.text().contains("教会") }?.text()?.trim()
            ?: return null
        val links = element.select("a[href]")
        return OfficialDenominationChurch(
            name = name,
            address = address,
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.jbu.or.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            denominationChurchListDetailPage = links.firstOrNull { it.absUrl("href").contains("jbu.or.jp") }?.absUrl("href").orEmpty(),
            ministers = ChurchMinisterParser.parse(text),
        )
    }
}
