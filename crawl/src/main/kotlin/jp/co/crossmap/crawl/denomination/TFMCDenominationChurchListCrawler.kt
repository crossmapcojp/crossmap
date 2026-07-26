package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TFMCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TFMC"
    override val denominationName = "東京フリー・メソジスト教団"
    override val outputFileName = "tfmc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("h2.wp-block-heading[id]").mapNotNull { heading ->
            if (heading.id() !in churchSectionIds) return@mapNotNull null
            val section = mutableListOf<org.jsoup.nodes.Element>()
            var element = heading.nextElementSibling()
            while (element != null && !(element.tagName() == "h2" && element.hasClass("wp-block-heading"))) {
                section.add(element)
                element = element.nextElementSibling()
            }
            val table = section.asSequence().flatMap { it.select("table").asSequence() }.firstOrNull()
                ?: return@mapNotNull null
            val rows = table.select("tr")
            val values = rows.mapNotNull { row ->
                val label = row.selectFirst("th")?.text()?.trim().orEmpty()
                val value = row.selectFirst("td")?.text()?.trim().orEmpty()
                label.takeIf(String::isNotBlank)?.let { it to value }
            }.toMap()
            val name = heading.text().trim()
            val rawAddress = values["住所"].orEmpty().let { address ->
                if (name == "小金井教会" && address.startsWith("小金井市")) "東京都$address" else address
            }
            val address = DirectoryCrawlerSupport.normalizeAddress(rawAddress)
            val links = section.flatMap { it.select("a[href]") }
            val website = links.firstOrNull {
                it.text().contains("ホームページ") &&
                    DirectoryCrawlerSupport.socialProfiles(listOf(it)).isEmpty()
            }?.absUrl("href").orEmpty()
            val ministers = rows.flatMap { row ->
                val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
                val role = cells.firstOrNull()?.text()?.trim().orEmpty()
                if (!ministerRolePattern.containsMatchIn(role)) return@flatMap emptyList()
                val names = cells.getOrNull(1)?.html()
                    ?.replace(lineBreak, "、")
                    ?.let(Jsoup::parseBodyFragment)
                    ?.text()
                    ?.let(::separateAdjacentJapaneseNames)
                    .orEmpty()
                ChurchMinisterParser.fromRoleAndNames(role, names)
            }
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                websiteUrl = website,
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministers.distinctBy { it.roleId to it.name },
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private fun separateAdjacentJapaneseNames(value: String): String {
        if (nameSeparator.containsMatchIn(value)) return value
        val parts = value.trim().split(whitespace)
        return if (parts.size > 2 && parts.size % 2 == 0) {
            parts.chunked(2).joinToString("、") { it.joinToString(" ") }
        } else {
            value
        }
    }

    private companion object {
        val churchSectionIds = setOf(
            "koganei",
            "sakuragaoka",
            "akishima",
            "hachioujinakano",
            "oume",
            "hinonanpeidai",
            "moriya",
            "kawagoe",
            "minamioosawa",
            "mizuhodai",
        )
        val ministerRolePattern = Regex("""(?:牧師|伝道師|宣教師|教職|長老)""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val lineBreak = Regex("""(?i)<br\s*/?>""")
        val nameSeparator = Regex("""[、,，/／・\n]""")
        val whitespace = Regex("""[\s　]+""")
    }
}
