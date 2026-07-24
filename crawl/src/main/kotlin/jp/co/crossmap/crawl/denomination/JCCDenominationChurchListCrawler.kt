package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

/** The requested JCC directory maps to the canonical CCJ denomination ID; JCC is already used by 日本キリストの教会. */
class JCCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CCJ"
    override val denominationName = "日本キリスト教会"
    override val sourceUrl = "http://www.nikki-church.org/data.htm"
    override val outputFileName = "jcc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val legacyRows = document.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 6) return@mapNotNull null
            val name = cells[0].text().trim()
            val postal = cells[1].text().trim()
            val street = cells[2].text().trim()
            if (!Regex("教会|伝道所").containsMatchIn(name) || !Regex("[0-9０-９]{3}[-ー－−][0-9０-９]{4}").containsMatchIn(postal)) {
                return@mapNotNull null
            }
            val pastorRoster = cells[5].html()
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "、")
                .let { Jsoup.parseBodyFragment(it).text() }
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.normalizeAddress("〒$postal $street"),
                phone = cells[3].text().trim(),
                fax = cells[4].text().trim().takeUnless { it == "同" }.orEmpty(),
                ministers = parsePastorRoster(pastorRoster),
            )
        }
        val selectors = "tr, table, p, li"
        val postalPattern = Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}")
        val genericRows = document.select(selectors)
            .filter { postalPattern.containsMatchIn(it.text()) }
            .filter { element -> element.select(selectors).none { it !== element && postalPattern.containsMatchIn(it.text()) } }
            .mapNotNull { row ->
                val text = row.text().trim()
                val address = DirectoryCrawlerSupport.addressFromText(text)
                val name = row.select("strong,b,a,th,td")
                    .map { it.ownText().ifBlank { it.text() }.trim() }
                    .firstOrNull { Regex("教会|伝道所").containsMatchIn(it) && !postalPattern.containsMatchIn(it) && it.length <= 80 }
                    .orEmpty()
                if (name.isBlank() || address.isBlank()) return@mapNotNull null
                val links = row.select("a[href]")
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = DirectoryCrawlerSupport.phoneFromText(text),
                    fax = DirectoryCrawlerSupport.faxFromText(text),
                    websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.nikki-church.org"),
                    email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                    ministers = ChurchMinisterParser.parse(text),
                )
            }
        return (legacyRows.ifEmpty { genericRows }).distinctBy { it.name to it.address }
    }

    private fun parsePastorRoster(value: String) = value
        .split(Regex("\\s*(?:、|,|，|／|/)\\s*"))
        .flatMap { raw ->
            val role = when {
                Regex("[（(]伝[）)]").containsMatchIn(raw) -> "伝道師"
                Regex("[（(]宣[）)]").containsMatchIn(raw) -> "宣教師"
                Regex("[（(]担[）)]").containsMatchIn(raw) -> "担任牧師"
                Regex("[（(]応[）)]").containsMatchIn(raw) -> "協力牧師"
                else -> "牧師"
            }
            val name = raw.replace(Regex("[（(](?:議|指|応|伝|宣|担|着)[）)]"), "").trim()
            if (name in setOf("", "Ｘ", "X", "なし")) emptyList()
            else ChurchMinisterParser.fromRoleAndNames(role, name)
        }
}
