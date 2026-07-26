package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class COGJpDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "COG_JP"
    override val denominationName = "チャーチ・オブ・ゴッド"
    override val outputFileName = "cog-jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("div.paragraph")
        .mapNotNull { paragraph ->
            val match = churchPattern.matchEntire(paragraph.text().trim()) ?: return@mapNotNull null
            val links = followingChurchElements(paragraph).flatMap { it.select("a[href]") }
            OfficialDenominationChurch(
                name = match.groupValues[1].trim(),
                jurisdiction = match.groupValues[2],
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.cogjapan.com"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }

    private fun followingChurchElements(paragraph: Element): List<Element> = buildList {
        var element = paragraph.nextElementSibling()
        while (element != null && !element.hasClass("paragraph") && element.tagName() != "h2") {
            add(element)
            element = element.nextElementSibling()
        }
    }

    private companion object {
        val churchPattern = Regex("""^(.+(?:教会|チャペル))\s*[（(](北海道|東京都|京都府|大阪府|.{2,3}県)[）)]$""")
    }
}
