package jp.co.crossmap.crawl.denomination

import java.text.Normalizer
import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class NFKDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "NFK"
    override val denominationName = "日本福音教団"
    override val outputFileName = "nfk-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, sourceUrl).select("p, table").forEach { element ->
            if (element.tagName() == "p") {
                element.text().trim().takeIf { it.startsWith("〇") || it.startsWith("○") }
                    ?.let { jurisdiction = it.drop(1).trim() }
                return@forEach
            }
            element.select("tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size < 5) return@forEach
                val name = Normalizer.normalize(cells[0].text().trim(), Normalizer.Form.NFKC)
                if (!looksLikeChurchName(name)) return@forEach
                val postalCode = cells[1].text().trim().removePrefix("〒")
                val ministerText = cells[3].text().trim()
                churches += OfficialDenominationChurch(
                    name = name,
                    address = DirectoryCrawlerSupport.normalizeAddress("〒$postalCode ${cells[2].text().trim()}"),
                    jurisdiction = jurisdiction,
                    phone = cells[4].text().trim(),
                    ministers = parseMinister(ministerText),
                )
            }
        }
        return churches
    }

    private fun parseMinister(value: String) = ChurchMinisterParser.fromRoleAndNames("牧師", baseMinisterName(value)).map { minister ->
        val reading = halfWidthReading(value)
        val korean = koreanNames[minister.name]
        minister.copy(
            localizedNames = listOfNotNull(
                reading.takeIf(String::isNotBlank)?.let { LocalizedName("ja", "${minister.name}（$it）") },
                korean?.let { LocalizedName("ko", it) },
            ),
        )
    }

    private fun baseMinisterName(value: String): String = value.takeWhile { it.code !in 0xFF61..0xFF9F }.trim()

    private fun halfWidthReading(value: String): String = Normalizer.normalize(
        value.dropWhile { it.code !in 0xFF61..0xFF9F },
        Normalizer.Form.NFKC,
    ).trim()

    private fun looksLikeChurchName(name: String) = listOf("教会", "宣教師の家", "チャペル").any(name::contains)

    private companion object {
        val koreanNames = mapOf(
            "車幸任" to "차행림", "朴敏圭" to "박민규", "安重植" to "안중식", "田王成" to "정왕성",
            "崔南道" to "최남도", "蘇妍稀" to "소연희", "李南洙" to "이남수", "邢宗宇" to "형종우",
            "金元植" to "김완숙", "李永子" to "이영자", "趙相賢" to "조상현", "白均鉉" to "백균현",
            "趙成愚" to "조성우", "呉" to "오필제", "洪京淑" to "홍경숙", "李明奎" to "이명규",
        )
    }
}
