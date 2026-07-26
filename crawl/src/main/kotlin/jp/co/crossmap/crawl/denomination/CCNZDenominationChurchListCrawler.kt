package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CCNZDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "CCNZ"
    override val denominationName = "チャーチオブクライストニュージーランド日本"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "ccnz-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        if (url.endsWith("/church/access.html")) {
            parseOsakaChurch(url, html)
        } else {
            parseBranchChurches(url, html)
        }

    private fun parseBranchChurches(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("div.grBox").mapNotNull { card ->
            val name = card.selectFirst("h4")?.text()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            val rows = card.select("table tr").mapNotNull { row ->
                val label = row.selectFirst("th")?.text()?.trim().orEmpty()
                val value = row.selectFirst("td")?.text()?.trim().orEmpty()
                label.takeIf(String::isNotBlank)?.let { it to value }
            }
            val values = rows.toMap()
            val addresses = rows.filter { (label) -> label.contains("住所") }.map(Pair<String, String>::second)
            val isOsakaContactOnly = values["連絡先"] == "大阪教会" &&
                addresses.size == 1 && addresses.single().contains("大阪府吹田市青山台")
            val address = if (isOsakaContactOnly) {
                ""
            } else {
                addresses.lastOrNull()
                    ?.let(DirectoryCrawlerSupport::normalizeAddress)
                    .orEmpty()
            }
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value
                    ?: jurisdictionByName.entries.firstOrNull { name.contains(it.key) }?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText("TEL ${values["TEL"].orEmpty()}"),
                fax = DirectoryCrawlerSupport.faxFromText("FAX ${values["FAX"].orEmpty()}"),
                ministers = values["責任者"]?.takeIf(String::isNotBlank)
                    ?.let { ChurchMinisterParser.fromRoleAndNames("責任者", it) }
                    .orEmpty(),
                note = if (isOsakaContactOnly) "公式一覧は大阪教会を連絡先として掲載" else "",
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private fun parseOsakaChurch(url: String, html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, url)
        val text = document.body().text()
        val links = document.select("a[href]")
        if (!text.contains("大阪教会")) return emptyList()
        val address = osakaAddressPattern.find(text)?.value
            ?.let(DirectoryCrawlerSupport::normalizeAddress)
            .orEmpty()
        return listOf(
            OfficialDenominationChurch(
                name = "チャーチオブクライストニュージーランド日本 大阪教会",
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            ),
        )
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val osakaAddressPattern = Regex(
            """〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}\s*(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)[^\sA-Za-z]+""",
        )
        val jurisdictionByName = linkedMapOf(
            "横浜" to "神奈川県",
            "富田林" to "大阪府",
            "西脇" to "兵庫県",
            "加古川" to "兵庫県",
            "小豆島" to "香川県",
        )
    }
}
