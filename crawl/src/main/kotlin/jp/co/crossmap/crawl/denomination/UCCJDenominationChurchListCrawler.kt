package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class UCCJDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "UCCJ"
    override val denominationName: String = "日本基督教団"
    override val sourceUrl: String = "https://uccj.org/diocese"
    override val outputFileName: String = "uccj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("table.kyokai tr").mapNotNull { row ->
            val name = row.selectFirst("td.name")?.text()?.trim().orEmpty()
            if (name.isBlank() || name.startsWith("【") || name == "教会名") return@mapNotNull null
            val postalCode = row.selectFirst("td.postno")?.text()?.trim()?.removePrefix("〒").orEmpty()
            val location = row.selectFirst("td.address")?.text()?.trim().orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = listOfNotNull(
                    postalCode.takeIf(String::isNotBlank)?.let { "〒$it" },
                    location.takeIf(String::isNotBlank),
                ).joinToString(" "),
                jurisdiction = row.selectFirst("td.kyouku")?.text()?.trim().orEmpty(),
                phone = row.selectFirst("td.tel")?.text()?.trim().orEmpty(),
                fax = row.selectFirst("td.fax")?.text()?.trim().orEmpty(),
            )
        }
}
