package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TLEADenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TLEA"
    override val denominationName = "The Light of Eternal Agape"
    override val sourceUrl = "https://tlea.tokyoantioch.com/ourchurch/all-tlea-link/"
    override val outputFileName = "tlea-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val selectors = "tr, article, li, .wp-block-group, .elementor-widget-container"
        val contactRows = document.select(selectors)
            .filter { postal.containsMatchIn(it.text()) }
            .filter { element -> element.select(selectors).none { it !== element && postal.containsMatchIn(it.text()) } }
            .mapNotNull { block ->
                val text = block.text().trim()
                val address = DirectoryCrawlerSupport.addressFromText(text)
                val name = block.select("h1,h2,h3,h4,h5,h6,strong,b,a,th,td")
                    .map { it.ownText().ifBlank { it.text() }.trim() }
                    .firstOrNull { Regex("教会|チャーチ|チャペル").containsMatchIn(it) && !postal.containsMatchIn(it) && it.length <= 80 }
                    .orEmpty()
                if (name.isBlank() || address.isBlank()) return@mapNotNull null
                val links = block.select("a[href]")
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = DirectoryCrawlerSupport.phoneFromText(text),
                    fax = DirectoryCrawlerSupport.faxFromText(text),
                    websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tlea.tokyoantioch.com"),
                    email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                    ministers = ChurchMinisterParser.parse(text),
                )
            }
        val linkedChurches = document.select("tr td a[href]").mapNotNull { link ->
            val name = link.text().trim()
            if (!Regex("教会|チャーチ|チャペル").containsMatchIn(name)) return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.startsWith("http") }.orEmpty()
            OfficialDenominationChurch(name = name, websiteUrl = href)
        }
        return (contactRows + linkedChurches)
            .groupBy(OfficialDenominationChurch::name)
            .map { (_, rows) -> rows.maxByOrNull { it.address.length + it.ministers.size * 100 }!!
                .let { best -> best.copy(websiteUrl = rows.firstNotNullOfOrNull { it.websiteUrl.takeIf(String::isNotBlank) }.orEmpty()) }
            }
    }

    private val postal = Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}")
}
