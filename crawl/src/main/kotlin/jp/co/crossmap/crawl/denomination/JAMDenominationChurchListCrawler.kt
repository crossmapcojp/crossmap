package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class JAMDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JAM"
    override val denominationName = "日本アライアンス・ミッション"
    override val outputFileName = "jam-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("div.location").mapNotNull { card ->
            val englishName = card.selectFirst("h4")?.text()?.trim().orEmpty()
            val identity = identities[englishName] ?: return@mapNotNull null
            val text = card.text()
            val links = card.select("a[href]")
            OfficialDenominationChurch(
                name = identity.name,
                address = identity.address,
                jurisdiction = identity.jurisdiction,
                phone = internationalPhone.find(text)?.groupValues?.get(1)
                    ?.let { "0$it" }
                    .orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "japanalliancemission.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                localizedNames = listOf(
                    LocalizedName("ja", identity.name),
                    LocalizedName("en", englishName),
                ),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private data class Identity(
        val name: String,
        val address: String,
        val jurisdiction: String,
    )

    private companion object {
        val internationalPhone = Regex("""\+81\s+([0-9]{1,4}-[0-9-]+)""")
        val identities = linkedMapOf(
            "Asahigaoka Christ Church" to Identity(
                "旭が丘キリスト教会",
                "〒191-0065 東京都日野市旭が丘１丁目２５−１１",
                "東京都",
            ),
            "Kawaguchi Christ Church" to Identity(
                "川口キリスト教会",
                "〒333-0861 埼玉県川口市柳崎５丁目１−６８",
                "埼玉県",
            ),
            "Narita Evangelical Church" to Identity(
                "成田福音教会",
                "〒286-0043 千葉県成田市大袋３５６−１",
                "千葉県",
            ),
            "Sengendai Christ Church" to Identity(
                "千間台キリスト教会",
                "〒343-0041 埼玉県越谷市千間台西１丁目８−１５",
                "埼玉県",
            ),
            "Tokyo Shibuya Evangelical Church" to Identity(
                "東京渋谷福音教会",
                "〒150-0036 東京都渋谷区南平台町６−１７",
                "東京都",
            ),
            "Yachiyo Alliance Mission Church" to Identity(
                "八千代福音キリスト教会",
                "〒276-0031 千葉県八千代市八千代台北５丁目８−１７",
                "千葉県",
            ),
        )
    }
}
