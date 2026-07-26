package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JMSDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JMS"
    override val denominationName = "日本宣教会"
    override val outputFileName = "jms-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl)
            .select("h2.elementor-heading-title")
            .mapNotNull { heading ->
                val name = heading.text().trim()
                if (name !in churchNames) return@mapNotNull null
                val card = heading.closest("div.e-con.e-child") ?: heading.parent()
                val text = card.text()
                val links = card.select("a[href]")
                val address = if (name == "喜多見チャペル") {
                    ""
                } else {
                    DirectoryCrawlerSupport.addressFromText(text).let { value ->
                        if (value.contains("世田谷区") && !value.contains("東京都")) {
                            value.replace(Regex("""^(〒\d{3}-\d{4}\s+)(?=世田谷区)"""), "$1東京都")
                        } else {
                            value
                        }
                    }
                }
                val phone = labeledPhone.find(text)?.groupValues?.get(1).orEmpty()
                val website = links.map { it.absUrl("href") }.firstOrNull { url ->
                    url.startsWith("http") &&
                        !url.contains("maps.app.goo.gl") &&
                        !url.contains("g.co/kgs/") &&
                        !url.contains("nihonsenkyoukai.com")
                }.orEmpty()
                OfficialDenominationChurch(
                    name = name,
                    address = address,
                    jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                    phone = phone,
                    fax = phone.takeIf { telFax.containsMatchIn(text) }.orEmpty(),
                    websiteUrl = website,
                    email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                    socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                    ministers = ChurchMinisterParser.parse(text),
                )
            }
            .distinctBy(OfficialDenominationChurch::name)

    private companion object {
        val churchNames = setOf(
            "代田教会",
            "西調布キリスト教会",
            "小千谷キリスト宣教会",
            "きさらづキリスト教会",
            "狭山キリスト教会",
            "三沢集会所",
            "喜多見チャペル",
        )
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val labeledPhone = Regex(
            """(?:TEL(?:/FAX)?|Tel|電話)\s*[:：]?\s*([0-9０-９\-ー－‐]{10,})""",
            RegexOption.IGNORE_CASE,
        )
        val telFax = Regex("""TEL/FAX""", RegexOption.IGNORE_CASE)
    }
}
