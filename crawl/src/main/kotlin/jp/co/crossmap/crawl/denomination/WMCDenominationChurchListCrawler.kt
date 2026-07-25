package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class WMCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "WMC"
    override val denominationName = "ワールドミッション教団"
    override val outputFileName = "wmc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("table").flatMap { table ->
            val jurisdiction = table.parents().firstOrNull { parent -> parent.selectFirst("h2.elementor-heading-title") != null }
                ?.selectFirst("h2.elementor-heading-title")?.text()?.trim().orEmpty()
            table.select("tr").mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size < 5) return@mapNotNull null
                val rawName = cells[0].text().replace(Regex("^\\(宗\\)"), "").trim()
                val name = rawName.replace(Regex("\\s*[（(]教団事務局[）)]"), "").trim()
                if (name.isBlank() || name == "教会名") return@mapNotNull null
                val postalCode = cells[2].text().trim()
                val rawAddress = cells[3].text().trim()
                OfficialDenominationChurch(
                    name = name,
                    address = DirectoryCrawlerSupport.normalizeAddress(listOf(postalCode.takeIf(String::isNotBlank)?.let { "〒$it" }, rawAddress).filterNotNull().joinToString(" ")),
                    jurisdiction = jurisdiction,
                    phone = cells[4].text().trim(),
                    fax = cells.getOrNull(5)?.text()?.trim().orEmpty(),
                    websiteUrl = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty(),
                    ministers = ChurchMinisterParser.fromRoleAndNames("教職", cells[1].text()),
                    note = rawName.takeIf { it != name }?.substringAfter(name)?.trim().orEmpty(),
                )
            }
        }
    }
}
