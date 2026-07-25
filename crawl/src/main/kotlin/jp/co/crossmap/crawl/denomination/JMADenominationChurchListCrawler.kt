package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import jp.co.crossmap.crawl.Rfc4180Csv

class JMADenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JMA"
    override val denominationName = "日本宣教連合会"
    override val sourceUrl = "resource:crawl/jma-churches.csv"
    override val outputFileName = "jma-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val rows = Rfc4180Csv.parse(html).filter { row -> row.any(String::isNotBlank) }
        require(rows.isNotEmpty()) { "JMA fixture is empty" }
        val header = rows.first().map { it.removePrefix("\uFEFF").trim() }
        fun column(name: String): Int = header.indexOf(name).also { require(it >= 0) { "JMA CSV is missing $name: $header" } }
        val nameColumn = column("教会名")
        val postalCodeColumn = column("郵便番号")
        val addressColumn = column("住所")
        val ministerColumn = column("担任教師")
        return rows.drop(1).mapNotNull { row ->
            val name = row.getOrNull(nameColumn)?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val postalCode = row.getOrNull(postalCodeColumn)?.trim().orEmpty()
            val rawAddress = row.getOrNull(addressColumn)?.trim().orEmpty()
            val ministerName = row.getOrNull(ministerColumn)?.trim().orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.normalizeAddress(listOf(postalCode.takeIf(String::isNotBlank)?.let { "〒$it" }, rawAddress).filterNotNull().joinToString(" ")),
                jurisdiction = prefectureFrom(rawAddress),
                ministers = ChurchMinisterParser.fromRoleAndNames("担任教師", ministerName).map { minister ->
                    koreanMinisterNames[minister.name]?.let { korean ->
                        minister.copy(localizedNames = listOf(LocalizedName("ko", korean)))
                    } ?: minister
                },
            )
        }
    }

    private fun prefectureFrom(address: String): String =
        Regex("^(?:北海道|東京都|京都府|大阪府|.{2,3}県)").find(address)?.value.orEmpty()

    private companion object {
        val koreanMinisterNames = mapOf(
            "申吹錫" to "신취석", "李格寅" to "이격인", "朴今錫" to "박금석", "蔡連培" to "채연배",
            "呉洙眞" to "오수진", "康泰榮" to "강태영", "閔信基" to "민신기", "羅大路" to "나대로",
            "楊在成" to "양재성", "喩冬" to "유동", "權寧滿" to "권영만", "鄭秀鎭" to "정수진",
            "尹在卿" to "윤재경", "高采煜" to "고채욱", "林東晛" to "임동현", "金其南" to "김기남",
            "文栄喚" to "문영환", "金順子" to "김순자", "金恩栄" to "김은영", "程鉉熙" to "정현희",
            "朴鍾國" to "박종국", "嚴善一" to "엄선일", "具ハンビョル" to "구한별", "曹乙用" to "조을용",
            "崔現郁" to "최현욱", "郭宝慶" to "곽보경", "朴善姫" to "박선희", "李玉心" to "이옥심",
            "金明洙" to "김명수", "曹圭範" to "조규범", "洪永淳" to "홍영순", "姜成勲" to "강성훈",
            "全錦璟" to "전금경",
        )
    }
}
