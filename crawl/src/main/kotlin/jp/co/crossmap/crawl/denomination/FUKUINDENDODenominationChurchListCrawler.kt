package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class FUKUINDENDODenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "FUKUIN_DENDO"
    override val denominationName = "日本伝道福音教団"
    override val outputFileName = "fukuin_dendo-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("h2").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val section = Jsoup.parseBodyFragment(
                heading.nextElementSiblings().takeWhile { it.tagName() != "h2" }.joinToString("\n") { it.outerHtml() },
                sourceUrl,
            )
            val text = section.text()
            val links = section.select("a[href]")
            val address = DirectoryCrawlerSupport.addressFromText(text)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text.replace("電話：", "TEL：")),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "church.ne.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャペル").any(name::contains)

    private companion object {
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)")
    }
}
