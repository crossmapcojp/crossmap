package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicSaitamaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "saitama"
    override val jurisdictionNames = setOf("さいたま教区・埼玉県", "さいたま教区・栃木県", "さいたま教区・群馬県", "さいたま教区・茨城県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val prefecture = when {
            "key=Tochigi" in url -> "栃木県"
            "key=Gunma" in url -> "群馬県"
            "key=Ibaraki" in url -> "茨城県"
            else -> "埼玉県"
        }
        return Jsoup.parse(html, url).select("table[width=680][bgcolor]").mapNotNull { table ->
            val name = table.selectFirst("font[style*=130] b")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val contact = table.select("tr").firstOrNull { it.selectFirst("img[src*=icon-church]") != null }?.text().orEmpty()
            val phones = phonePattern.findAll(contact).map { it.value }.toList()
            val links = table.select("a[href]")
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.addressFromText(contact), jurisdiction = "さいたま教区・$prefecture",
                phone = phones.getOrNull(0).orEmpty(), fax = phones.getOrNull(1).orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "saitama-kyoku.net"),
            )
        }.distinctBy { it.name to it.address }
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}
