package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JCOBJpDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCOB_JP"
    override val denominationName = "Jesus Christ Our Banner Japan"
    override val outputFileName = "jcob-jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("p")
        .mapNotNull { paragraph ->
            val mapLink = paragraph.select("a[href*='google.com/maps/']").firstOrNull() ?: return@mapNotNull null
            val rawName = paragraph.text().substringBefore('|').trim()
            if (!churchName.matches(rawName) || rawName in excludedLabels || "/maps/search/jcob/" in mapLink.attr("href")) {
                return@mapNotNull null
            }
            val text = paragraph.text().trim()
            val links = paragraph.select("a[href]")
            val address = mapLink.text().trim()
            OfficialDenominationChurch(
                name = "JCOB ${rawName.toDisplayName()}",
                address = address,
                jurisdiction = jurisdiction(address),
                phone = churchPhone.find(text)?.groupValues?.get(1).orEmpty().normalizePhone(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(
                    links.filterNot { link -> "google.com/maps" in link.absUrl("href") },
                    "jcobjapan.com",
                ),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.absUrl("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = minister.findAll(text).flatMap { match ->
                    ChurchMinisterParser.fromRoleAndNames(
                        roleText = when (match.groupValues[1].lowercase()) {
                            "e." -> "伝道師"
                            else -> "牧師"
                        },
                        namesText = match.groupValues[2].trim(),
                    )
                }.toList(),
            )
        }
        .distinctBy { it.name to it.address }

    private fun String.toDisplayName(): String = lowercase().split(Regex("""\s+"""))
        .joinToString(" ") { word ->
            word.split('-').joinToString("-") { part -> part.replaceFirstChar(Char::titlecase) }
        }

    private fun String.normalizePhone(): String = replace(Regex("""[ ()]+"""), "-").trim('-')

    private fun jurisdiction(address: String): String = when {
        address.contains("Tokyo", ignoreCase = true) -> "東京都"
        address.contains("Saitama", ignoreCase = true) -> "埼玉県"
        address.contains("Chiba", ignoreCase = true) -> "千葉県"
        address.contains("Niigata", ignoreCase = true) -> "新潟県"
        address.contains("Fukui", ignoreCase = true) -> "福井県"
        address.contains("Shizuoka", ignoreCase = true) || address.contains("Shizouka", ignoreCase = true) -> "静岡県"
        address.contains("Aichi", ignoreCase = true) -> "愛知県"
        address.contains("Gunma", ignoreCase = true) -> "群馬県"
        else -> ""
    }

    private companion object {
        val churchName = Regex("""[A-Z][A-Z -]+""")
        val excludedLabels = setOf("CHURCHES", "TOKYO-SAITAMA-CHIBA", "NIIGATA-FUKUI", "SHIZUOKA-AICHI", "PIONEERING")
        val churchPhone = Regex("""Phone:\s*([0-9 ()-]{10,})""", RegexOption.IGNORE_CASE)
        val minister = Regex("""\b(Ptr\.|Pastor|Pstr\.|E\.)\s*([^:|]+):\s*[0-9 ()-]{10,}""", RegexOption.IGNORE_CASE)
    }
}
