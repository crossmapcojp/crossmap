package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class KELCDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "KELC"
    override val denominationName = "近畿福音ルーテル教会"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "kelc-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select(".detail_box").mapNotNull { box ->
            val heading = box.selectFirst("h3")?.text()?.replace(Regex("[\\s　]+"), " ")?.trim().orEmpty()
            val name = heading.substringBefore(" 牧師").trim()
            if (name.isBlank()) return@mapNotNull null
            val text = box.text()
            val links = box.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.addressFromText(text),
                jurisdiction = regionNames[url.substringAfterLast('/').substringBefore('.')].orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text.replace("電話", "TEL")),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "kelc.net"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(heading),
            )
        }

    private companion object {
        val regionNames = mapOf("hyogo" to "兵庫地区", "osaka" to "大阪地区", "wakayama" to "和歌山地区", "nara" to "奈良地区", "mie" to "三重地区")
    }
}
