package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicSapporoDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "sapporo"
    override val jurisdictionNames = setOf("札幌地区", "苫小牧地区", "函館地区", "旭川地区", "北見地区", "釧路地区")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val jurisdiction = when (url.substringAfter("www.csd.or.jp/").substringBefore('/')) {
            "sapporo" -> "札幌地区"
            "tomakomai" -> "苫小牧地区"
            "hakodate" -> "函館地区"
            "asahikawa" -> "旭川地区"
            "kitami" -> "北見地区"
            "kushiro" -> "釧路地区"
            else -> "札幌教区"
        }
        val document = Jsoup.parse(html, url)
        return document.select("h4[id^=ttl-]").mapNotNull { heading ->
            val section = heading.parents().firstOrNull { it.hasClass("row") }?.nextElementSibling()
                ?: return@mapNotNull null
            val table = section.selectFirst("table") ?: return@mapNotNull null
            val values = table.select("tr").mapNotNull { row ->
                val label = row.selectFirst("th")?.text()?.replace(Regex("\\s+"), "")?.trim().orEmpty()
                val value = row.selectFirst("td")
                label.takeIf(String::isNotBlank)?.let { it to value }
            }.toMap()
            val name = values["小教区"]?.text()?.trim().orEmpty()
            if (name.isBlank() || !name.contains("教会")) return@mapNotNull null
            val links = table.select("a[href]")
            val phoneAndFax = values["TEL・FAX"]?.text().orEmpty().ifBlank { values["電話・FAX"]?.text().orEmpty() }
            val numbers = phonePattern.findAll(phoneAndFax).map { it.value }.toList()
            val ministerCell = values["主任司祭"] ?: values["担当司祭"] ?: values["司祭"]
            OfficialDenominationChurch(
                name = name,
                address = values["住所"]?.text()?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty(),
                jurisdiction = jurisdiction,
                phone = values["TEL"]?.text()?.trim().orEmpty()
                    .ifBlank { values["電話"]?.text()?.trim().orEmpty() }
                    .ifBlank { numbers.firstOrNull().orEmpty() },
                fax = values["FAX"]?.text()?.trim().orEmpty().ifBlank { numbers.getOrNull(1).orEmpty() },
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.csd.or.jp"),
                email = DirectoryCrawlerSupport.extractEmail(table.text(), links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = "$url#${heading.id()}",
                ministers = ministerCell?.text()?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty(),
            )
        }.distinctBy { it.name to it.address }
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}
