package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup

class EFCJPDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "EFC_JP"
    override val denominationName = "日本福音自由教会協議会"
    override val sourceUrls = listOf("https://efcj.org/churchlist")
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "efc_jp-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select("a[href]").mapNotNull { link ->
            val name = link.text().trim()
            val detail = link.absUrl("href")
            val path = runCatching { URI(detail).path.orEmpty() }.getOrDefault("")
            if (!name.contains("教会") || !Regex("^/posts/churchinfo/[^/]+/?$").matches(path)) null
            else OfficialDenominationChurch(name = name, denominationChurchListDetailPage = detail)
        }.distinctBy { it.name }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val table = document.select("table").firstOrNull { candidate -> candidate.text().contains("所在地") }
            ?: return church
        val values = table.select("tr").associate { row ->
            row.selectFirst("td:first-child")?.text()?.trim().orEmpty() to row.select("td").getOrNull(1)
        }
        val websiteCell = values["ウェブサイト"]
        val websiteLinks = websiteCell?.select("a[href]").orEmpty()
        val website = DirectoryCrawlerSupport.externalWebsite(websiteLinks, "efcj.org")
        return church.copy(
            jurisdiction = values["地区"]?.text()?.trim().orEmpty(),
            address = values["所在地"]?.text()?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty(),
            phone = values["電話番号"]?.text()?.trim().orEmpty(),
            fax = values["FAX番号"]?.text()?.trim().orEmpty(),
            websiteUrl = website,
            email = values["メール"]?.let { DirectoryCrawlerSupport.extractEmail(it.text(), it.select("a[href]").map { link -> link.attr("href") }) }.orEmpty(),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(websiteLinks),
            ministers = values["教職者名"]?.text()?.let { ChurchMinisterParser.fromRoleAndNames("教職", it) }.orEmpty(),
        )
    }
}
