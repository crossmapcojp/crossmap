package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TokyoHorizonChapelDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "THC"
    override val denominationName = "東京ホライズンチャペル"
    override val outputFileName = "thc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val headings = document.select("h3")
        return headings.mapNotNull { heading ->
            val name = heading.text().trim()
            if (name.isBlank() || name == "礼拝案内" || name == "サービス") return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                jurisdiction = "東京都",
            )
        }.filter { it.name.contains(Regex("チャペル|教会")) }
            .distinctBy(OfficialDenominationChurch::name)
    }
}