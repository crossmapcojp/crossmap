package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

/** Official Chinese-church directory published by JCCC. */
class JCCCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCCC"
    override val denominationName = "華人教会"
    override val outputFileName = "jccc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("table tr")
        .mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
            if (cells.size < 4 || !numberPattern.matches(cells[0].text().trim())) return@mapNotNull null
            val name = cells[1].text().replace(Regex("""\s+"""), " ").trim()
            val address = cells[2].text().replace(Regex("""\s+"""), " ").trim()
            val contact = cells.drop(3).joinToString(" ") { it.text() }.trim()
            if (name.isBlank() || address.isBlank()) return@mapNotNull null
            val links = row.select("a[href]")
            val externalWebsite = DirectoryCrawlerSupport.externalWebsite(links, "tokyo-jcc.com")
            val internalDetailPage = links.asSequence()
                .map { it.absUrl("href") }
                .firstOrNull { it.startsWith(sourceOrigin) && it != sourceUrl }
                .orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(contact).ifBlank {
                    phonePattern.find(contact)?.value.orEmpty()
                },
                fax = DirectoryCrawlerSupport.faxFromText(contact),
                websiteUrl = externalWebsite,
                email = DirectoryCrawlerSupport.extractEmail(contact, links.map { it.absUrl("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = internalDetailPage,
                ministers = parseChineseMinisters(contact),
                localizedNames = officialChineseName(name),
            )
        }

    private fun parseChineseMinisters(value: String) = chineseMinisterPattern.findAll(value)
        .flatMap { match ->
            val role = when (match.groupValues[2]) {
                "牧师", "牧師" -> "牧師"
                else -> "伝道師"
            }
            ChurchMinisterParser.fromRoleAndNames(role, match.groupValues[1]).asSequence()
        }
        .distinctBy { it.roleId to it.name }
        .toList()

    private fun officialChineseName(value: String): List<LocalizedName> = when {
        traditionalCharacters.any(value::contains) -> listOf(LocalizedName("zh-Hant", value))
        simplifiedCharacters.any(value::contains) -> listOf(LocalizedName("zh-Hans", value))
        else -> emptyList()
    }

    private val sourceOrigin = URI(sourceUrl).let { "${it.scheme}://${it.authority}/" }

    private companion object {
        val numberPattern = Regex("""\d+""")
        val phonePattern = Regex("""(?:0\d{1,4}-\d{1,4}-\d{3,4}|0\d{9,10})""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県|沖縄県""")
        val chineseMinisterPattern = Regex("""([\p{L}·・]{2,30})\s*(牧师|牧師|传道|傳道)""")
        val traditionalCharacters = setOf('會', '靈', '禱', '國', '華', '傳', '師', '繩')
        val simplifiedCharacters = setOf('会', '灵', '祷', '国', '华', '传', '师', '绳')
    }
}
