package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicTokyoArchdioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "tokyo"
    override val jurisdictionNames = setOf("東京大司教区・東京都", "東京大司教区・千葉県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val jurisdiction = if (url.contains("/chiba/")) jurisdictionNames.last() else jurisdictionNames.first()
        return Jsoup.parse(html, url).select("li.info__item a[href]").mapNotNull { link ->
            val name = link.text().trim().takeIf { it.contains("教会") } ?: return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                jurisdiction = jurisdiction,
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val links = document.select("main a[href], article a[href], .contents a[href]")
        val text = document.body().text()
        val ministers = document.select("h3").filter { it.text().contains("司祭") }.flatMap { heading ->
            heading.nextElementSibling()?.text()?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty()
        }
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text),
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tokyo.catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ministers.distinctBy { it.roleId to it.name },
        )
    }
}
