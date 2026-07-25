package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class WJELCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "WJELC"
    override val denominationName = "西日本福音ルーテル教会"
    override val outputFileName = "wjelc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var prefecture = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("h2, div.linkarea").forEach { element ->
            if (element.tagName() == "h2") {
                prefecture = Regex("(?:北海道|東京都|京都府|大阪府|.{2,3}県)").find(element.text())?.value.orEmpty()
                return@forEach
            }
            val name = element.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            val text = element.text()
            var address = DirectoryCrawlerSupport.addressFromText(text.replace(phonePattern, ""))
            if (prefecture.isNotBlank() && !address.contains(prefecture)) {
                address = address.replace(Regex("^(〒?\\d{3}-\\d{4}\\s*)"), "$1$prefecture")
            }
            val links = element.parents().firstOrNull { it.hasClass("grid-1-4") }?.select("a[href]").orEmpty()
            churches += OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecture,
                phone = DirectoryCrawlerSupport.phoneFromText("TEL ${phonePattern.find(text)?.value.orEmpty()}"),
                websiteUrl = element.attr("data-href").trim(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }
        return churches
    }

    private companion object {
        val phonePattern = Regex("(?<!\\d)0\\d{1,4}-\\d{1,4}-\\d{3,4}(?!\\d)")
    }
}
