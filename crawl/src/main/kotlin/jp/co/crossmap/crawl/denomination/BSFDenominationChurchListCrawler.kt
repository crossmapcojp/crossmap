package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class BSFDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BSF"
    override val denominationName = "聖書研究会"
    override val outputFileName = "bsf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val address = DirectoryCrawlerSupport.addressFromText(text)
        val phone = DirectoryCrawlerSupport.phoneFromText(text)
        val fax = DirectoryCrawlerSupport.faxFromText(text)
        val email = DirectoryCrawlerSupport.extractEmail(text, emptyList())
        return listOf(
            OfficialDenominationChurch(
                name = "聖書研究会",
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                fax = fax,
                email = email,
            )
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}