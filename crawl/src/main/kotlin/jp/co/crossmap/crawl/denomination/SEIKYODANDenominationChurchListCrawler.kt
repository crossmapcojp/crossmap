package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class SEIKYODANDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "SEIKYODAN"
    override val denominationName = "基督聖協団"
    override val outputFileName = "seikyodan-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("a[name], table").forEach { element ->
            if (element.tagName() == "a") {
                jurisdiction = jurisdictions[element.attr("name").lowercase()].orEmpty().ifBlank { jurisdiction }
                return@forEach
            }
            element.select("tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size < 2) return@forEach
                val name = cells[0].text().trim()
                val address = DirectoryCrawlerSupport.addressFromText(cells[1].text().replace(Regex("▲?もどる.*"), ""))
                if (name.isBlank() || address.isBlank() || !looksLikeChurchName(name)) return@forEach
                churches += OfficialDenominationChurch(
                    name = name,
                    address = address,
                    jurisdiction = jurisdiction,
                    websiteUrl = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty(),
                )
            }
        }
        return churches
    }

    private fun looksLikeChurchName(name: String) = listOf("教会", "教会堂", "センター", "チャペル").any(name::contains)

    private companion object {
        val jurisdictions = mapOf(
            "hottkaido" to "北海道地区", "touhoku" to "東北地区", "kanto" to "関東地区",
            "tyubu" to "中部地区", "kyusyu" to "九州地区",
        )
    }
}
