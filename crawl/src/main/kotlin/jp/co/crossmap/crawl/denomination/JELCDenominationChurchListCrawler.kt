package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JELCDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JELC"
    override val denominationName = "日本福音ルーテル教会"
    override val sourceUrl = "https://jelc.or.jp/all_churchs/"
    override val outputFileName = "jelc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val selectors = "tr, article, li, .church, .church_box, .wp-block-group"
        return document.select(selectors)
            .filter { postal.containsMatchIn(it.text()) }
            .filter { element -> element.select(selectors).none { it !== element && postal.containsMatchIn(it.text()) } }
            .mapNotNull { block ->
                val text = block.text().trim()
                val address = DirectoryCrawlerSupport.addressFromText(text)
                val name = block.select("h1,h2,h3,h4,h5,h6,strong,b,a,th,td")
                    .map { it.ownText().ifBlank { it.text() }.trim() }
                    .firstOrNull { churchName.containsMatchIn(it) && !postal.containsMatchIn(it) && it.length <= 80 }
                    .orEmpty()
                if (name.isBlank() || address.isBlank()) return@mapNotNull null
                val links = block.select("a[href]")
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = DirectoryCrawlerSupport.phoneFromText(text),
                    fax = DirectoryCrawlerSupport.faxFromText(text),
                    websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "jelc.or.jp"),
                    email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                    denominationChurchListDetailPage = links.firstOrNull { it.absUrl("href").contains("jelc.or.jp/") }
                        ?.absUrl("href").orEmpty(),
                    ministers = ChurchMinisterParser.parse(text),
                )
            }
            .distinctBy { it.name to it.address }
    }

    private companion object {
        val postal = Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}")
        val churchName = Regex("教会|伝道所|チャペル")
    }
}
