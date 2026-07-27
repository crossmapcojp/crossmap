package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class GospelBaptistFederationDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "GBF"
    override val denominationName = "福音バプテスト連合"
    override val outputFileName = "gbf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val address = DirectoryCrawlerSupport.addressFromText(text)
        return listOf(
            OfficialDenominationChurch(
                name = "福音バプテスト連合",
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            )
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}