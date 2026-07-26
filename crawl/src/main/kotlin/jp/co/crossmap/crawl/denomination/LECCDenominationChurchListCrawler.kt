package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class LECCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "LECC"
    override val denominationName = "ルーテル福音キリスト教会"
    override val outputFileName = "lecc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("main a[href]").mapNotNull { link ->
            val label = cleanText(link.text())
            val detailUrl = link.absUrl("href")
            if (label.isBlank() || detailUrl.isBlank()) return@mapNotNull null

            val churchHost = runCatching { URI(detailUrl).host }.getOrNull()
            OfficialDenominationChurch(
                name = label,
                websiteUrl = detailUrl.takeIf { churchHost != denominationHost }.orEmpty(),
                denominationChurchListDetailPage = detailUrl,
            )
        }.distinctBy(OfficialDenominationChurch::denominationChurchListDetailPage)

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val text = cleanText(document.body().text())
        val links = document.select("a[href]")
        val headings = document.select("h1, h2, h3").map { cleanText(it.text()) }
        val name = headings.firstOrNull(churchNamePattern::matches) ?: church.name
        val englishName = headings.firstOrNull { heading ->
            heading != name && englishChurchNamePattern.containsMatchIn(heading)
        }
        val addressLine = document.select("p").asSequence()
            .map { cleanText(it.text()) }
            .firstOrNull { postalCodePattern.containsMatchIn(it) }
            .orEmpty()
        val address = addressLine.substringFromPostalCode()
            .replace(contactSuffixPattern, "")
            .replace(duplicatedUtsunomiyaAddressSuffix, "")
            .let(DirectoryCrawlerSupport::normalizeAddress)
            .replace(katakanaLongVowelAsAddressDash, "ー")
        val pastor = ledByPastorPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank {
            telephoneSymbolPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        }.removeUnmatchedClosingParenthesis()

        return church.copy(
            name = name,
            address = address,
            jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            phone = phone,
            email = if (church.websiteUrl.isNotBlank()) {
                DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") })
            } else {
                ""
            },
            socialProfiles = if (church.websiteUrl.isNotBlank()) {
                DirectoryCrawlerSupport.socialProfiles(links)
            } else {
                emptyList()
            },
            ministers = pastor.takeIf(String::isNotBlank)
                ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                .orEmpty(),
            localizedNames = church.localizedNames.filterNot { it.languageCode == "en" } +
                listOfNotNull(englishName?.let { LocalizedName("en", it) }),
        )
    }

    private fun String.substringFromPostalCode(): String {
        val match = postalCodePattern.find(this) ?: return ""
        return substring(match.range.first)
    }

    private fun String.removeUnmatchedClosingParenthesis(): String {
        var result = trim()
        if (result.count { it == ')' } > result.count { it == '(' }) result = result.removeSuffix(")")
        if (result.count { it == '）' } > result.count { it == '（' }) result = result.removeSuffix("）")
        return result.trim()
    }

    private fun cleanText(value: String): String = value.replace(invisibleCharacters, "").trim()

    private val denominationHost = URI(sourceUrl).host

    private companion object {
        val churchNamePattern = Regex(""".+(?:教会|チャペル)$""")
        val englishChurchNamePattern = Regex("""\bChurch\b""", RegexOption.IGNORE_CASE)
        val postalCodePattern = Regex("""〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}""")
        val contactSuffixPattern = Regex(
            """\s*(?:TEL|Tel|電話(?:とFAX)?|℡|email|Email)\s*[:：]?.*$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val duplicatedUtsunomiyaAddressSuffix = Regex("""(?<=[０-９])\d+-\d+-\d+$""")
        val katakanaLongVowelAsAddressDash = Regex("""(?<=[ァ-ヶ])−(?=[ァ-ヶ])""")
        val ledByPastorPattern = Regex(
            """This church is led by Pastor\s+(.+?)(?=[.。]|$)""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val telephoneSymbolPattern = Regex("""℡\s*([0-9０-９()（）+\-ー－‐/\s　]{8,})""")
        val prefecturePattern = Regex("""(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)""")
        val invisibleCharacters = Regex("""[\u200B-\u200D\u2060\uFEFF]""")
    }
}
