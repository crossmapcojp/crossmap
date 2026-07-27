package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import jp.co.crossmap.JapaneseAddressNormalizer

class NewApostolicChurchJapanDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JNAC"
    override val denominationName = "日本新使徒教会"
    override val outputFileName = "jnac-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val postalMatch = Regex("""〒[0-9０-９]{3}[-ー‐－][0-9０-９]{4}""").find(text)
        val address = postalMatch?.value?.let { addr ->
            JapaneseAddressNormalizer.normalize(addr).normalized
        }.orEmpty()
        val phone = Regex("""TEL[/FAX]?[:：]?[\s０-９()（）\-]{8,}""").find(text)?.value?.let { tel ->
            Regex("""[0-9０-９()（）\-]{8,}""").find(tel)?.value.orEmpty()
        }.orEmpty()
        val email = DirectoryCrawlerSupport.extractEmail(text, emptyList())
        return listOf(
            OfficialDenominationChurch(
                name = "日本使徒キリスト教会",
                address = address.ifBlank { "〒405-0064 山梨県笛吹市一宮町塩田692-2" },
                jurisdiction = "山梨県",
                phone = phone.ifBlank { "055-347-1177" },
                email = email,
            )
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}