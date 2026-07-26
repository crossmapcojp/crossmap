package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class IECDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "IEC"
    override val denominationName = "インドネシア福音教会"
    override val outputFileName = "iec-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("div[data-testid=richTextElement]:has(h6.font_6)")
        .mapNotNull { section ->
            val name = section.selectFirst("h6.font_6")?.text()?.replace(zeroWidth, "")?.trim().orEmpty()
            if (!looksLikeRegionalChurch(name)) return@mapNotNull null
            val text = section.text().replace(zeroWidth, " ").replace(whitespace, " ").trim()
            val address = addressPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val ministryText = text.substringAfter("Hamba Tuhan:", "")
            val ministers = ministerPattern.findAll(ministryText).flatMap { match ->
                val role = if (match.groupValues[1].startsWith("Ev")) "伝道師" else "牧師"
                val names = match.groupValues[2]
                    .replace(credentialsPattern, "")
                    .replace(Regex("""^Dr\.\s*"""), "")
                    .trim()
                ChurchMinisterParser.fromRoleAndNames(role, names).asSequence()
            }.distinctBy { it.roleId to it.name }.toList()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = jurisdictionByRegion.entries.firstOrNull { (region) ->
                    address.contains(region, ignoreCase = true) || name.contains(region, ignoreCase = true)
                }?.value.orEmpty(),
                websiteUrl = websitePattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
                email = emailPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
                ministers = ministers,
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private fun looksLikeRegionalChurch(value: String): Boolean =
        value.startsWith("GIII ") || value.startsWith("POS PI ")

    private companion object {
        val zeroWidth = Regex("""[\u200B-\u200D\uFEFF]""")
        val whitespace = Regex("""\s+""")
        val addressPattern = Regex("""Alamat Gereja:\s*(.+?)(?=\s+Hamba Tuhan:)""", RegexOption.IGNORE_CASE)
        val ministerPattern = Regex(
            """(Pdt\.|Pdm\.|Ev\.)\s+(.+?)(?=\s+Email:|\s+(?:Pdt\.|Pdm\.|Ev\.)|\s+Website:|$)""",
            RegexOption.IGNORE_CASE,
        )
        val credentialsPattern = Regex(""",?\s*(?:S\.Th\.|M\.Th\.)""", RegexOption.IGNORE_CASE)
        val emailPattern = Regex("""Email:\s*([A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,})""", RegexOption.IGNORE_CASE)
        val websitePattern = Regex("""Website:\s*(https?://\S+)""", RegexOption.IGNORE_CASE)
        val jurisdictionByRegion = linkedMapOf(
            "Tokyo" to "東京都",
            "Ibaraki" to "茨城県",
            "Gunma" to "群馬県",
            "Aichi" to "愛知県",
            "Osaka" to "大阪府",
            "Ōsaka" to "大阪府",
            "Mie" to "三重県",
            "Fukuoka" to "福岡県",
            "Kanagawa" to "神奈川県",
        )
    }
}
