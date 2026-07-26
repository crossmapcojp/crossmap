package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class GFADenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "GFA"
    override val denominationName = "福音交友会"
    override val outputFileName = "gfa-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val candidates = Jsoup.parse(html, sourceUrl).select("font[color=#990000]").mapIndexedNotNull { index, heading ->
            if (heading.children().isNotEmpty()) return@mapIndexedNotNull null
            val name = heading.text().trim()
            if (name.isBlank() || name.length > 40 || name.contains("所属教会") ||
                listOf("教会", "チャペル").none(name::contains)
            ) return@mapIndexedNotNull null
            val row = heading.closest("table") ?: return@mapIndexedNotNull null
            val text = row.text().replace(Regex("""\s+"""), " ").trim()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            val links = row.select("a[href]")
            val phone = phonePattern.find(text)?.groupValues?.get(1).orEmpty()
            val faxValue = faxPattern.find(text)?.groupValues?.get(1).orEmpty()
            ParsedCandidate(
                index = index,
                sourceLength = text.length,
                church = OfficialDenominationChurch(
                    name = name,
                    address = address,
                    jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                    phone = phone,
                    fax = if (sameFaxPattern.containsMatchIn(text)) phone else faxValue,
                    websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "fkk-web.net"),
                    email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.absUrl("href") }),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                    ministers = ChurchMinisterParser.parse(text.replace(parenthesizedPastorPattern, "$1$2")),
                ),
            )
        }
        return candidates.groupBy { it.church.name }.values
            .map { duplicates -> duplicates.minBy(ParsedCandidate::sourceLength) }
            .sortedBy(ParsedCandidate::index)
            .map(ParsedCandidate::church)
    }

    private data class ParsedCandidate(
        val index: Int,
        val sourceLength: Int,
        val church: OfficialDenominationChurch,
    )

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val phonePattern = Regex("""TEL(?:/FAX)?[：:]\s*([0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4})""", RegexOption.IGNORE_CASE)
        val faxPattern = Regex("""FAX[：:]\s*([0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4})""", RegexOption.IGNORE_CASE)
        val sameFaxPattern = Regex("""FAX[：:]\s*同""", RegexOption.IGNORE_CASE)
        val parenthesizedPastorPattern = Regex("""((?:協力)?牧師[：:])\s*[（(]([^）)]+)[）)]""")
    }
}
