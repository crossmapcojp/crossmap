package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class ChristianFellowshipDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CHRISTIAN_FELLOWSHIP"
    override val denominationName = "キリスト同信会"
    override val outputFileName = "christian_fellowship-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val content = document.selectFirst("div#post-597") ?: return emptyList()
        val headings = content.children().filter { it.tagName() == "h2" }
        val namedMeetings = headings
            .filter { it.text().trim().endsWith("集会") && it.text().trim() != "その他の集会" }
            .map { heading ->
                val section = siblingsUntilNextHeading(heading)
                val obsoleteVenue = section.any { it.text().contains("建物は老朽化したため、解体") }
                val address = if (obsoleteVenue) {
                    ""
                } else {
                    section.asSequence()
                        .mapNotNull { addressPattern.find(it.text())?.value }
                        .firstOrNull()
                        ?.let(DirectoryCrawlerSupport::normalizeAddress)
                        .orEmpty()
                }
                OfficialDenominationChurch(
                    name = heading.text().trim(),
                    address = address,
                    jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                )
            }
        val otherMeetings = headings
            .firstOrNull { it.text().trim() == "その他の集会" }
            ?.nextElementSibling()
            ?.select("strong")
            .orEmpty()
            .mapNotNull { strong ->
                strong.text().trim().trim('・', '。', '、')
                    .takeIf(String::isNotBlank)
                    ?.let { OfficialDenominationChurch(name = "${it}集会") }
            }
        return (namedMeetings + otherMeetings).distinctBy(OfficialDenominationChurch::name)
    }

    private fun siblingsUntilNextHeading(heading: Element): List<Element> {
        val result = mutableListOf<Element>()
        var sibling = heading.nextElementSibling()
        while (sibling != null && sibling.tagName() != "h2") {
            result.add(sibling)
            sibling = sibling.nextElementSibling()
        }
        return result
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val addressPattern = Regex(
            """〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}\s*""" +
                """(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)[^\s]+""",
        )
    }
}
