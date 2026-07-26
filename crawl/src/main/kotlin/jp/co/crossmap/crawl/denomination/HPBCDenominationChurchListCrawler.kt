package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class HPBCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "HPBC"
    override val denominationName = "Hawaii Pacific Baptist Convention"
    override val outputFileName = "hpbc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("article.location-asia h2.entry-title a[href]")
        .mapNotNull { link ->
            val name = link.text().trim()
            val detailUrl = link.absUrl("href")
            if (name.isBlank() || detailUrl.isBlank() || nonJapanDetailSlugs.any(detailUrl::contains)) {
                return@mapNotNull null
            }
            OfficialDenominationChurch(
                name = name,
                localizedNames = listOf(LocalizedName("en", name)),
                denominationChurchListDetailPage = detailUrl,
            )
        }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val text = document.selectFirst("main.content")?.text().orEmpty()
        val address = addressPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val pastor = pastorPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return church.copy(
            address = address,
            phone = phonePattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
            ministers = pastor.takeIf(String::isNotBlank)
                ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                .orEmpty(),
        )
    }

    private companion object {
        val nonJapanDetailSlugs = setOf(
            "/church/international-baptist-church-of-manila/",
            "/church/songtan-central-baptist-church/",
        )
        val addressPattern = Regex(
            """Church Address\s+Address:\s*(.+?)(?=\s+Mailing:|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val pastorPattern = Regex(
            """Pastor:\s*(.+?)(?=\s+(?:Pastors Spouse|Phone Numbers|Emails|Church Address)|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val phonePattern = Regex(
            """Phone Numbers:\s*Church:\s*([0-9()+\-\s]+?)(?=\s+(?:Emails|Church Address)|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
