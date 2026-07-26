package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JCUCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCUC"
    override val denominationName = "日本キリスト合同教会"
    override val outputFileName = "jcuc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("article h3").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!name.endsWith("教会")) return@mapNotNull null
            val section = mutableListOf<org.jsoup.nodes.Element>()
            var element = heading.nextElementSibling()
            while (element != null && element.tagName() != "h3") {
                section.add(element)
                element = element.nextElementSibling()
            }
            val text = section.joinToString(" ") { it.text() }
            val links = section.flatMap { it.select("a[href]") }
            val address = DirectoryCrawlerSupport.addressFromText(text)
                .replace("千葉県椅隅郡", "千葉県夷隅郡")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                websiteUrl = links.firstOrNull()?.absUrl("href").orEmpty(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}
