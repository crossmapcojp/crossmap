package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JECUDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JECU"
    override val denominationName = "日本福音教会連合"
    override val outputFileName = "jecu-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        var jurisdiction = ""
        return document.select("tr").mapNotNull { row ->
            val cells = row.select("> td")
            val rowText = row.text().trim()
            if (cells.size >= 2 && rowText.contains("地区協議会") && cells.map(Element::text).distinct().size == 1) {
                jurisdiction = cells[0].text().trim()
                return@mapNotNull null
            }
            val name = cells.getOrNull(1)?.text()?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            if (!looksLikeChurchName(name)) return@mapNotNull null

            val rows = listOf(row) + row.nextElementSiblings().take(2).filter { it.tagName() == "tr" }
            val text = rows.joinToString(" ") { it.text() }
            val addressText = rows.drop(1).joinToString(" ") { it.select("> td").getOrNull(1)?.text().orEmpty() }
            val links = rows.flatMap { it.select("a[href]") }
            val people = cells.getOrNull(2)?.text().orEmpty()
                .replace(Regex("""[・･]\s*[^・･]+$"""), "")
                .trim()
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.addressFromText(addressText),
                jurisdiction = jurisdiction,
                phone = phonePattern.find(text)?.value.orEmpty().replaceFirst(Regex("^O"), "0"),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "church.ne.jp")
                    .ifBlank { links.firstOrNull { it.absUrl("href").startsWith("http") }?.absUrl("href").orEmpty() },
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.absUrl("href") }),
                ministers = people.takeIf(String::isNotBlank)
                    ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                    .orEmpty(),
            )
        }
    }

    private fun looksLikeChurchName(value: String): Boolean =
        value.contains("教会") && !value.contains("教会へのリンク")

    private companion object {
        val phonePattern = Regex("(?<![0-9])O?[0-9]{2,5}-[0-9]{1,4}-[0-9]{3,4}(?![0-9])")
    }
}
