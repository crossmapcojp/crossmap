package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicOitaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "oita"
    override val jurisdictionNames = setOf("大分教区・大分県", "大分教区・宮崎県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val prefecture = if ("/46/" in url) "宮崎県" else "大分県"
        return Jsoup.parse(html, url).select(".record[role=listitem], section.record, div.record").mapNotNull { record ->
            val text = record.text()
            val name = namePattern.find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), "")
                ?.takeIf { it.length >= 3 } ?: return@mapNotNull null
            val phones = phonePattern.findAll(text).map { it.value }.toList()
            val links = record.select("a[href]")
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.addressFromText(text), jurisdiction = "大分教区・$prefecture",
                phone = phones.getOrNull(0).orEmpty(), fax = phones.getOrNull(1).orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "oita-catholic.jp"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            )
        }.distinctBy { it.name to it.address }
    }

    private companion object {
        val namePattern = Regex("([\\p{L}・（）()\\s]+教会)")
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}
