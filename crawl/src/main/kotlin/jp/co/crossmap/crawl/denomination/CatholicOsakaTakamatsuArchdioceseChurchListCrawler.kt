package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicOsakaTakamatsuArchdioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "ostk"
    override val jurisdictionNames = setOf("大阪高松大司教区・大阪府", "大阪高松大司教区・兵庫県", "大阪高松大司教区・和歌山県", "大阪高松大司教区・香川県", "大阪高松大司教区・愛媛県", "大阪高松大司教区・徳島県", "大阪高松大司教区・高知県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("figure.parish_list").flatMap { figure ->
            val prefecture = figure.previousElementSibling()?.text().orEmpty().ifBlank {
                figure.parent()?.selectFirst("h3")?.text().orEmpty()
            }
            figure.select("tbody tr").mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size < 2) return@mapNotNull null
                val name = cells[0].text().trim().takeIf { it.endsWith("教会") } ?: return@mapNotNull null
                val detail = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty()
                val contact = cells[1].text()
                val address = Regex("(?:〒?\\s*)?([0-9０-９]{3}[-‐－ー][0-9０-９]{4}.*?)(?=\\s+[0-9０-９]{2,5}[-‐－ー])")
                    .find(contact)?.groupValues?.get(1)?.let { DirectoryCrawlerSupport.normalizeAddress("〒$it") }.orEmpty()
                OfficialDenominationChurch(
                    name = name, address = address, jurisdiction = "大阪高松大司教区・$prefecture",
                    phone = phonePattern.find(contact)?.value.orEmpty(), websiteUrl = detail,
                    denominationChurchListDetailPage = "",
                )
            }
        }.distinctBy { it.name to it.address }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}
