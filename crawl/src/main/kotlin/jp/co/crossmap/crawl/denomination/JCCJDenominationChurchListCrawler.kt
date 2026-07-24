package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JCCJDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JCCJ"
    override val denominationName = "日本イエス・キリスト教団"
    override val sourceUrl = "https://jccj.info/localchurch.php"
    override val outputFileName = "jccj-churches.json"
    override val sourceUrls = listOf(
        "https://jccj.info/localchurch_tyokkatu.php",
        "https://jccj.info/localchurch_touhoku.php",
        "https://jccj.info/localchurch_kanto.php",
        "https://jccj.info/localchurch_shinetu.php",
        "https://jccj.info/localchurch_kyoto.php",
        "https://jccj.info/localchurch_osaka.php",
        "https://jccj.info/localchurch_hyogo.php",
        "https://jccj.info/localchurch_tyugoku.php",
        "https://jccj.info/localchurch_shikoku.php",
        "https://jccj.info/localchurch_kyusyu.php",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val tables = document.select("table.table_localchurch, table").filter { table ->
            Regex("教会|伝道所").containsMatchIn(table.text()) && Regex("〒?[0-9０-９]{3}[-ー－]?[0-9０-９]{4}").containsMatchIn(table.text())
        }
        return tables.flatMap(::parseTable).distinctBy { it.name to it.address }
    }

    private fun parseTable(table: Element): List<OfficialDenominationChurch> {
        val headingName = table.selectFirst("h5")?.text()?.trim().orEmpty()
        if (table.hasClass("table_localchurch") && headingName.isNotBlank()) {
            val name = headingName
            val items = table.select("li")
            val postalIndex = items.indexOfFirst { it.ownText().trim().startsWith("〒") }
            val address = if (postalIndex >= 0) {
                val postal = items[postalIndex].ownText().trim()
                val street = items.getOrNull(postalIndex + 1)?.ownText()?.trim().orEmpty()
                DirectoryCrawlerSupport.normalizeAddress("$postal $street")
            } else ""
            val ministerItem = items.firstOrNull { item ->
                Regex("^(?:主任牧師|主管牧師|副牧師|牧師|伝道師|宣教師)(?:\\s|　)")
                    .containsMatchIn(item.text().trim())
            }
            val ministerText = ministerItem?.html()
                ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "、")
                ?.let { Jsoup.parseBodyFragment(it).text() }
                ?.replace('　', ' ')
                ?.replace('\u00a0', ' ')
                ?.trim()
                .orEmpty()
                .ifBlank {
                    Regex("(?:^|\\s)(?:主任牧師|主管牧師|副牧師|牧師|伝道師|宣教師)\\s+.+?(?=\\s+定期集会|$)")
                        .find(table.text().replace('　', ' ').replace('\u00a0', ' '))?.value?.trim().orEmpty()
                }
            val role = aliases.firstOrNull { ministerText.startsWith(it) }.orEmpty()
            val website = table.select("a[href]").map { it.absUrl("href") }.firstOrNull { url ->
                url.startsWith("http") && runCatching { URI(url).host != "jccj.info" }.getOrDefault(false)
            }.orEmpty()
            return listOf(
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = items.firstOrNull { it.ownText().trim().startsWith("TEL") }?.ownText()?.removePrefix("TEL")?.trim().orEmpty(),
                    fax = items.firstOrNull { it.ownText().trim().startsWith("FAX") }?.ownText()?.removePrefix("FAX")?.trim().orEmpty(),
                    websiteUrl = website,
                    ministers = ChurchMinisterParser.fromRoleAndNames(role, ministerText.removePrefix(role).trim()),
                ),
            )
        }
        val labelled = table.select("tr").associate { row ->
            val cells = row.select("th, td")
            cells.firstOrNull()?.text()?.replace(Regex("[：:\\s　]+"), "").orEmpty() to cells.getOrNull(1)
        }
        val name = labelled.entries.firstOrNull { (label) -> label in setOf("教会名", "名称") }
            ?.value?.text()?.trim().orEmpty()
        if (name.isNotBlank()) {
            val text = table.text()
            val address = labelled.entries.firstOrNull { (label) -> "住所" in label }?.value?.text()?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
            val pastorRows = labelled.filterKeys { Regex("牧師|伝道師|宣教師|教職|教師").containsMatchIn(it) }
            return listOf(
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = labelled.entries.firstOrNull { (label) -> label == "電話" || label == "TEL" }?.value?.text()?.trim().orEmpty(),
                    fax = labelled.entries.firstOrNull { (label) -> label == "FAX" }?.value?.text()?.trim().orEmpty(),
                    websiteUrl = table.selectFirst("a[href]")?.absUrl("href")?.takeUnless { it.contains("jccj.info/") }.orEmpty(),
                    denominationChurchListDetailPage = table.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.contains("jccj.info/") }.orEmpty(),
                    ministers = pastorRows.flatMap { (role, value) -> ChurchMinisterParser.fromRoleAndNames(role, value?.text().orEmpty()) }
                        .ifEmpty { ChurchMinisterParser.parse(text) },
                ),
            )
        }
        return table.select("tr").mapNotNull { row ->
            val text = row.text().trim()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            if (address.isBlank()) return@mapNotNull null
            val name = row.select("strong,b,a,th,td")
                .map { it.ownText().ifBlank { it.text() }.trim() }
                .firstOrNull {
                    Regex("教会|伝道所").containsMatchIn(it) &&
                        !Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}").containsMatchIn(it) &&
                        it.length <= 80
                }
                .orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val links = row.select("a[href]")
            val internalDetail = links.firstOrNull { link ->
                runCatching { URI(link.absUrl("href")).host == "jccj.info" }.getOrDefault(false)
            }?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "jccj.info"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = internalDetail,
                ministers = ChurchMinisterParser.parse(text),
            )
        }
    }

    private val aliases = listOf("主任牧師", "主管牧師", "副牧師", "牧師", "伝道師", "宣教師")
}
