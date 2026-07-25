package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicHiroshimaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "hirosima"
    override val jurisdictionNames = setOf("広島教区・山口県", "広島教区・島根県", "広島教区・広島県", "広島教区・鳥取県", "広島教区・岡山県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        var prefecture = "広島県"
        return Jsoup.parse(html, url).select("h3, ul.flex_wrap > li, li.kyoudoutai").mapNotNull { element ->
            if (element.tagName() == "h3") {
                element.text().trim().takeIf { it.endsWith("県") }?.let { prefecture = it }
                return@mapNotNull null
            }
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val name = link.text().replace(Regex("^\\d+\\s*"), "").trim().takeIf { it.contains("教会") }
                ?: return@mapNotNull null
            OfficialDenominationChurch(
                name = name, jurisdiction = "広島教区・$prefecture", denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val content = document.selectFirst("main, article, .incontents") ?: document.body()
        val links = content.select("a[href]")
        val text = content.text()
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text), phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "hiroshima.catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
        )
    }
}
