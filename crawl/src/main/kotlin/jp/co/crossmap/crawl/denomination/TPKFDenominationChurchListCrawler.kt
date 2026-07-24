package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TPKFDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TPKF"
    override val denominationName = "単立ペンテコステ教会フェローシップ"
    override val sourceUrl = "https://tpkf.org/localch_group.html"
    override val outputFileName = "tpkf-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("table tr").mapNotNull { row ->
            val cells = row.select("th,td")
            if (cells.size < 3 || !cells[0].text().contains(Regex("教会|チャペル|センター"))) return@mapNotNull null
            val text = row.text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            if (address.isBlank()) return@mapNotNull null
            val name = cells[0].select("a,strong,b").firstOrNull { it.text().contains(Regex("教会|チャペル|センター")) }?.text()?.trim()
                ?: Regex("^(.+?(?:教会|チャペル|センター))").find(cells[0].text())?.groupValues?.get(1)
                ?: return@mapNotNull null
            val links = row.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = cells.getOrNull(3)?.text()?.trim().orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tpkf.org"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = links.firstOrNull { it.absUrl("href").contains("tpkf.org") }?.absUrl("href").orEmpty(),
                ministers = ChurchMinisterParser.parse(cells[0].text()),
            )
        }.distinctBy { it.name to it.address }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main,article,#contents") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tpkf.org").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links)).distinctBy { it.platform to it.url },
            ministers = ChurchMinisterParser.parse(text).ifEmpty { church.ministers },
        )
    }
}
