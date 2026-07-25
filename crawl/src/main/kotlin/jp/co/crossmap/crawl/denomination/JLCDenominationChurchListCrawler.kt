package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JLCDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JLC"
    override val denominationName = "日本ルーテル教団"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "jlc-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select(".fl-callout").mapNotNull { callout ->
            val name = callout.selectFirst(".fl-callout-title")?.text()?.trim().orEmpty()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val text = callout.text()
            val links = callout.select("a[href]")
            OfficialDenominationChurch(
                name = name.substringBefore(" (").trim(),
                address = DirectoryCrawlerSupport.addressFromText(text),
                jurisdiction = regionNames[url.substringAfter("/area/").substringBefore('/')].orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "jlc.or.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "伝道所", "チャペル").any(name::contains)

    private companion object {
        val regionNames = mapOf("hokkaido" to "北海道地区", "nigata" to "新潟地区", "kanto" to "関東地区", "okinawa" to "沖縄地区")
    }
}
