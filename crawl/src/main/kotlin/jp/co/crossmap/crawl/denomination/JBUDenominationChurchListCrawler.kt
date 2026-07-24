package jp.co.crossmap.crawl.denomination

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import jp.co.crossmap.SocialProfile
import jp.co.crossmap.crawl.SocialUrlNormalizer
import org.jsoup.Jsoup

class JBUDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JBU"
    override val denominationName = "日本バプテスト同盟"
    override val sourceUrl = "http://www.jbu.or.jp/chs/"
    override val outputFileName = "jbu-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("a[href*=churchdital.php]")
            .groupBy { it.absUrl("href") }
            .mapNotNull { (detailPage, links) ->
                if (detailPage.isBlank()) return@mapNotNull null
                val name = links.map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString("")
                if (name.isBlank()) null else OfficialDenominationChurch(
                    name = name,
                    denominationChurchListDetailPage = detailPage,
                )
            }
            .distinctBy { it.denominationChurchListDetailPage }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val rows = document.select("table tr")
        val addressParts = mutableListOf<String>()
        var readingAddress = false
        rows.forEach { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 2) return@forEach
            val label = cells[0].text().replace(Regex("[\\s　]+"), "")
            when {
                label == "所在地" -> {
                    readingAddress = true
                    addressParts += cells[1].text()
                }
                readingAddress && label.isBlank() -> addressParts += cells[1].text()
                readingAddress -> readingAddress = false
            }
        }
        val text = document.text().replace(Regex("牧[\\s　]+師"), "牧師")
        val websiteLinks = rows.firstOrNull { row ->
            row.children().firstOrNull()?.text()?.replace(Regex("[\\s　]+"), "") == "Webサイト"
        }?.select("a[href]").orEmpty()
        val externalUrl = websiteLinks.firstOrNull()?.let { link ->
            link.text().trim().takeIf { it.startsWith("http") }
                ?: URLDecoder.decode(link.attr("href"), StandardCharsets.UTF_8)
                    .substringAfter("&url=", "")
                    .takeIf { it.startsWith("http") }
        }.orEmpty()
        val platform = SocialUrlNormalizer.platform(externalUrl)
        val socialProfiles = if (platform == null) emptyList() else listOf(
            SocialProfile(platform, SocialUrlNormalizer.canonical(externalUrl, platform), SocialUrlNormalizer.handle(externalUrl)),
        )
        val labeledMinisters = rows.flatMap { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 2) return@flatMap emptyList()
            val role = cells[0].text().replace(Regex("[\\s　]+"), "")
            if (!role.contains(Regex("牧師|副牧師|伝道師|宣教師|協力牧師"))) emptyList()
            else parseMinisterNames(role, cells[1].text())
        }
        return church.copy(
            name = document.title().trim().ifBlank { church.name },
            address = DirectoryCrawlerSupport.normalizeAddress(addressParts.joinToString(" "))
                .let { if (it.matches(Regex("^\\d{3}-\\d{4}.*"))) "〒$it" else it }
                .replace(Regex("(?<=[丁目南北東西])\\s+(?=[０-９])"), ""),
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = externalUrl.takeIf { platform == null }.orEmpty(),
            email = DirectoryCrawlerSupport.extractEmail(text, document.select("a[href]").map { it.attr("href") }),
            socialProfiles = socialProfiles,
            ministers = labeledMinisters.ifEmpty { ChurchMinisterParser.parse(text) }
                .distinctBy { it.roleId to it.name },
        )
    }

    private fun parseMinisterNames(defaultRole: String, value: String) = value
        .replace(Regex("\\s+[・･]\\s+([（(](?:伝|協|宣)[）)])\\s*$"), " $1")
        .split(Regex("\\s+[・･]\\s+"))
        .flatMap { rawName ->
            val annotation = Regex("^[（(](伝|協|宣)[）)]|[（(](伝|協|宣)[）)]$").find(rawName)
            val abbreviation = annotation?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            val role = when (abbreviation) {
                "伝" -> "伝道師"
                "協" -> "協力牧師"
                "宣" -> "宣教師"
                else -> defaultRole
            }
            val name = rawName
                .replace(Regex("^[（(](?:伝|協|宣)[）)]\\s*|\\s*[（(](?:伝|協|宣)[）)]$"), "")
                .trim()
            ChurchMinisterParser.fromRoleAndNames(role, name)
        }
}
