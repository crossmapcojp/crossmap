package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import jp.co.crossmap.JapaneseAddressNormalizer

class ChriterianFaithAssemblyDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CBA"
    override val denominationName = "キリスト信徒の集会"
    override val outputFileName = "cba-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val address = JapaneseAddressNormalizer.normalize(
            Regex("""〒?\s*[0-9０-９]{3}[-ー‐－][0-9０-９]{4}[^\n]*""")
                .find(text)?.value ?: ""
        ).normalized
        return listOf(
            OfficialDenominationChurch(
                name = "キリスト信徒の集会",
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            )
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}