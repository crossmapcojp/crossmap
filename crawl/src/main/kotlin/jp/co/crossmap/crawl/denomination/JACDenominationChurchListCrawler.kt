package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JACDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JAC"
    override val denominationName = "日本アライアンス教団"
    override val outputFileName = "jac-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("table.sp-table").flatMap { table ->
            val jurisdiction = table.previousElementSiblings().select("h2").eachText().firstOrNull()?.trim().orEmpty()
            table.select("tr").mapNotNull { row ->
                val cells = row.select("th, td")
                if (cells.size < 5 || cells.first()?.text()?.contains("教会名") == true) return@mapNotNull null
                val nameCell = cells[0]
                val name = nameCell.text().replace(Regex("\\s+"), " ").trim()
                if (name.isBlank() || !name.contains("教会")) return@mapNotNull null
                val postalCode = cells[2].text().trim()
                val rawAddress = cells[3].text().trim()
                OfficialDenominationChurch(
                    name = name,
                    address = DirectoryCrawlerSupport.normalizeAddress(listOf(postalCode.takeIf(String::isNotBlank)?.let { "〒$it" }, rawAddress).filterNotNull().joinToString(" ")),
                    jurisdiction = jurisdiction,
                    phone = cells[4].text().trim(),
                    websiteUrl = nameCell.selectFirst("a[href]")?.absUrl("href")?.trim().orEmpty(),
                    ministers = ChurchMinisterParser.fromRoleAndNames("牧師", cells[1].text()),
                )
            }
        }
    }
}
