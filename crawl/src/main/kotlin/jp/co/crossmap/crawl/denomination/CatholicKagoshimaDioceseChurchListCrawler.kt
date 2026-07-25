package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicKagoshimaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "kagosima"
    override val jurisdictionNames = setOf("鹿児島教区・鹿児島地区", "鹿児島教区・北薩地区", "鹿児島教区・大隅地区", "鹿児島教区・南薩・種子屋久地区", "鹿児島教区・奄美地区")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, url).select("h2 a[href], h3 a[href], article a[href]").mapNotNull { link ->
            val name = link.text().trim().takeIf { it.endsWith("教会") } ?: return@mapNotNull null
            val href = link.absUrl("href")
            val district = when {
                "/amami/" in href -> "奄美地区"
                "/nansatsu/" in href -> "南薩・種子屋久地区"
                "/ohsumi/" in href -> "大隅地区"
                "/hokusatsu/" in href -> "北薩地区"
                else -> "鹿児島地区"
            }
            OfficialDenominationChurch(name = name, jurisdiction = "鹿児島教区・$district", denominationChurchListDetailPage = href)
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val content = document.selectFirst("main, article, .entry-content") ?: document.body()
        val links = content.select("a[href]")
        val text = content.text()
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text), phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "kagoshima-catholic.jp"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
        )
    }
}
