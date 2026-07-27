package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class ECCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "ECC"
    override val denominationName = "エバンジェリカル・コングリゲーショナル・チャーチ"
    override val outputFileName = "ecc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val links = document.select("a[href]")
        val churches = listOf(
            OfficialDenominationChurch(
                name = "経堂めぐみ教会",
                address = "東京都世田谷区",
                jurisdiction = "東京都",
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "gracegardenchurch.com"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            ),
            OfficialDenominationChurch(
                name = "グレースガーデンチャペル",
                address = "神奈川県海老名市",
                jurisdiction = "神奈川県",
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "gracegardenchurch.com"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            ),
            OfficialDenominationChurch(
                name = "アメリカEC教会",
                address = "",
                jurisdiction = "",
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "gracegardenchurch.com"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            ),
        )
        return churches
    }
}