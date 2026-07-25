package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JECDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JEC"
    override val denominationName = "日本福音教会"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "jec-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, url)
        .select("article").mapNotNull { article ->
            val name = article.selectFirst("h2.entry-title, h1.entry-title")?.text()?.trim().orEmpty()
            if (!looksLikeChurchName(name) || name.contains("本部事務所")) return@mapNotNull null
            val content = article.selectFirst(".entry-content") ?: article
            val text = content.text()
            val links = content.select("a[href]")
            val addressText = text.substringAfter("住所：", text.substringAfter("住所", text))
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.addressFromText(addressText),
                jurisdiction = prefectures[url.substringAfter("/pref/").substringBefore('/').lowercase()].orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text.replace("電話番号", "TEL")),
                fax = DirectoryCrawlerSupport.faxFromText(text.replace("ファクス", "FAX")),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "jec-net.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.fromRoleAndNames("教職", text.substringAfter("教職者：", "").substringBefore("電話番号")),
            )
        }

    private fun looksLikeChurchName(name: String) = listOf("教会", "チャペル", "チャーチ").any(name::contains)

    private companion object {
        val prefectures = mapOf(
            "gunma" to "群馬県", "tokyo" to "東京都", "kanagawa" to "神奈川県", "nagano" to "長野県",
            "shizuoka" to "静岡県", "aichi" to "愛知県", "mie" to "三重県", "kyoto" to "京都府",
            "osaka" to "大阪府", "hyogo" to "兵庫県", "nara" to "奈良県", "wakayama" to "和歌山県",
            "shimane" to "島根県", "okayama" to "岡山県", "tokushima" to "徳島県", "fukuoka" to "福岡県",
        )
    }
}
