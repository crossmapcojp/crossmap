package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicYokohamaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "yokohama"
    override val jurisdictionNames = setOf("横浜教区・神奈川県", "横浜教区・静岡県", "横浜教区・長野県", "横浜教区・山梨県")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val prefecture = when {
            "_s" in url -> "静岡県"
            "_n" in url -> "長野県"
            "_y" in url -> "山梨県"
            else -> "神奈川県"
        }
        val blocks = Jsoup.parse(html, url).select(".block")
        return blocks.mapIndexedNotNull { index, block ->
            val heading = block.selectFirst("span.fsize_ll") ?: return@mapIndexedNotNull null
            val name = heading.text().trim().takeIf { it.startsWith("カトリック") && it.contains("教会") }
                ?: return@mapIndexedNotNull null
            val details = buildString {
                blocks.drop(index + 1).takeWhile { it.selectFirst("span.fsize_ll") == null }.forEach { append(' ').append(it.text()) }
            }
            val links = blocks.drop(index + 1).takeWhile { it.selectFirst("span.fsize_ll") == null }.flatMap { it.select("a[href]") }
            val phones = phonePattern.findAll(details).map { it.value }.toList()
            val minister = Regex("(?:主任司祭|小教区管理者)[：:]?\\s*([^主〒]+)").find(details)?.groupValues?.get(1)
                ?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty()
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.addressFromText(details), jurisdiction = "横浜教区・$prefecture",
                phone = phones.getOrNull(0).orEmpty(), fax = phones.getOrNull(1).orEmpty(),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.yokohama.catholic.jp"),
                email = DirectoryCrawlerSupport.extractEmail(details, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links), ministers = minister,
                denominationChurchListDetailPage = "$url#${block.id()}",
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }
}

private val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
