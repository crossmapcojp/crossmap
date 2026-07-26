package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JOACDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JOAC"
    override val denominationName = "日本オリベットアッセンブリー教団"
    override val outputFileName = "joac-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val currentEntries = document.select("ul.church-links li").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val name = link.text().trim()
            val jurisdiction = currentJurisdictions.entries.firstOrNull { (prefix) -> name.startsWith(prefix) }?.value.orEmpty()
            if (!churchNamePattern.containsMatchIn(name) || jurisdiction.isBlank()) return@mapNotNull null
            OfficialDenominationChurch(name = name, jurisdiction = jurisdiction, websiteUrl = link.absUrl("href"))
        }
        if (currentEntries.isNotEmpty()) return currentEntries.distinctBy(OfficialDenominationChurch::name)

        return document.select("li").mapNotNull { item ->
            val match = churchEntryPattern.matchEntire(item.text().trim()) ?: return@mapNotNull null
            val jurisdiction = match.groupValues[1]
            val name = match.groupValues[2].trim()
            val links = item.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                jurisdiction = jurisdiction,
                websiteUrl = links.firstOrNull()?.absUrl("href").orEmpty(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val churchEntryPattern = Regex(
            """^(北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)\s*[（(]\s*(.+(?:教会|チャペル))\s*[）)]$""",
        )
        val churchNamePattern = Regex("""(?:教会|チャペル)$""")
        val currentJurisdictions = linkedMapOf(
            "仙台" to "宮城県",
            "横浜" to "神奈川県",
            "東京" to "東京都",
            "愛知" to "愛知県",
            "大阪" to "大阪府",
            "広島" to "広島県",
            "福岡" to "福岡県",
        )
    }
}
