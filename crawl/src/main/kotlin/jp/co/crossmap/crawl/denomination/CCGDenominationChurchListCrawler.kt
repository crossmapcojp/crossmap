package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CCGDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CCG"
    override val denominationName = "カルバリーチャペルグループ"
    override val outputFileName = "ccg-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("tr")
        .mapNotNull { row ->
            val directCells = row.children().filter { it.tagName() == "td" }
            val nameCell = directCells.firstOrNull { cell ->
                cell.children().any { it.tagName() == "strong" }
            } ?: return@mapNotNull null
            val name = nameCell.selectFirst("strong")?.text()?.replace(Regex("""\s+"""), "")?.trim().orEmpty()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val detailText = directCells.dropWhile { it != nameCell }.drop(1).joinToString(" ") { it.text() }
            val cleanedAddress = DirectoryCrawlerSupport.addressFromText(detailText)
                .replace(trailingPersonPattern, "")
                .replace(unclosedPersonPattern, "")
                .replace(addressSchedulePattern, "")
                .trim()
            val address = cleanedAddress.takeIf(prefecturePattern::containsMatchIn)
                ?.let { if (it.startsWith("〒")) it else "〒$it" }
                .orEmpty()
            val links = row.select("a[href]")
            val sharedPhoneFax = telFaxPattern.find(detailText)?.groupValues?.get(1)?.trim().orEmpty()
            val ministers = (
                ChurchMinisterParser.parse(detailText) + personRolePattern.findAll(detailText).flatMap { match ->
                    val name = match.groupValues[1].trim()
                    if (name in rolePrefixes) emptySequence()
                    else ChurchMinisterParser.fromRoleAndNames(match.groupValues[2], name).asSequence()
                }
                ).distinctBy { it.roleId to it.name }
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(detailText).ifBlank { sharedPhoneFax },
                fax = DirectoryCrawlerSupport.faxFromText(detailText).ifBlank { sharedPhoneFax },
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.yamatocalvarychapel.com"),
                email = DirectoryCrawlerSupport.extractEmail(detailText, links.map { it.absUrl("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministers,
            )
        }

    private fun looksLikeChurchName(value: String): Boolean =
        !value.startsWith("支教会") && listOf("教会", "チャペル", "フェローシップ", "祈りの家").any(value::contains)

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val trailingPersonPattern = Regex("""\s*[（(][^）)]*(?:牧師|伝道師|宣教師|兄)[）)].*$""")
        val unclosedPersonPattern = Regex("""\s*[（(][^）)]*$""")
        val addressSchedulePattern = Regex("""\s*(?:[*＊]時間借り|時間[：:]|・事務所).*$""")
        val personRolePattern = Regex("""([\p{L}][\p{L} 　・･]{1,30}?)(主任牧師|牧師|伝道師|宣教師)""")
        val rolePrefixes = setOf("主任", "副", "担任", "協力")
        val telFaxPattern = Regex("""Tel/Fax:\s*([0-9０-９()（）+\-ー－‐/\s　]{8,})""", RegexOption.IGNORE_CASE)
    }
}
