package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup

class HEJDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "HEJ"
    override val denominationName = "聖イエス会"
    override val sourceUrl = "https://seiiesukai.org/branch/"
    override val outputFileName = "hej-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val withAddresses = DirectoryCrawlerSupport.blocks(document, "tr, article, li, .wp-block-group, .elementor-widget-container")
            .mapNotNull { DirectoryCrawlerSupport.churchFromBlock(it, "seiiesukai.org") }
        val detailOnly = document.select("a[href]").mapNotNull { link ->
            val name = link.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty().ifBlank { link.text().trim() }
            val href = link.absUrl("href")
            if (name.isBlank() || href.isBlank() || !Regex("教会|伝道所|チャペル").containsMatchIn(name)) null
            else {
                val isOfficialDetail = runCatching { URI(href).host == "seiiesukai.org" }.getOrDefault(false)
                OfficialDenominationChurch(
                    name = name,
                    websiteUrl = href.takeUnless { isOfficialDetail }.orEmpty(),
                    denominationChurchListDetailPage = href.takeIf { isOfficialDetail }.orEmpty(),
                )
            }
        }
        return (withAddresses + detailOnly).distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val block = DirectoryCrawlerSupport.blocks(document, "tr, article, main, .entry-content, .wp-block-group").firstOrNull()
            ?: return church.copy(ministers = ChurchMinisterParser.parse(document.text()))
        val parsed = DirectoryCrawlerSupport.churchFromBlock(block, "seiiesukai.org")
        return church.copy(
            address = parsed?.address.orEmpty().ifBlank { church.address },
            phone = parsed?.phone.orEmpty().ifBlank { church.phone },
            fax = parsed?.fax.orEmpty().ifBlank { church.fax },
            websiteUrl = parsed?.websiteUrl.orEmpty().ifBlank { church.websiteUrl },
            ministers = parsed?.ministers.orEmpty().ifEmpty { ChurchMinisterParser.parse(document.text()) },
        )
    }
}
