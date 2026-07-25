package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class SEIYAKUDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "SEIYAKU"
    override val denominationName = "日本聖約キリスト教団"
    override val outputFileName = "seiyaku-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("table tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 4) return@mapNotNull null
            val name = cells[0].text().trim()
            if (name == "教会名" || !looksLikeChurchName(name)) return@mapNotNull null
            val contact = cells[3].text()
            val address = DirectoryCrawlerSupport.normalizeAddress(cells[2].text())
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(contact),
                fax = DirectoryCrawlerSupport.faxFromText(contact),
                websiteUrl = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty(),
                ministers = ChurchMinisterParser.fromRoleAndNames("牧師", cells[1].text()),
            )
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャーチ", "チャペル").any(name::contains)

    private companion object {
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)")
    }
}
