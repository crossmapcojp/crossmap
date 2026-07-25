package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class JFGCDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JFGC"
    override val denominationName = "日本フォースクエア福音教団"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "jfgc-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select(".church-item").mapNotNull { item ->
            val rawName = item.selectFirst(".church-name")?.text()?.trim().orEmpty()
            if (rawName.isBlank()) return@mapNotNull null
            val bilingual = Regex("^「(.+?)」\\s*(.+)$").matchEntire(rawName)
            val name = bilingual?.groupValues?.get(1)?.trim() ?: rawName.trim('「', '」', ' ')
            val englishName = bilingual?.groupValues?.get(2)?.trim().orEmpty()
            val text = item.text()
            val links = item.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                localizedNames = englishName.takeIf(String::isNotBlank)?.let { listOf(LocalizedName("en", it)) }.orEmpty(),
                address = DirectoryCrawlerSupport.addressFromText(text),
                jurisdiction = regionNames[url.substringAfterLast('/').substringBefore('.')].orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "japan-foursquare.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }

    private companion object {
        val regionNames = mapOf("6" to "北部", "22" to "関東", "21" to "中部", "23" to "関西・中国", "13" to "九州・沖縄")
    }
}
