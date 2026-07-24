package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.ChurchMinister
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
            val path = runCatching { URI(detail).path.orEmpty() }.getOrDefault("")
            if (!name.contains("教会") || !Regex("^/churches/[^/]+/?$").matches(path)) null
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
            ministers = parseMinisters(text),
        )
    }

    private fun parseMinisters(text: String): List<ChurchMinister> {
        val contact = Regex("窓口\\s*(.*?)\\s*所属中会").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (contact.isBlank() || contact.contains("代表電話")) return emptyList()
        return buildList {
            Regex("(.+?)(引退協力教師|代理牧師|牧師|宣教師|長老)(?=\\s*(?:、|,|$))")
                .findAll(contact)
                .forEach { match ->
                    addAll(ChurchMinisterParser.fromRoleAndNames(match.groupValues[2], match.groupValues[1]))
                }
            Regex("(?:^|[、,]\\s*)Pastor\\s+(.+)$", RegexOption.IGNORE_CASE).find(contact)?.let { match ->
                addAll(ChurchMinisterParser.fromRoleAndNames("牧師", match.groupValues[1]))
            }
        }.distinctBy { it.roleId to it.name }
    }
}
