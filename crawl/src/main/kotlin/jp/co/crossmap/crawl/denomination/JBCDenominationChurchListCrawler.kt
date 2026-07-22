package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JBCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "JBC"
    override val denominationName: String = "日本バプテスト連盟"
    override val sourceUrl: String = "https://bapren.jp/church/"
    override val outputFileName: String = "jbc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        return Jsoup.parse(html, sourceUrl).select("table.church-table tr").mapNotNull { row ->
            row.selectFirst("td.kyoku")?.text()?.trim()?.takeIf(String::isNotBlank)?.let {
                jurisdiction = it
                return@mapNotNull null
            }
            val cells = row.select("td")
            val nameCell = row.selectFirst("td.c-name") ?: return@mapNotNull null
            val nameIndex = cells.indexOf(nameCell)
            val rawName = nameCell.text().trim()
            val pending = rawName.contains("加盟申請")
            val name = rawName.substringBefore("※").trim()
            if (name.isBlank()) return@mapNotNull null
            val postalCode = cells.getOrNull(nameIndex + 1)?.text()?.trim().orEmpty()
            val location = cells.getOrNull(nameIndex + 2)?.text()?.trim().orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = listOf(postalCode, location).filter(String::isNotBlank).joinToString(" "),
                jurisdiction = jurisdiction,
                phone = cells.getOrNull(nameIndex + 3)?.text()?.trim().orEmpty().removePrefix("TEL").trim(),
                membershipStatus = if (pending) OfficialChurchMembershipStatus.PENDING else OfficialChurchMembershipStatus.LISTED,
                note = rawName.substringAfter("※", "").trim(),
            )
        }
    }
}
