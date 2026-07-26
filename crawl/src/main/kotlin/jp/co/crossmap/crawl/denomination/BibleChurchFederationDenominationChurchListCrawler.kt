package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class BibleChurchFederationDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BIBLE_CHURCH_FEDERATION"
    override val denominationName = "聖書教会連盟"
    override val outputFileName = "bible-church-federation-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 2) return@mapNotNull null
            val name = cells.first()?.text()?.trim().orEmpty()
            if (!churchNamePattern.containsMatchIn(name)) return@mapNotNull null

            val links = row.select("a[href]")
            val text = row.text().replace(phoneParentheses) { match ->
                "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
            }
            val address = DirectoryCrawlerSupport.addressFromText(text)
                .let(DirectoryCrawlerSupport::normalizeAddress)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text.replace("TEL&FAX", "TEL")),
                fax = DirectoryCrawlerSupport.faxFromText(text.replace("TEL&FAX", "FAX")),
                websiteUrl = links.asSequence()
                    .map { it.absUrl("href") }
                    .firstOrNull { it.isNotBlank() && !it.contains("1ran.html#") }
                    .orEmpty(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private companion object {
        val churchNamePattern = Regex("""(?:教会|チャーチ)$""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val phoneParentheses = Regex("""(\d{2,4})\((\d{2,4})\)(\d{4})""")
    }
}
