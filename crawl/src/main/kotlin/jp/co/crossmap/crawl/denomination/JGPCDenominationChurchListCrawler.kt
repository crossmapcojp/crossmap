package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup

class JGPCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JGPC"
    override val denominationName = "日本福音ペンテコステ教団"
    override val outputFileName = "jgpc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("table tr").mapNotNull { row ->
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            val name = link.text().trim()
            if (!churchNamePattern.containsMatchIn(name)) return@mapNotNull null

            val region = row.select("td").map { it.text().trim() }
                .firstOrNull { regionPattern.matches(it) }
                ?.replace(Regex("""[\[\]\s]"""), "")
                .orEmpty()
            val pastorNames = link.parent()?.nextElementSibling()?.text().orEmpty()
                .trim().removeSuffix("師").trim()
            val url = link.absUrl("href")
            val internalDetail = runCatching { URI(url).host == sourceHost }.getOrDefault(false)
            OfficialDenominationChurch(
                name = name,
                jurisdiction = jurisdiction(region),
                websiteUrl = url.takeUnless { internalDetail }.orEmpty(),
                denominationChurchListDetailPage = url.takeIf { internalDetail }.orEmpty(),
                ministers = pastorNames.takeIf(String::isNotBlank)
                    ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                    .orEmpty(),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val text = document.body().text()
        val links = document.select("a[href]")
        val addressText = text.replace(emailAndFollowing, "")
        val address = addMissingJurisdiction(
            canonicalizePostalCode(
                DirectoryCrawlerSupport.addressFromText(addressText).replace(nonCanonicalHyphen, "−"),
            ),
            church.jurisdiction,
        )
        return church.copy(
            address = address,
            phone = DirectoryCrawlerSupport.phoneFromText(text).replace(nonCanonicalHyphen, "-"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = church.ministers,
        )
    }

    private fun jurisdiction(region: String): String = when (region) {
        "北海道" -> "北海道"
        "東京" -> "東京都"
        "京都" -> "京都府"
        "大阪" -> "大阪府"
        "" -> ""
        else -> "${region}県"
    }

    private fun addMissingJurisdiction(address: String, jurisdiction: String): String {
        if (address.isBlank() || jurisdiction.isBlank() || prefecturePattern.containsMatchIn(address)) return address
        val postal = postalPattern.find(address) ?: return "$jurisdiction$address"
        val prefix = address.substring(0, postal.range.last + 1)
        val rest = address.substring(postal.range.last + 1).trimStart()
        return "$prefix $jurisdiction$rest"
    }

    private fun canonicalizePostalCode(address: String): String {
        val match = postalPattern.find(address) ?: return address
        val normalized = buildString(match.value.length) {
            match.value.forEach { character ->
                append(
                    when {
                        character in '０'..'９' -> (character.code - 0xFEE0).toChar()
                        character in "－ー‐−" -> '-'
                        character.isWhitespace() -> return@forEach
                        else -> character
                    },
                )
            }
        }
        return address.replaceRange(match.range, normalized)
    }

    private companion object {
        const val sourceHost = "jgpc.jimdofree.com"
        val churchNamePattern = Regex("""(?:教会|チャーチ|チャペル)""")
        val regionPattern = Regex("""\[\s*[^]]+\s*]""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val postalPattern = Regex("""^〒?\s*[0-9０-９]{3}[-－ー‐−][0-9０-９]{4}""")
        val emailAndFollowing = Regex("""\s+(?:E-?mail|メール)[：:].*$""", RegexOption.IGNORE_CASE)
        val nonCanonicalHyphen = Regex("""[－ー‐]""")
    }
}
