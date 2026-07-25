package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class FMCJPDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "FMC_JP"
    override val denominationName = "日本フリーメソジスト教団"
    override val outputFileName = "fmc_jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("table").flatMap { table ->
            val jurisdiction = table.parents().firstOrNull { it.selectFirst("h3") != null }
                ?.selectFirst("h3")?.text()?.trim().orEmpty()
            table.select("tr").mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size < 2) return@mapNotNull null
                val name = cells[0].text().trim()
                if (!looksLikeChurchName(name)) return@mapNotNull null
                val detail = cells[1]
                val text = detail.text()
                OfficialDenominationChurch(
                    name = name,
                    address = DirectoryCrawlerSupport.addressFromText(
                        text.replace(Regex("℡.*"), "").replace(Regex("〒\\s+"), "〒")
                            .replace(Regex("(?<=\\p{L})\\s+(?=\\d)"), ""),
                    ),
                    jurisdiction = jurisdiction,
                    phone = phonePattern.find(text)?.value.orEmpty(),
                    websiteUrl = cells[0].selectFirst("a[href]")?.absUrl("href").orEmpty(),
                    ministers = ChurchMinisterParser.parse(text),
                )
            }
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャペル", "チャーチ").any(name::contains)

    private companion object {
        val phonePattern = Regex("(?<!\\d)0\\d{1,4}-\\d{1,4}-\\d{3,4}(?!\\d)")
    }
}
