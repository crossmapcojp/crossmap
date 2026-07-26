package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class OCCJDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "OCCJ"
    override val denominationName = "日本華僑基督教団"
    override val outputFileName = "occj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val names = document.select("p.font_5").map { cleanName(it.text()) }
            .filter(churchNamePattern::containsMatchIn)
        val detailUrls = document.select("a.wixui-button[href]").map { it.absUrl("href") }
            .filter { it.startsWith("https://www.occj.net/") }
        return names.zip(detailUrls).map { (name, detailUrl) ->
            OfficialDenominationChurch(
                name = name,
                websiteUrl = detailUrl,
                denominationChurchListDetailPage = detailUrl,
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val localContact = document.select("[data-testid=richTextElement]").asSequence()
            .map { it.text().replace(invisibleCharacters, "").trim() }
            .firstOrNull { "〒" in it && "日本華僑基督教團 監督" !in it }
            .orEmpty()
        val address = localAddressPattern.find(localContact)?.value.orEmpty()
            .let(DirectoryCrawlerSupport::normalizeAddress)
            .replace(" 大阪市", " 大阪府大阪市")
            .replace(" 福島市", " 福島県福島市")
            .replace(" 名古屋市", " 愛知県名古屋市")
            .replace("熊本県阿蘇市西原村", "熊本県阿蘇郡西原村")
            .replace("竂禮拜堂", "寮礼拝堂")
        val links = document.select("a[href]")
        val ministers = ministerPattern.findAll(localContact).flatMap { match ->
            val role = if ("牧師" in match.groupValues[2]) "牧師" else "伝道師"
            ChurchMinisterParser.fromRoleAndNames(role, match.groupValues[1].trim()).asSequence()
        }.toList()
        return church.copy(
            address = address,
            jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            phone = DirectoryCrawlerSupport.phoneFromText(localContact),
            email = DirectoryCrawlerSupport.extractEmail(localContact, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ministers.distinctBy { it.roleId to it.name },
        )
    }

    private fun cleanName(value: String): String = value
        .replace(invisibleCharacters, "")
        .replace(Regex("""^宗教法人[・\s]*"""), "")
        .replace('廣', '広')
        .replace('德', '徳')
        .replace('國', '国')
        .replace('靜', '静')
        .trim()

    private companion object {
        val churchNamePattern = Regex("""基督教生命堂$""")
        val localAddressPattern = Regex(
            """〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}\s*.+?(?=\s*(?:電話|電子郵件|[A-Z0-9._%+\-]+@|$))""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val ministerPattern = Regex(
            """([一-龯ァ-ヶ・\s]{1,20}?)\s*(牧師|實習傳道|実習伝道|傳道|伝道)(?=\s|[0-9]|$)""",
        )
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val invisibleCharacters = Regex("""[\u200B-\u200D\u2060\uFEFF]""")
    }
}
