package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class GECDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "GEC"
    override val denominationName = "福音伝道教団"
    override val sourceUrls = listOf(
        "https://fdk.fukuindendou.org/gunma/",
        "https://fdk.fukuindendou.org/saitama/",
        "https://fdk.fukuindendou.org/tochigi/",
        "https://fdk.fukuindendou.org/tokyo-kanagawa/",
    )
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "gec-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, url)
        return document.select("h2.wp-block-heading").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!name.contains(Regex("教会|チャーチ|福音館"))) return@mapNotNull null
            val section = mutableListOf<org.jsoup.nodes.Element>()
            var sibling = heading.nextElementSibling()
            while (sibling != null && sibling.tagName() != "h2") {
                section.add(sibling)
                sibling = sibling.nextElementSibling()
            }
            val info = section.firstNotNullOfOrNull { element ->
                element.takeIf { it.`is`("p.is-style-emboss_box") }
                    ?: element.selectFirst("p.is-style-emboss_box")
            } ?: return@mapNotNull null
            val text = info.text()
            val rawAddress = Regex("住所\\s*[：:]\\s*(.+?)(?=\\s*電話|\\s*メール|\\s*牧師|$)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (rawAddress.isBlank()) return@mapNotNull null
            val links = section.flatMap { it.select("a[href]") }
            val ministerElement = section.asSequence().flatMap { it.select("p").asSequence() }
                .firstOrNull { it.text().contains(Regex("牧師\\s*[：:]")) }
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.normalizeAddress(rawAddress),
                phone = info.selectFirst("a[href^=tel:]")?.attr("href")?.substringAfter(':').orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "fdk.fukuindendou.org"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministerElement?.let(::parseMinisters).orEmpty(),
            )
        }.distinctBy { it.name to it.address }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        return church
    }

    private fun parseMinisters(element: org.jsoup.nodes.Element): List<jp.co.crossmap.ChurchMinister> {
        val text = element.wholeText()
        val label = Regex("牧師\\s*[：:]").find(text) ?: return emptyList()
        return text.substring(label.range.last + 1)
            .split(Regex("(?:\\r?\\n)+|[　\\u2003]{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
            .distinctBy { it.roleId to it.name }
    }
}
