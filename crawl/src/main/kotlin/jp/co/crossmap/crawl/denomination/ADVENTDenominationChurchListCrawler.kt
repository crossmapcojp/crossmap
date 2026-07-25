package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class ADVENTDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "ADVENT"
    override val denominationName = "日本アドベント・キリスト教団"
    override val outputFileName = "advent-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("h1").mapNotNull { heading ->
            val rawName = heading.text().trim()
            val name = rawName.substringBefore('＝').trim()
            if (!looksLikeChurchName(name) || rawName.contains("関連団体")) return@mapNotNull null
            val section = Jsoup.parseBodyFragment(
                heading.nextElementSiblings().takeWhile { it.tagName() != "h1" }.joinToString("\n") { it.outerHtml() },
                sourceUrl,
            )
            val text = section.text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phonePattern.find(text)?.value.orEmpty(),
                websiteUrl = heading.selectFirst("a[href]")?.absUrl("href").orEmpty(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(section.select("a[href]")),
                ministers = pastorPattern.findAll(text).flatMap { match ->
                    ChurchMinisterParser.fromRoleAndNames("牧師", match.groupValues[1]).asSequence()
                }.distinctBy { it.name }.toList(),
                note = rawName.substringAfter('＝', "").trim(),
            )
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "ハウス", "チャペル").any(name::contains)

    private companion object {
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)")
        val phonePattern = Regex("[0-9０-９]{2,5}[（(ー－‐-][0-9０-９]{2,5}[）)ー－‐-][0-9０-９]{3,5}")
        val pastorPattern = Regex("([\\p{L}][\\p{L}　 ・･]{1,24})牧師")
    }
}
