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
        val selectors = "tr, article, li, .wp-block-group, .elementor-widget-container"
        val withAddresses = document.select(selectors)
            .filter { postal.containsMatchIn(it.text()) }
            .filter { element -> element.select(selectors).none { it !== element && postal.containsMatchIn(it.text()) } }
            .mapNotNull(::parseBranchBlock)
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
        val selectors = "tr, article, main, .entry-content, .wp-block-group"
        val block = document.select(selectors)
            .filter { postal.containsMatchIn(it.text()) }
            .firstOrNull { element -> element.select(selectors).none { it !== element && postal.containsMatchIn(it.text()) } }
            ?: return church.copy(ministers = ChurchMinisterParser.parse(document.text()))
        val parsed = parseBranchBlock(block)
        return church.copy(
            address = parsed?.address.orEmpty().ifBlank { church.address },
            phone = parsed?.phone.orEmpty().ifBlank { church.phone },
            fax = parsed?.fax.orEmpty().ifBlank { church.fax },
            websiteUrl = parsed?.websiteUrl.orEmpty().ifBlank { church.websiteUrl },
            ministers = parsed?.ministers.orEmpty().ifEmpty { ChurchMinisterParser.parse(document.text()) },
        )
    }

    private fun parseBranchBlock(block: org.jsoup.nodes.Element): OfficialDenominationChurch? {
        val text = block.text().trim()
        val address = DirectoryCrawlerSupport.addressFromText(text)
        if (address.isBlank()) return null
        val name = block.select("h1,h2,h3,h4,h5,h6,strong,b,a,th,td")
            .map { it.ownText().ifBlank { it.text() }.trim() }
            .firstOrNull { Regex("教会|伝道所|チャペル").containsMatchIn(it) && !postal.containsMatchIn(it) && it.length <= 80 }
            .orEmpty()
        if (name.isBlank()) return null
        val links = block.select("a[href]")
        val detail = links.firstOrNull { link ->
            runCatching { URI(link.absUrl("href")).host == "seiiesukai.org" }.getOrDefault(false)
        }?.absUrl("href").orEmpty()
        return OfficialDenominationChurch(
            name = name,
            address = address,
            phone = DirectoryCrawlerSupport.phoneFromText(text),
            fax = DirectoryCrawlerSupport.faxFromText(text),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "seiiesukai.org"),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            denominationChurchListDetailPage = detail,
            ministers = ChurchMinisterParser.parse(text),
        )
    }

    private val postal = Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}")
}
