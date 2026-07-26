package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class SFDKDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "SFDK"
    override val denominationName = "世界福音伝道会"
    override val outputFileName = "sfdk-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("li.wp-block-pages-list__item > a.wp-block-pages-list__item__link[href]")
        .mapNotNull { link ->
            val name = link.text().trim()
            val detailUrl = link.absUrl("href")
            if (!looksLikeChurchName(name) || detailUrl.isBlank()) return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = detailUrl,
            )
        }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val main = document.selectFirst("main") ?: document.body()
        val text = main.text()
        val address = DirectoryCrawlerSupport.addressFromText(text).trimEnd('（', '(').trim()
        val ministers = ministerPattern.findAll(text).flatMap { match ->
            val role = if (match.groupValues[1] == "牧会スタッフ") "牧師" else match.groupValues[1]
            ChurchMinisterParser.fromRoleAndNames(role, match.groupValues[2].trim()).asSequence()
        }.toList()
        val homepage = homepagePattern.find(text)?.groupValues?.get(1)?.trim()
            ?: shortSfdkWebsitePattern.find(text)?.value.orEmpty()
        return church.copy(
            address = address,
            jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            phone = phonePattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
            fax = faxPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
            websiteUrl = homepage,
            ministers = ministers.distinctBy { it.roleId to it.name },
        )
    }

    private fun looksLikeChurchName(value: String): Boolean =
        listOf("教会", "チャペル").any(value::contains)

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val phonePattern = Regex(
            """[（(](?:電話|連絡先)[）)]\s*([0-9０-９()（）+\-ー－‐/\s　]{8,}?)(?=\s*[（(](?:ファックス|牧師|牧会スタッフ|協力宣教師|ホームページ)[）)]|$)""",
        )
        val faxPattern = Regex(
            """[（(]ファックス[）)]\s*([0-9０-９()（）+\-ー－‐/\s　]{8,}?)(?=\s*[（(](?:牧師|ホームページ)[）)]|$)""",
        )
        val ministerPattern = Regex(
            """[（(](牧師|牧会スタッフ|協力宣教師|宣教師)[）)]\s*(.+?)(?=\s*[（(](?:ホームページ|電話|連絡先|ファックス|牧師|牧会スタッフ|協力宣教師|宣教師)[）)]|\s+https?://|\s+集いのご案内|\s*コメント|$)""",
        )
        val homepagePattern = Regex("""[（(]ホームページ[）)]\s*(https?://\S+)""")
        val shortSfdkWebsitePattern = Regex("""https?://www\.sfdk\.org/[a-z0-9-]+/""")
    }
}
