package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicFukuokaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "fukuoka"
    override val jurisdictionNames = setOf("福岡教区・福岡", "福岡教区・筑後", "福岡教区・北九州", "福岡教区・佐賀", "福岡教区・熊本")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("div.col_2").mapNotNull { card ->
            val values = card.select("tr").associate { row -> row.selectFirst("th")?.text().orEmpty() to row.selectFirst("td")?.text().orEmpty() }
            val link = card.select("a[title*=教会][href]").firstOrNull { it.text().isNotBlank() }
                ?: card.selectFirst("a[title*=教会][href]") ?: return@mapNotNull null
            val name = link.text().ifBlank { link.attr("title") }.substringBefore('/').trim()
                .takeIf { it.endsWith("教会") || it.contains("教会(") } ?: return@mapNotNull null
            val area = card.parent()?.classNames()?.firstOrNull { it in areaByClass }?.let(areaByClass::get).orEmpty().ifBlank { "福岡" }
            val phones = phonePattern.findAll(values["TEL / FAX"].orEmpty()).map { it.value }.toList()
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.normalizeAddress(values["住所"].orEmpty()),
                jurisdiction = "福岡教区・$area", phone = phones.getOrNull(0).orEmpty(), fax = phones.getOrNull(1).orEmpty(),
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val content = document.selectFirst("main, article, #content") ?: document.body()
        val links = content.select("a[href]")
        val text = content.text()
        val ministers = Regex("(?:主任司祭|司祭)[：:]?\\s*([^。|｜]+)").find(text)?.groupValues?.get(1)
            ?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty()
        return church.copy(
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "fukuoka.catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links), ministers = ministers,
        )
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
        val areaByClass = mapOf("fuk" to "福岡", "chikugo" to "筑後", "kitakyu" to "北九州", "saga" to "佐賀", "kuma" to "熊本")
    }
}
