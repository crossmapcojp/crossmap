package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicKyotoDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "kyoto"
    override val jurisdictionNames = setOf("京都教区・京都府", "京都教区・滋賀県", "京都教区・奈良県", "京都教区・三重県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        var prefecture = "京都府"
        return Jsoup.parse(html, url).select("tr").mapNotNull { row ->
            val rowText = row.text().replace(Regex("\\s+"), "")
            listOf("京都府", "滋賀県", "奈良県", "三重県").firstOrNull { rowText.contains(it) }?.let { prefecture = it }
            val cells = row.select("td")
            if (cells.size < 6 || !postalPattern.containsMatchIn(cells[3].text())) return@mapNotNull null
            val rawName = cells[0].text().replace(Regex("[A-Z<].*$"), "")
                .replace(Regex("\\s+"), "").replace("（巡）", "").trim()
            if (rawName.isBlank()) return@mapNotNull null
            val patrol = cells[0].text().contains("巡")
            val name = "カトリック${rawName}教会" + if (patrol) "（巡回）" else ""
            val website = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.normalizeAddress("〒${cells[3].text()}"),
                jurisdiction = "京都教区・$prefecture", phone = cells[4].text().takeUnless { it == "-" }.orEmpty(),
                fax = cells[5].text().takeUnless { it == "-" }.orEmpty(), websiteUrl = website,
            )
        }.distinctBy { it.name to it.address }
    }

    private companion object {
        val postalPattern = Regex("[0-9０-９]{3}[-‐－ー][0-9０-９]{4}")
    }
}
