package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class OBCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "OBC"
    override val denominationName = "沖縄バプテスト連盟"
    override val outputFileName = "obc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        var jurisdiction = ""
        return document.select("h2, h3").mapNotNull { heading ->
            val headingLines = heading.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
                .map { Jsoup.parseBodyFragment(it).text().trim() }
                .filter(String::isNotBlank)
            val japaneseName = headingLines.firstOrNull().orEmpty()
            if (!looksLikeChurchName(japaneseName)) {
                if (japaneseName.contains("/")) jurisdiction = japaneseName.substringBefore('/').trim()
                return@mapNotNull null
            }
            val sectionHtml = heading.nextElementSiblings()
                .takeWhile { it.tagName() != "h3" }
                .joinToString("\n") { it.outerHtml() }
            val section = Jsoup.parseBodyFragment(sectionHtml, sourceUrl).body()
            val sectionText = section.text()
            val address = DirectoryCrawlerSupport.addressFromText(sectionText).let { value ->
                if (value.isNotBlank() && !value.contains("沖縄県")) {
                    value.replace(Regex("^(〒?\\d{3}-\\d{4}\\s*)"), "$1沖縄県")
                } else value
            }
            val englishName = headingLines.drop(1).firstOrNull { it.any(Char::isLetter) }.orEmpty()
            OfficialDenominationChurch(
                name = japaneseName,
                localizedNames = englishName.takeIf(String::isNotBlank)?.let { listOf(LocalizedName("en", it)) }.orEmpty(),
                address = address,
                jurisdiction = jurisdiction,
                phone = DirectoryCrawlerSupport.phoneFromText(sectionText),
                fax = DirectoryCrawlerSupport.faxFromText(sectionText),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(section.select("a[href]"), "okinawabaptist.com"),
                email = DirectoryCrawlerSupport.extractEmail(sectionText, section.select("a[href]").map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(section.select("a[href]")),
                ministers = ChurchMinisterParser.parse(
                    section.select("h4, h5, p").map { it.text() }.filter { ministerRolePattern.containsMatchIn(it) }.joinToString(" "),
                ),
            )
        }
    }

    private fun looksLikeChurchName(value: String): Boolean =
        listOf("教会", "伝道所", "チャペル", "チャーチ", "祈りの家").any(value::contains) &&
            !value.contains("教会学校")

    private companion object {
        val ministerRolePattern = Regex("牧師|伝道師|宣教師|教職")
    }
}
