package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class BarnabasEvangelicalMissionSocietyDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BEMS"
    override val denominationName = "バルナバ福音宣教会"
    override val outputFileName = "bems-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val address = DirectoryCrawlerSupport.addressFromText(text)
        val phone = DirectoryCrawlerSupport.phoneFromText(text)
        return listOf(
            OfficialDenominationChurch(
                name = "バルナバ福音宣教会",
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
            )
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}