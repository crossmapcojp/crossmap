package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JLBCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JLBC"
    override val denominationName = "日本ルーテル同胞教団"
    override val outputFileName = "jlbc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var prefecture = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("h2.wp-block-heading, h3.wp-block-heading").forEach { heading ->
            if (heading.tagName() == "h2") {
                prefecture = heading.text().trim()
                return@forEach
            }
            val name = heading.text().trim()
            if (name.isBlank() || !looksLikeChurchName(name)) return@forEach
            val sectionHtml = heading.nextElementSiblings().takeWhile { it.tagName() !in setOf("h2", "h3") }
                .joinToString("\n") { it.outerHtml() }
            val section = Jsoup.parseBodyFragment(sectionHtml, sourceUrl)
            val values = section.select("table").firstOrNull { it.text().contains("住所") }
                ?.select("tr")?.mapNotNull { row ->
                    val cells = row.select("th, td")
                    if (cells.size < 2) null else cells[0].text().trim() to cells[1]
                }?.toMap().orEmpty()
            if (values.isEmpty()) return@forEach
            val links = section.select("a[href]")
            churches += OfficialDenominationChurch(
                name = name,
                address = values["住所"]?.text()?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty(),
                jurisdiction = prefecture,
                phone = values.entries.firstOrNull { it.key.replace("/", "").contains("Tel") }?.value?.text()?.trim().orEmpty(),
                websiteUrl = values["HP"]?.selectFirst("a[href]")?.absUrl("href")
                    .orEmpty().ifBlank { DirectoryCrawlerSupport.externalWebsite(links, "clbj.org") },
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = values["牧師"]?.text()?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }.orEmpty(),
            )
        }
        return churches
    }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャペル", "希望の家").any(name::contains)
}
