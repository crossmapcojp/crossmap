package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicNagoyaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "nagoya"
    override val jurisdictionNames = setOf(
        "名古屋教区・城北ブロック", "名古屋教区・城東ブロック", "名古屋教区・城南ブロック",
        "名古屋教区・愛岐ブロック", "名古屋教区・濃尾ブロック", "名古屋教区・三河ブロック",
        "名古屋教区・北陸ブロック（富山地区）", "名古屋教区・北陸ブロック（石川地区）",
        "名古屋教区・北陸ブロック（福井地区）",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select(".church-block").flatMap { block ->
            val jurisdiction = "名古屋教区・" + block.selectFirst(".block-title")!!.text().replace(Regex("\\s+"), "")
            block.select(".list-wrapper a[href]").mapNotNull { link ->
                val name = link.text().trim().takeIf { it.contains("教会") } ?: return@mapNotNull null
                OfficialDenominationChurch(name = name, jurisdiction = jurisdiction, denominationChurchListDetailPage = link.absUrl("href"))
            }
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val content = document.selectFirst("main, article, .entry-content") ?: document.body()
        val links = content.select("a[href]")
        val text = content.text()
        val ministers = content.select("h2, h3, h4, th, strong").filter { it.text().contains("司祭") }.flatMap { heading ->
            heading.nextElementSibling()?.text()?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty()
        }
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text), phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "nagoya.catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links), ministers = ministers,
        )
    }
}
