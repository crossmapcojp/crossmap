package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class JMBCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JMBC"
    override val denominationName = "日本メノナイトブレザレン教団"
    override val outputFileName = "jmbc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("h2.wp-block-heading, div.wp-block-columns").forEach { element ->
            if (element.tagName() == "h2") {
                jurisdiction = element.text().substringBefore('(').trim()
                return@forEach
            }
            val content = element.children().firstOrNull { it.selectFirst("h5.wp-block-heading") != null } ?: return@forEach
            val heading = content.selectFirst("h5.wp-block-heading")?.text()?.trim().orEmpty()
            val match = namePattern.matchEntire(heading) ?: return@forEach
            val japaneseName = match.groupValues[1].trim()
            val englishName = match.groupValues[2].trim()
            val address = content.select("p").map { it.text().trim() }
                .firstOrNull { prefecturePattern.containsMatchIn(it) }
                ?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
            val links = content.select("a[href]")
            churches += OfficialDenominationChurch(
                name = japaneseName,
                localizedNames = listOf(LocalizedName("en", englishName)),
                address = address,
                jurisdiction = jurisdiction,
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "jmbc.japan-mb.com"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(
                    content.select("p").map { it.text() }.filter { ministerRolePattern.containsMatchIn(it) }.joinToString(" "),
                ),
            )
        }
        return churches
    }

    private companion object {
        val namePattern = Regex("^(.+?)[（(]([^）)]+)[）)]$")
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|.{2,3}県)")
        val ministerRolePattern = Regex("牧師|伝道師|宣教師|教職")
    }
}
