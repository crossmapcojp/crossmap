package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class MinoMissionDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "MINO_MISSION"
    override val denominationName = "美濃ミッション"
    override val outputFileName = "mino-mission-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("h3").mapNotNull { heading ->
            val name = heading.text().trim()
            if (name == "コンテンツ" || name.isBlank()) return@mapNotNull null
            if (!name.contains(Regex("教会|チャペル"))) return@mapNotNull null
            val link = heading.selectFirst("a[href]")
            val detailUrl = link?.absUrl("href") ?: ""
            OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = detailUrl,
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }
}