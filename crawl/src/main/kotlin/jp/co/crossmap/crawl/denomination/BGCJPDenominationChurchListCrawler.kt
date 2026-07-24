package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class BGCJPDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BGC_JP"
    override val denominationName = "日本バプテスト教会連合"
    override val sourceUrl = "https://rengo.ne.jp/chruch-list/"
    override val outputFileName = "bgc_jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("h2.wp-block-heading").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!name.contains("教会")) return@mapNotNull null
            var sibling = heading.nextElementSibling()
            while (sibling != null && sibling.tagName() != "h2" && sibling.selectFirst("table") == null) {
                sibling = sibling.nextElementSibling()
            }
            val table = sibling?.selectFirst("table") ?: return@mapNotNull null
            val values = table.select("tr").associate { row ->
                row.selectFirst("th")?.text()?.trim().orEmpty() to row.selectFirst("td")
            }
            val address = values["住所"]?.text()?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
            if (address.isBlank()) return@mapNotNull null
            val links = table.select("a[href]")
            val ministers = values.entries.flatMap { (role, cell) ->
                if (cell != null && role.contains(Regex("牧師|伝道師|宣教師|教職"))) {
                    ChurchMinisterParser.fromRoleAndNames(role, cell.text())
                } else {
                    emptyList()
                }
            }
            OfficialDenominationChurch(
                name = name,
                address = address,
                phone = values["電話"]?.text()?.substringBefore("FAX")?.trim().orEmpty(),
                fax = values["電話"]?.text()?.substringAfter("FAX", "")?.trim().orEmpty(),
                websiteUrl = values["HP"]?.selectFirst("a[href]")?.absUrl("href").orEmpty(),
                email = DirectoryCrawlerSupport.extractEmail(table.text(), links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministers,
            )
        }.distinctBy { it.name }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        return church
    }
}
