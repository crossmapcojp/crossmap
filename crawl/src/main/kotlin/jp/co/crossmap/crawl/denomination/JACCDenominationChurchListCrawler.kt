package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JACCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "JACC"
    override val denominationName: String = "日本同盟基督教団"
    override val sourceUrl: String = "https://db.jacc.info/database/db_list.php"
    override val outputFileName: String = "jacc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val doc = Jsoup.parse(html, sourceUrl)
        val rows = doc.select("table tr")
        val churches = mutableListOf<OfficialDenominationChurch>()
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            val bgColor = row.attr("bgcolor")
            if (!bgColor.equals("#FFFF99", ignoreCase = true)) {
                i++
                continue
            }
            val headerCells = row.select("td")
            if (headerCells.size < 3) {
                i++
                continue
            }
            val rawName = headerCells[0].selectFirst("strong")?.text()?.trim().orEmpty()
            if (rawName.isBlank()) {
                i++
                continue
            }
            val name = rawName.substringBefore("（").trim()
            if (name.isBlank()) {
                i++
                continue
            }

            val addressCell = headerCells[2]
            val addressStrong = addressCell.selectFirst("strong")
            val address = addressStrong?.text()?.trim().orEmpty()

            var jurisdiction = ""
            var phone = ""
            var fax = ""
            var websiteUrl = ""

            if (i + 1 < rows.size) {
                val jurisdictionRow = rows[i + 1]
                val jurisdictionCells = jurisdictionRow.select("td")
                jurisdiction = jurisdictionCells.firstOrNull()?.text()?.trim().orEmpty()
                val phoneFaxText = jurisdictionCells.lastOrNull()?.text()?.trim().orEmpty()
                phone = phonePattern.find(phoneFaxText)?.groupValues?.get(1)?.trim().orEmpty()
                fax = faxPattern.find(phoneFaxText)?.groupValues?.get(1)?.trim().orEmpty()
                if (fax in listOf("なし", "N/A")) fax = ""
            }

            if (i + 3 < rows.size) {
                val websiteRow = rows[i + 3]
                val websiteCells = websiteRow.select("td")
                if (websiteCells.size >= 3) {
                    val href = websiteCells[2].selectFirst("a[href]")
                        ?.attr("href")
                        ?.trim()
                        .orEmpty()
                    websiteUrl = when {
                        href.isBlank() -> ""
                        href.startsWith("http://") || href.startsWith("https://") -> href
                        else -> "https://$href"
                    }
                }
            }

            churches += OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = jurisdiction,
                phone = phone,
                fax = fax,
                websiteUrl = websiteUrl,
            )
            i++
        }
        return churches
    }

    private companion object {
        val phonePattern = Regex("電話：\\[([^\\]]*)\\]")
        val faxPattern = Regex("FAX：\\[([^\\]]*)\\]")
    }
}
