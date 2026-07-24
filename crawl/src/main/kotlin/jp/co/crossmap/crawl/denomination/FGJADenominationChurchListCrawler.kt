package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class FGJADenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "FGJA"
    override val denominationName = "日本フルゴスペル教団"
    override val sourceUrl = "https://www.fgja.jp/sanctuary-church.html"
    override val outputFileName = "fgja-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("table tr").mapNotNull { row ->
            val cells = row.select("th,td")
            if (cells.size < 3) return@mapNotNull null
            val name = cells[1].text().trim()
            if (!name.contains("教会") || excluded.any(name::contains)) return@mapNotNull null
            val detail = row.selectFirst("a[href]")?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                jurisdiction = cells[0].text().trim(),
                denominationChurchListDetailPage = detail,
                ministers = ChurchMinisterParser.parse(cells[2].text()),
            )
        }.distinctBy { it.name }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,article,.entry-content,.modal-body") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.fgja.jp").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links)).distinctBy { it.platform to it.url },
            ministers = ChurchMinisterParser.parse(text).ifEmpty { church.ministers },
        )
    }

    private val excluded = setOf("ウラジオストック", "ハバロフスク", "サハリン", "パルチザンスク")
}
