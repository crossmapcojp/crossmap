package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JapanFreeEvangelicalChurchDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JAPAN_FREE_EVANGELICAL_CHURCH"
    override val denominationName = "日本自由福音教団"
    override val outputFileName = "japan-free-evangelical-church-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("h4.wp-block-heading").mapNotNull { heading ->
            val name = heading.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val section = mutableListOf<org.jsoup.nodes.Element>()
            var element = heading.nextElementSibling()
            while (element != null && element.tagName() != "h4" && element.tagName() != "h2") {
                section.add(element)
                element = element.nextElementSibling()
            }
            val text = section.joinToString(" ") { it.text() }
            val links = section.flatMap { it.select("a[href]") }
            val address = DirectoryCrawlerSupport.addressFromText(text)
            val phone = DirectoryCrawlerSupport.phoneFromText(text)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "njfk-jp.com"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }.filter { it.name.endsWith("教会") || it.name.endsWith("チャペル") }
            .distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}