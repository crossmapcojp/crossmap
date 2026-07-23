package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class SDAJPDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "SDA_JP"
    override val denominationName = "セブンスデー・アドベンチスト教団"
    override val sourceUrl = "https://adventist.jp/%E6%95%99%E4%BC%9A%E6%89%80%E5%9C%A8%E5%9C%B0/%E6%95%99%E4%BC%9A%E4%B8%80%E8%A6%A7/"
    override val outputFileName = "sda-jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val structured = DirectoryCrawlerSupport.blocks(document, "tr, article, li, .wp-block-group, .elementor-widget-container")
            .mapNotNull { DirectoryCrawlerSupport.churchFromBlock(it, "adventist.jp") }
        val cards = document.select(".rmc-item").mapNotNull { card ->
            val heading = card.selectFirst(".rmc-product-name,h2,h3,h4")?.text()?.trim().orEmpty()
            val name = Regex("^(.+?(?:キリスト教会|教会|集会所|伝道所))").find(heading)?.groupValues?.get(1).orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val contact = card.select("p").firstOrNull { phone.containsMatchIn(it.text()) }?.text().orEmpty()
            val phoneNumber = phone.find(contact)?.value.orEmpty()
            val rawAddress = contact.removeSuffix(phoneNumber).trim()
            val detail = card.selectFirst("a[href*=/church/]")?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = rawAddress.takeIf(String::isNotBlank)?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty(),
                phone = phoneNumber,
                denominationChurchListDetailPage = detail,
            )
        }
        return (cards + structured).distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val relevant = document.select("article,main,.entry-content,.site-content")
            .firstOrNull { it.text().contains(church.name.removeSuffix("キリスト教会").removeSuffix("教会")) }
        val parsed = relevant?.let { DirectoryCrawlerSupport.churchFromBlock(it, "adventist.jp") }
        return church.copy(
            address = parsed?.address.orEmpty().ifBlank { church.address },
            phone = parsed?.phone.orEmpty().ifBlank { church.phone },
            websiteUrl = parsed?.websiteUrl.orEmpty().ifBlank { church.websiteUrl },
            ministers = parsed?.ministers.orEmpty(),
        )
    }

    private val phone = Regex("[0-9０-９]{2,4}[-ー－‐][0-9０-９]{2,4}[-ー－‐][0-9０-９]{3,4}")
}
