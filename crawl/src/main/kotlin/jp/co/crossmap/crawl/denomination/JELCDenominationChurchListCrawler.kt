package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JELCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JELC"
    override val denominationName = "日本福音ルーテル教会"
    override val sourceUrl = "https://jelc.or.jp/all_churchs/"
    override val outputFileName = "jelc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return DirectoryCrawlerSupport.blocks(document, "tr, article, li, .church, .church_box, .wp-block-group")
            .mapNotNull { DirectoryCrawlerSupport.churchFromBlock(it, "jelc.or.jp") }
            .distinctBy { it.name to it.address }
    }
}
