package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JapanBethelMissionDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JAPAN_BETHEL_MISSION"
    override val denominationName = "日本べテルミッション"
    override val outputFileName = "japan-bethel-mission-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val subMenu = document.select("nav ul.sub-menu li a")
        return subMenu.mapNotNull { link ->
            val name = link.text().trim()
            if (name.isBlank() || name == "リンク") return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }
}