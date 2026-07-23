package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JBBFDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "JBBF"
    override val denominationName: String = "日本バプテスト・バイブル・フェローシップ"
    override val sourceUrl: String = "https://jbbf.or.jp/%e4%bd%8f%e6%89%80%e9%8c%b2/"
    override val outputFileName: String = "jbbf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("p").mapNotNull { paragraph ->
            val text = paragraph.text().trim()
            if ('〒' !in text) return@mapNotNull null

            val nameElement = paragraph.selectFirst("b, strong") ?: return@mapNotNull null
            val listedName = nameElement.text().trim()
            val name = listedName.substringBefore('（').trim()
            if (name.isBlank()) return@mapNotNull null

            val afterPostal = text.substringAfter('〒')
            val postalAndAddress = telephoneMarker.find(afterPostal)
                ?.let { afterPostal.substring(0, it.range.first) }
                ?.trim()
                ?: afterPostal.trim()
            val websiteUrl = nameElement.selectFirst("a[href]")
                ?.absUrl("href")
                ?.trim()
                .orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = "〒$postalAndAddress",
                jurisdiction = prefecturePattern.find(postalAndAddress)?.value?.trim().orEmpty(),
                phone = telephonePattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
                websiteUrl = websiteUrl,
                ministers = ChurchMinisterParser.parse(text),
                note = listedName.substringAfter('（', "").substringBeforeLast('）').trim(),
            )
        }

    private companion object {
        val telephoneMarker = Regex("[\\s　]+(?:TEL|電話)", RegexOption.IGNORE_CASE)
        val telephonePattern = Regex("(?:TEL|電話)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]+)", RegexOption.IGNORE_CASE)
        val prefecturePattern = Regex("北海道|東京都|大阪府|京都府|.{2,3}県")
    }
}
