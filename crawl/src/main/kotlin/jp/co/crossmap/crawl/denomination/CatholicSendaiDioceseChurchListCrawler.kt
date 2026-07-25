package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicSendaiDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "sendai"
    override val jurisdictionNames = (1..5).mapTo(linkedSetOf()) { "仙台教区・第${it}地区" }

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("a[href*=/diocese/parishes/d]").mapNotNull { link ->
            val href = link.absUrl("href")
            val district = Regex("/parishes/d([1-5])/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.contains("教会") } ?: return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                jurisdiction = "仙台教区・第${district}地区",
                denominationChurchListDetailPage = href.takeUnless { unavailableDetailPages.any(href::endsWith) }.orEmpty(),
                note = if (unavailableDetailPages.any(href::endsWith)) "Official diocese detail page is currently unavailable" else "",
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val contact = document.selectFirst("ul.church__access") ?: document.body()
        val links = document.select("main a[href], article a[href], .church a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(contact.text()),
            phone = DirectoryCrawlerSupport.phoneFromText(contact.text()),
            fax = DirectoryCrawlerSupport.faxFromText(contact.text()),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "sendai.catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(document.body().text(), links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
        )
    }

    private companion object {
        val unavailableDetailPages = setOf("/d1/aomori-honcho/")
    }
}
