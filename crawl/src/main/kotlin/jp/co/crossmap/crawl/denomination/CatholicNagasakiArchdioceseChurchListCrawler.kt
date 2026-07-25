package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicNagasakiArchdioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "nagasaki"
    override val jurisdictionNames = (1..6).mapTo(linkedSetOf()) { "長崎大司教区・第${it}地区" }

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, url)
        return document.select(".entry-content table").flatMapIndexed { index, table ->
            val jurisdiction = "長崎大司教区・第${index + 1}地区"
            table.select("tr").mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size < 2) return@mapNotNull null
                val raw = cells[0].selectFirst("b")?.text()?.trim() ?: cells[0].ownText().trim()
                if (raw.isBlank() || raw.contains("集会所")) return@mapNotNull null
                val patrol = raw.contains("巡回") || cells[0].text().contains("巡回")
                val base = raw.replace(Regex("[（）()]?(?:巡回)[（）()]?"), "").trim()
                val name = "カトリック${base}教会" + if (patrol) "（巡回）" else ""
                val contact = cells[1].text()
                val phone = phonePattern.find(contact)?.value.orEmpty()
                val addressText = contact.substringBefore(phone.ifBlank { "平日" }).trim()
                val detail = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty()
                OfficialDenominationChurch(
                    name = name, address = DirectoryCrawlerSupport.normalizeAddress("〒$addressText"), jurisdiction = jurisdiction,
                    phone = phone, websiteUrl = detail.takeIf { !it.contains("facebook.com") && !it.contains("on.fb.me") }.orEmpty(),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(cells[0].select("a[href]")),
                )
            }
        }.distinctBy { it.name to it.address }
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}
