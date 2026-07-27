package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class InternationalChapelMinistriesDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "ICM"
    override val denominationName = "インターナショナル・チャペル・ミニストリーズ"
    override val outputFileName = "icm-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val postalMatch = Regex("""〒[0-9０-９]{3}[-ー‐－][0-9０-９]{4}""").find(text)
        val address = postalMatch?.value?.let { addr ->
            addr.replace(Regex("""[^〒0-9０-９\-ー－‐ ]"""), "")
        }.orEmpty()
        val phone = Regex("""TEL/FAX[:：]?[\s０-９()（）\-．]{8,}""").find(text)?.value?.let { tel ->
            Regex("""[0-9０-９()（）\-．]{8,}""").find(tel)?.value.orEmpty()
        }.orEmpty()
        val email = DirectoryCrawlerSupport.extractEmail(text, emptyList())
        return listOf(
            OfficialDenominationChurch(
                name = "生駒インターナショナルチャペル",
                address = address.ifBlank { "〒630-0243 奈良県生駒市俵口町983-2" },
                jurisdiction = "奈良県",
                phone = phone,
                email = email,
            ),
            OfficialDenominationChurch(
                name = "京田辺チャペル",
                address = "",
                jurisdiction = "京都府",
                phone = phone,
                email = email,
            ),
        )
    }
}