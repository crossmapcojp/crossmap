package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicNiigataDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "niigata"
    override val jurisdictionNames = setOf(
        "新潟教区・秋田地区", "新潟教区・山形地区", "新潟教区・下越地区", "新潟教区・中越地区", "新潟教区・上越地区",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("li.menu-item a[href]").mapNotNull { link ->
            val name = link.text().trim().takeIf { it.startsWith("カトリック") && it.endsWith("教会") }
                ?: return@mapNotNull null
            val district = link.parent()?.parent()?.parent()?.children()?.firstOrNull { it.tagName() == "a" }?.text()?.trim()
                ?.takeIf { it.endsWith("地区") } ?: "下越地区"
            OfficialDenominationChurch(
                name = name,
                jurisdiction = "新潟教区・$district",
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val content = document.selectFirst(".entry-content") ?: document.body()
        val links = content.select("a[href]")
        val ministers = content.select("p").flatMap { paragraph ->
            val label = paragraph.selectFirst("strong")?.text().orEmpty()
            if (label.contains("司祭")) {
                ChurchMinisterParser.fromRoleAndNames("司祭", paragraph.ownText().removeSuffix("神父"))
            } else {
                emptyList()
            }
        }
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(content.text()),
            phone = DirectoryCrawlerSupport.phoneFromText(content.text()),
            fax = DirectoryCrawlerSupport.faxFromText(content.text()),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.catholic-niigata.net"),
            email = DirectoryCrawlerSupport.extractEmail(content.text(), links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ministers.distinctBy { it.roleId to it.name },
        )
    }
}
