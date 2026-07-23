package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JECADenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JECA"
    override val denominationName = "日本福音キリスト教会連合"
    override val sourceUrl = "https://jeca.jp/church/index.html"
    override val outputFileName = "jeca-churches.json"
    override val sourceUrls = listOf(
        "https://jeca.jp/church/church/hokkaido.html",
        "https://jeca.jp/church/church/touhoku.html",
        "https://jeca.jp/church/church/kitakanto.html",
        "https://jeca.jp/church/church/nakakanto.html",
        "https://jeca.jp/church/church/nishikanto.html",
        "https://jeca.jp/church/church/minamikanto.html",
        "https://jeca.jp/church/church/chubu.html",
        "https://jeca.jp/church/church/nisinippon.html",
        "https://jeca.jp/church/code/nisinippon.html",
        "https://jeca.jp/church/church/okinawa.html",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("tr").mapNotNull { row ->
            val cells = row.select("th, td")
            val nameCell = cells.firstOrNull { Regex("教会|伝道所|チャペル").containsMatchIn(it.text()) }
                ?: return@mapNotNull null
            val name = nameCell.text().trim()
            if (name.length > 80) return@mapNotNull null
            val text = row.text()
            val parsed = DirectoryCrawlerSupport.churchFromBlock(row, "jeca.jp")
            val link = row.selectFirst("a[href]")?.absUrl("href")?.trim().orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = parsed?.address.orEmpty(),
                phone = parsed?.phone.orEmpty(),
                fax = parsed?.fax.orEmpty(),
                websiteUrl = link.takeUnless { it.contains("jeca.jp/church/") }.orEmpty(),
                denominationChurchListDetailPage = link.takeIf { it.contains("jeca.jp/church/") }.orEmpty(),
                ministers = ChurchMinisterParser.parse(text),
            )
        }
    }

    override fun merge(churches: List<OfficialDenominationChurch>): List<OfficialDenominationChurch> = churches
        .groupBy { normalizeName(it.name) }
        .values
        .map { rows ->
            rows.reduce { result, row ->
                result.copy(
                    address = result.address.ifBlank { row.address },
                    phone = result.phone.ifBlank { row.phone },
                    fax = result.fax.ifBlank { row.fax },
                    websiteUrl = result.websiteUrl.ifBlank { row.websiteUrl },
                    denominationChurchListDetailPage = result.denominationChurchListDetailPage.ifBlank { row.denominationChurchListDetailPage },
                    ministers = (result.ministers + row.ministers).distinctBy { it.roleId to it.name },
                )
            }
        }

    private fun normalizeName(value: String): String = value.replace(Regex("[\\s　]+"), "").trim()
}
