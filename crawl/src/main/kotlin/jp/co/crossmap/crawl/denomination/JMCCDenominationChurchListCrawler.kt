package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JMCCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JMCC"
    override val denominationName = "日本メノナイト・キリスト教会会議"
    override val outputFileName = "jmcc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("#churchs li > .box").mapNotNull { card ->
            val name = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val text = card.text().replace(Regex("""\s+"""), " ").trim()
            val address = addressPattern.find(text)?.groupValues?.get(1)
                ?.replace("ホームページ", "")
                ?.replace("詳しくはコチラ", "")
                ?.replace(trailingDuplicatePostalCode, "")
                ?.replace("〒885-00126", "〒885-0012")
                ?.let(::addPrefecture)
                ?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
            val links = card.select("a[href]")
            val ministry = ministryPattern.find(text)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phonePattern.find(text)?.value.orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "mennonite.jpn.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministry?.let {
                    ChurchMinisterParser.fromRoleAndNames(
                        it.groupValues[1].replace("代表", "教職"),
                        it.groupValues[2],
                    )
                }.orEmpty(),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private fun looksLikeChurchName(value: String): Boolean =
        value.endsWith("教会") || value.endsWith("兄弟団")

    private fun addPrefecture(address: String): String {
        if (prefecturePattern.containsMatchIn(address)) return address
        val prefecture = municipalityPrefectures.entries.firstOrNull { (_, municipalities) ->
            municipalities.any(address::contains)
        }?.key.orEmpty()
        return if (prefecture.isBlank()) address else address.replaceFirst(postalPrefixPattern, "$0$prefecture")
    }

    private companion object {
        val postalPrefixPattern = Regex("""^〒[0-9０-９]{3}[-－ー‐][0-9０-９]{4}\s*""")
        val addressPattern = Regex(
            """(〒[0-9０-９]{3}[-－ー‐][0-9０-９]{4,5}\s*.+?)(?=\s+[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}(?:\s|$)|\s*【|\s+(?:土曜|日曜)|$)""",
        )
        val trailingDuplicatePostalCode = Regex("""〒[0-9０-９]{3}[-－ー‐][0-9０-９]{4}\s*$""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val phonePattern = Regex("""[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}""")
        val ministryPattern = Regex("""【?(協力牧師|牧師|代表)】?\s*[：:]?\s*(.+)$""")
        val municipalityPrefectures = linkedMapOf(
            "兵庫県" to listOf("神戸市"),
            "広島県" to listOf("広島市"),
            "大分県" to listOf("別府市", "大分市"),
            "宮崎県" to listOf("延岡市", "日向市", "宮崎市", "都城市", "小林市", "日南市"),
        )
    }
}
