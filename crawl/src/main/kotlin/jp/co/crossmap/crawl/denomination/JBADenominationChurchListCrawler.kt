package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JBADenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JBA"
    override val denominationName = "日本バプテスト連合"
    override val outputFileName = "jba-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val lines = document.select("main p, #comp-k1hgky6m1 p")
            .distinct()
            .map { Line(it.text().replace(Regex("""\s+"""), " ").trim(), it.select("a[href]")) }
        val churchIndexes = lines.indices.filter { index -> churchName(lines[index].text).isNotBlank() }
        return churchIndexes.mapNotNull { index ->
            val end = churchIndexes.firstOrNull { it > index } ?: lines.size
            val section = lines.subList(index, end)
            val text = section.joinToString(" ") { it.text }
            val name = churchName(section.first().text)
            if (name.isBlank() || name.startsWith("セブ") || philippinesPattern.containsMatchIn(text)) {
                return@mapNotNull null
            }
            val links = section.flatMap(Line::links)
            val addressMatch = addressPattern.find(text)
            val address = addressMatch?.groupValues?.get(1)
                ?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
            val ministryText = text.substring(0, addressMatch?.range?.first ?: text.length)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value?.trim().orEmpty(),
                phone = phonePattern.find(text)?.value.orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.jbaptist.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(ministryText),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private fun churchName(value: String): String {
        val normalized = value.replace("​", "").trim()
        if (normalized.startsWith("詳細")) return ""
        return churchLinePattern.matchEntire(normalized)?.groupValues?.get(1).orEmpty()
    }

    private data class Line(val text: String, val links: List<Element>)

    private companion object {
        val churchLinePattern = Regex(
            """([^\s]+(?:バプテスト教会|バプテストチャーチ))(?:\s+(?:巡回宣教師|兼任牧師|宣教師|牧師|説教師)[：:].*)?""",
        )
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|.{2,3}県""")
        val phonePattern = Regex("""[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}""")
        val addressPattern = Regex(
            """((?:〒\s*[0-9０-９]{3}[-－ー‐][0-9０-９]{4}\s*)?(?:北海道|東京都|京都府|大阪府|.{2,3}県)\s*.+?)(?=\s*[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}(?:\s|$)|$)""",
        )
        val philippinesPattern = Regex("""Philippines|フィリピン""", RegexOption.IGNORE_CASE)
    }
}
