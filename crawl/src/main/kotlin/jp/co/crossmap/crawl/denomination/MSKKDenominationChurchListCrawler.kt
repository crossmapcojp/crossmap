package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class MSKKDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "NSKK"
    override val denominationName = "日本聖契キリスト教団"
    override val outputFileName = "mskk-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var district = ""
        var prefecture = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("h2.wp-block-heading").forEach { heading ->
            val headingText = heading.text().trim()
            when {
                headingText.endsWith("教区") -> district = headingText
                prefecturePattern.matches(headingText) -> prefecture = headingText
                !looksLikeChurchName(headingText) -> return@forEach
                else -> {
                    val sectionHtml = heading.nextElementSiblings().takeWhile { it.tagName() != "h2" }
                        .joinToString("\n") { it.outerHtml() }
                    val section = Jsoup.parseBodyFragment(sectionHtml, sourceUrl)
                    val text = section.text()
                    val links = section.select("a[href]")
                    val (name, englishName) = splitEnglishName(headingText)
                    churches += OfficialDenominationChurch(
                        name = name,
                        localizedNames = englishName.takeIf(String::isNotBlank)?.let { listOf(LocalizedName("en", it)) }.orEmpty(),
                        address = DirectoryCrawlerSupport.addressFromText(text),
                        jurisdiction = listOf(district, prefecture).filter(String::isNotBlank).joinToString(" / "),
                        phone = DirectoryCrawlerSupport.phoneFromText(text),
                        websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "nskk.gr.jp"),
                        socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                        ministers = ChurchMinisterParser.parse(text),
                    )
                }
            }
        }
        return churches
    }

    private fun splitEnglishName(value: String): Pair<String, String> {
        val match = Regex("^(.+?教会)\\s+([A-Za-z].+)$").matchEntire(value) ?: return value to ""
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャーチ", "フェローシップ").any(name::contains)

    private companion object {
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|.{2,3}県)")
    }
}
