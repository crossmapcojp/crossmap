package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class ChristEvangelizationTeamDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CHRIST_EVANGELIZATION_TEAM"
    override val denominationName = "キリスト伝道隊"
    override val outputFileName = "christ-evangelization-team-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("main .post_content h2").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val section = heading.nextElementSiblings().takeWhile { it.tagName() != "h2" }
            val text = section.joinToString(" ") { it.text() }
            val links = section.flatMap { it.select("a[href]") }
            val addressMatch = addressPattern.find(text) ?: municipalityAddressPattern.find(text)
            val address = addressMatch?.groupValues?.get(1)
                ?.let(DirectoryCrawlerSupport::normalizeAddress)
                ?.let(::addPrefecture).orEmpty()
            val ministryText = text.substring(0, addressMatch?.range?.first ?: text.length)
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phonePattern.find(text)?.value.orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "dendoutai.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(ministryText),
            )
        }

    private fun looksLikeChurchName(value: String): Boolean =
        listOf("教会", "チャペル", "伝道館").any(value::contains)

    private fun addPrefecture(address: String): String {
        if (prefecturePattern.containsMatchIn(address)) return address
        val prefecture = municipalityPrefectures.entries.firstOrNull { (municipality) ->
            address.contains(municipality)
        }?.value.orEmpty()
        val postal = Regex("""^(〒?\d{3}-\d{4}\s*)""")
        return if (postal.containsMatchIn(address)) {
            address.replace(postal, "$1$prefecture")
        } else {
            "$prefecture$address"
        }
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|.{2,3}県""")
        val phonePattern = Regex("""[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}""")
        val addressPattern = Regex("""(?:住\s*所|住所)[：:]\s*(.+?)(?=\s*(?:電\s*話|電話)[：:]|$)""")
        val municipalityAddressPattern = Regex(
            """((?:新宿区|平塚市|富津市|熊谷市|甲賀市|大阪市|京都市|宇治市|三次市|広島市|美馬市|美馬郡).+?)(?=\s*[0０][0-9０-９]{1,4}[-－ー‐][0-9０-９]{1,4}[-－ー‐][0-9０-９]{3,4}(?:\s|$)|$)""",
        )
        val municipalityPrefectures = linkedMapOf(
            "新宿区" to "東京都",
            "平塚市" to "神奈川県",
            "富津市" to "千葉県",
            "熊谷市" to "埼玉県",
            "甲賀市" to "滋賀県",
            "大阪市" to "大阪府",
            "京都市" to "京都府",
            "宇治市" to "京都府",
            "三次市" to "広島県",
            "広島市" to "広島県",
            "美馬市" to "徳島県",
            "美馬郡" to "徳島県",
        )
    }
}
