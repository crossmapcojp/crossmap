package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class KECCJCCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "KECCJCC"
    override val denominationName = "カンバーランド長老キリスト教会日本中会"
    override val outputFileName = "keccjcc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("table tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size != 3) return@mapNotNull null

            val name = cells[0].text().replace(leadingMarker, "").trim()
            val address = DirectoryCrawlerSupport.normalizeAddress(cells[2].text())
            if (!churchNamePattern.containsMatchIn(name) || !prefecturePattern.containsMatchIn(address)) {
                return@mapNotNull null
            }

            val links = cells[0].select("a[href]")
            val pastorNames = cells[1].html()
                .replace(lineBreak, "、")
                .let(Jsoup::parseBodyFragment)
                .text()
                .trim()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.cumberland.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = pastorNames.takeIf(String::isNotBlank)
                    ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                    .orEmpty(),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private companion object {
        val leadingMarker = Regex("""^[ー―−-]\s*""")
        val churchNamePattern = Regex("""(?:教会|チャペル)$""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val lineBreak = Regex("""(?i)<br\s*/?>""")
    }
}
