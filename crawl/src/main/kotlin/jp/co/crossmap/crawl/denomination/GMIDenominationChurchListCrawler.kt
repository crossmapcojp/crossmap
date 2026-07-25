package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class GMIDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "GMI"
    override val denominationName = "グレース宣教会"
    override val sourceUrl = sourceUrls.single()
    override val outputFileName = "gmi-churches.json"

    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select(".chapels_boxs li.clearfix").mapNotNull { card ->
            val name = card.selectFirst(".chapels_name")?.text()?.trim().orEmpty()
            val detail = card.selectFirst(".linkBtn a[href],.post_img a[href]")?.absUrl("href").orEmpty()
            if (name.isBlank() || detail.isBlank()) return@mapNotNull null
            OfficialDenominationChurch(
                name = name.removeSuffix("(GM)").trim(),
                jurisdiction = card.selectFirst(".area_caption")?.text()?.substringBefore('／')
                    ?.substringBefore('/')?.trim().orEmpty(),
                denominationChurchListDetailPage = detail,
            )
        }.distinctBy { it.denominationChurchListDetailPage }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,.under_contents,#contents") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        val ministers = detail.select(".pastor_block").flatMap { pastor ->
            val name = pastor.selectFirst(".name")?.ownText()?.trim().orEmpty()
            val role = pastor.selectFirst(".position")?.text()?.trim().orEmpty()
            if (name.isBlank()) emptyList() else ChurchMinisterParser.fromRoleAndNames(role.ifBlank { "牧師" }, name)
        }
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "gmi.or.jp").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links))
                .distinctBy { it.platform to it.url },
            ministers = ministers.distinctBy { it.roleId to it.name }.ifEmpty { church.ministers },
        )
    }
}
