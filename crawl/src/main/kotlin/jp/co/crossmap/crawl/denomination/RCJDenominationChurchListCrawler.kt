package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class RCJDenominationChurchListCrawler : DenominationChurchListCrawler {
    override val denominationId: String = "RCJ"
    override val denominationName: String = "日本キリスト改革派教会"
    override val sourceUrl: String = "https://www.rcj.gr.jp/_church_list/result_keyword.php"
    override val outputFileName: String = "rcj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val doc = Jsoup.parse(html, sourceUrl)
        val churches = mutableListOf<OfficialDenominationChurch>()
        doc.select("section").forEach { section ->
            val h3 = section.selectFirst("h3") ?: return@forEach
            val name = h3.text().trim()
            if (name.isBlank()) return@forEach
            val h4 = section.selectFirst("h4") ?: return@forEach
            val addressText = h4.text().trim()
            val address = if (addressText.startsWith('〒')) addressText else "〒$addressText"
            churches += OfficialDenominationChurch(
                name = name,
                address = address,
            )
        }
        return churches
    }
}
