package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JCBADenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "JCBA"
    override val denominationName = "保守バプテスト同盟"
    override val sourceUrls = listOf(
        "https://doumei.holy.jp/churches/%e9%9d%92%e6%a3%ae%e7%9c%8c%e3%83%bb%e5%b2%a9%e6%89%8b%e7%9c%8c%e3%83%bb%e7%a7%8b%e7%94%b0%e7%9c%8c/",
        "https://doumei.holy.jp/churches/%e5%ae%ae%e5%9f%8e%e7%9c%8c/",
        "https://doumei.holy.jp/churches/%e5%b1%b1%e5%bd%a2%e7%9c%8c/",
        "https://doumei.holy.jp/churches/%e7%a6%8f%e5%b3%b6%e7%9c%8c%e5%8c%97%e9%96%a2%e6%9d%b1/",
        "https://doumei.holy.jp/churches/%e9%a6%96%e9%83%bd%e5%9c%8f%e9%95%b7%e5%b4%8e%e7%9c%8c/",
    )
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "jcba-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, url)
        return document.select("main p.wp-block-paragraph,.entry-content p.wp-block-paragraph").mapNotNull { paragraph ->
            val firstLine = Jsoup.parseBodyFragment(paragraph.html().substringBefore("<br")).text().trim()
            if (!firstLine.contains(Regex("教会|チャペル"))) return@mapNotNull null
            val text = paragraph.text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            if (address.isBlank()) return@mapNotNull null
            val links = paragraph.select("a[href]")
            OfficialDenominationChurch(
                name = firstLine,
                address = address,
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "doumei.holy.jp"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }.distinctBy { it.name to it.address }
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        return church
    }
}
