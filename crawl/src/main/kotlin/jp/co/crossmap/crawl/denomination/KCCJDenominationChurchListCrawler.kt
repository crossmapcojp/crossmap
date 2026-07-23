package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class KCCJDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "KCCJ"
    override val denominationName = "在日大韓基督教会"
    override val sourceUrl = "https://kccj.jp/church_list.php"
    override val outputFileName = "kccj-churches.json"
    override val sourceUrls = listOf(
        sourceUrl,
        "$sourceUrl?page=2&chihokai=&keyfield=&key=",
        "$sourceUrl?page=3&chihokai=&keyfield=&key=",
        "$sourceUrl?page=4&chihokai=&keyfield=&key=",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("tr")
        .mapNotNull { row ->
            val cells = row.select("th, td")
            val addressIndex = cells.indexOfFirst { Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}").containsMatchIn(it.text()) }
            if (addressIndex < 0) return@mapNotNull null
            val nameIndex = cells.indexOfFirst { Regex("教会|伝道所").containsMatchIn(it.text()) }
            if (nameIndex < 0) return@mapNotNull null
            val name = cells[nameIndex].text().trim()
            if (name in excludedEntities) return@mapNotNull null
            val parsed = DirectoryCrawlerSupport.churchFromBlock(row, "kccj.jp")
            val detail = cells[nameIndex].selectFirst("a[href]")?.absUrl("href")?.trim().orEmpty()
            val staffText = cells.getOrNull(nameIndex + 1)?.text().orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = parsed?.address ?: DirectoryCrawlerSupport.normalizeAddress(cells[addressIndex].text()),
                phone = parsed?.phone.orEmpty(),
                fax = parsed?.fax.orEmpty(),
                denominationChurchListDetailPage = detail,
                ministers = ChurchMinisterParser.parse(staffText),
            )
        }
        .distinctBy { it.name to it.address }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val text = document.text()
        val externalWebsite = document.select("a[href]").map { it.absUrl("href") }.firstOrNull { url ->
            url.startsWith("http") && !url.contains("kccj.jp/")
        }.orEmpty()
        return church.copy(
            websiteUrl = externalWebsite,
            ministers = church.ministers.ifEmpty { ChurchMinisterParser.parse(text) },
        )
    }

    private companion object {
        val excludedEntities = setOf(
            "総会事務局", "在日韓国基督教会館", "在日韓国人問題研究所(RAIK)", "西南KCC", "全国教会女性連合会",
            "在日総会神学校", "関西聖書神学院", "在日本韓国基督教青年会(YMCA)", "関西韓国YMCA", "桜本保育園",
            "打越保育園", "永信保育園", "向上社保育園", "向上社児童館", "愛信保育園", "イカイノ保育園",
            "サカエ保育園", "永生苑", "永生苑新明", "永生苑豊橋", "ケアハウスセットンの家",
            "精神障害碍者支援に取り組む「社会福祉法人サワリ」",
        )
    }
}
