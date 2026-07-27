package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class YouthWithAMissionJapanDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "YWAM"
    override val denominationName = "ユース・ウィズ・ア・ミッション"
    override val outputFileName = "ywam-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val locations = document.select(".js-id-location")
        if (locations.isNotEmpty()) {
            return locations.mapNotNull { loc ->
                val text = loc.text().trim()
                if (text.isBlank()) return@mapNotNull null
                val cleaned = text.replace(Regex("最終更新\\s*\\d{4}-\\d{2}-\\d{2}"), "").trim()
                val name = cleaned.substringBefore("場所").trim()
                    .takeUnless { it.isBlank() || it.startsWith("位置") || it.startsWith("学校") || it == "場所" }
                    ?: return@mapNotNull null
                val address = DirectoryCrawlerSupport.addressFromText(loc.text())
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    jurisdiction = "日本",
                )
            }.filter { it.name.isNotBlank() }
                .distinctBy(OfficialDenominationChurch::name)
        }
        return emptyList()
    }
}