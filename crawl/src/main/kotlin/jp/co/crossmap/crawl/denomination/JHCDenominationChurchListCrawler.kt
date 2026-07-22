package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JHCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "JHC"
    override val denominationName: String = "日本ホーリネス教団"
    override val sourceUrl: String = "https://jhc.or.jp/churches/locations.html"
    override val outputFileName: String = "jhc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val doc = Jsoup.parse(html, sourceUrl)
        val churches = mutableListOf<OfficialDenominationChurch>()
        doc.select("table").forEach { table ->
            table.select("tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size < 7) return@forEach
                val bgColor = row.attr("style")
                if ("c200c2" in bgColor.lowercase()) return@forEach
                val rawName = cells[2].text().trim()
                val name = rawName.substringBefore('（').substringBefore('（').trim()
                if (name.isBlank()) return@forEach
                val postalCode = cells[3].text().trim()
                val address = cells[4].text().trim()
                val fullAddress = if (postalCode.isNotBlank() && address.isNotBlank()) {
                    "〒$postalCode $address"
                } else if (postalCode.isNotBlank()) {
                    "〒$postalCode"
                } else {
                    address
                }
                val phone = phonePattern.find(cells[5].text())?.groupValues?.get(1)?.trim().orEmpty()
                val fax = faxPattern.find(cells[6].text())?.groupValues?.get(1)?.trim().orEmpty()
                churches += OfficialDenominationChurch(
                    name = name,
                    address = fullAddress,
                    jurisdiction = cells[1].text().trim(),
                    phone = phone,
                    fax = fax,
                )
            }
        }
        return churches
    }

    private companion object {
        val phonePattern = Regex("([0-9０-９()（）+\\-ー－‐/\\s]+)")
        val faxPattern = Regex("([0-9０-９()（）+\\-ー－‐/\\s]+)")
    }
}
