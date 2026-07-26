package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class MennoniteBrethrenCouncilJpDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "MENNONITE_BRETHREN_COUNCIL_JP"
    override val denominationName = "日本メノナイト・キリスト教会協議会"
    override val outputFileName = "mennonite-brethren-council-jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("article table tr").mapNotNull { row ->
            val cells = row.select("> th, > td")
            if (cells.size < 4) return@mapNotNull null
            val name = cells[0].text().trim()
            if (!name.endsWith("教会")) return@mapNotNull null
            val address = cells[1].text().trim().let { raw ->
                DirectoryCrawlerSupport.normalizeAddress(
                    if (raw.startsWith("北海道")) raw else "北海道$raw",
                )
            }
            val contact = cells[2].text()
            val links = row.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = "北海道",
                phone = phonePattern.find(contact)?.value.orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.mennonite.jp"),
                email = DirectoryCrawlerSupport.extractEmail(contact, links.map { it.absUrl("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.fromRoleAndNames("教職", cells[3].text()),
            )
        }

    private companion object {
        val phonePattern = Regex("""[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}""")
    }
}
