package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JFECDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JFEC"
    override val denominationName = "同盟福音基督教会"
    override val sourceUrl = sourceUrls.single()
    override val outputFileName = "jfec-churches.json"

    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select(".gallery").flatMap { gallery ->
            val jurisdiction = gallery.previousElementSiblings()
                .firstOrNull { it.tagName() in setOf("h2", "h3") }
                ?.text().orEmpty()
                .replace(Regex("\\s*の教会$"), "")
                .replace(" ", "")
            gallery.select(".gallery-item").mapNotNull { item ->
                val name = item.selectFirst(".gallery-caption")?.text()?.trim().orEmpty()
                val detail = item.selectFirst("a[href]")?.absUrl("href").orEmpty()
                    .replace("http://", "https://")
                if (name.isBlank() || detail.isBlank()) return@mapNotNull null
                OfficialDenominationChurch(
                    name = name,
                    jurisdiction = jurisdiction,
                    denominationChurchListDetailPage = detail,
                )
            }
        }.distinctBy { it.denominationChurchListDetailPage }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.selectFirst("main article,.entry-content,article") ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        val address = Regex("住所[：:]\\s*(.+?)(?=\\s*(?:ウェッブ|Web|お問い合わせ|電話|$))")
            .find(text)?.groupValues?.get(1)?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
        return church.copy(
            address = address.ifBlank { DirectoryCrawlerSupport.addressFromText(text) }.ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text.replace("お問い合わせ", "電話")).ifBlank { church.phone },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.doumeifukuin.net").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links))
                .distinctBy { it.platform to it.url },
            ministers = ChurchMinisterParser.parse(text).ifEmpty { church.ministers },
        )
    }
}
