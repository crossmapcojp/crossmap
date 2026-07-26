package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class TJCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TJC"
    override val denominationName = "真イエス教会"
    override val outputFileName = "tjc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("div.pro dl.clearfix").mapNotNull { card ->
            val name = card.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            if (!churchNamePattern.containsMatchIn(name)) return@mapNotNull null
            val text = card.text()
            val addressText = card.selectFirst("div.des")?.text() ?: text
            val address = DirectoryCrawlerSupport.addressFromText(addressText)
                .replace("神戶市", "神戸市")
            val links = card.select("a[href]")
            val detailUrl = links.firstOrNull { it.absUrl("href").contains("/churchShow?") }
                ?.absUrl("href")
                ?.substringBefore('#')
                .orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                denominationChurchListDetailPage = detailUrl,
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val values = document.select("div.about1 table.table-condensed tr").mapNotNull { row ->
            val label = row.selectFirst("th")?.text()?.trim()?.trimEnd('：', ':').orEmpty()
            val value = row.selectFirst("td")?.text()?.trim().orEmpty()
            label.takeIf(String::isNotBlank)?.let { it to value }
        }.toMap()
        val detailAddress = values["住所"].orEmpty().takeIf(String::isNotBlank)?.let { address ->
            DirectoryCrawlerSupport.normalizeAddress("〒${values["郵便番号"].orEmpty()} $address")
        }.orEmpty().replace("神戶市", "神戸市")
        return church.copy(
            address = detailAddress.ifBlank { church.address },
            jurisdiction = prefecturePattern.find(detailAddress.ifBlank { church.address })?.value.orEmpty(),
            phone = values["電話番号"].orEmpty(),
            email = values["Email"].orEmpty(),
        )
    }

    private companion object {
        val churchNamePattern = Regex("""(?:教会|祈祷所|家庭集会)$""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}
