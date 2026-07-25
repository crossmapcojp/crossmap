package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup

data class CatholicJpDioceseRef(
    val slug: String,
    val name: String,
    val cbcjUrl: String,
    val officialWebsiteUrl: String = "",
)

object CatholicJpDioceseIndex {
    const val sourceUrl = "https://www.cbcj.catholic.jp/japan/diocese/"

    fun parseIndex(html: String): List<CatholicJpDioceseRef> = Jsoup.parse(html, sourceUrl)
        .select("a[href]")
        .mapNotNull { link ->
            val url = link.absUrl("href")
            val slug = runCatching { URI(url).path.orEmpty() }
                .getOrDefault("")
                .let(diocesePath::matchEntire)
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val name = link.text().replace(Regex("^[■●◆\\s]+|[（(].*$"), "").trim()
            if (!name.contains("教区")) null else CatholicJpDioceseRef(slug, name, url)
        }.distinctBy(CatholicJpDioceseRef::slug)

    fun resolveOfficialWebsite(diocese: CatholicJpDioceseRef, html: String): CatholicJpDioceseRef {
        val document = Jsoup.parse(html, diocese.cbcjUrl)
        val official = document.select("a[href]").firstNotNullOfOrNull { link ->
            val href = link.absUrl("href").trim().trimEnd('/')
            val label = link.text().trim().trimEnd('/')
            href.takeIf {
                it.startsWith("http") && label.startsWith("http") &&
                    runCatching { URI(it).host }.getOrNull() != "www.cbcj.catholic.jp"
            }
        }.orEmpty()
        require(official.isNotBlank()) { "CBCJ diocese page does not publish an official website: ${diocese.cbcjUrl}" }
        return diocese.copy(officialWebsiteUrl = official)
    }

    private val diocesePath = Regex("/japan/diocese/([^/]+)/")
}
