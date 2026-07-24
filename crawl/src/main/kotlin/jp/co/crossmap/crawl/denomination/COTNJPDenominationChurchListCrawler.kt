package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class COTNJPDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "COTN_JP"
    override val denominationName = "日本ナザレン教団"
    override val sourceUrl = "https://www.nazarene.or.jp/cm/index.html"
    override val outputFileName = "cotn_jp-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("div.g-column.-col2").mapNotNull { card ->
            val name = card.selectFirst("h3")?.text()?.trim()
            if (name == null || !name.contains("教会")) return@mapNotNull null
            val info = card.selectFirst("p.c-body")
            val text = info?.text().orEmpty()
            val links = card.select("a[href]")
            val detail = links.firstOrNull { link ->
                val href = link.absUrl("href")
                href.contains("/cm/") && !href.endsWith("/cm/index.html")
            }?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.addressFromText(text),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.nazarene.or.jp"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = detail,
                ministers = parseMinisters(text),
            )
        }.distinctBy { it.name }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        // The detail page's main element also contains denomination-wide Facebook/X links.
        // Church-specific contact fields and links live in the primary eight-column block.
        val detail = document.select(".column.-col8.-col_main").firstOrNull { column ->
            DirectoryCrawlerSupport.addressFromText(column.text()).isNotBlank()
        }
            ?: document.select(".column.-col8.-col_main").firstOrNull { column ->
                column.text().contains(church.name.removeSuffix("（伝道所）").removeSuffix("教会"))
            }
            ?: document.selectFirst("main article,#contents article,.contents article")
            ?: document.selectFirst("main,#contents,.contents,article")
            ?: document.body()
        val text = detail.text()
        val links = detail.select("a[href]")
        // Some legacy pages place the pastor line outside the contact column. The
        // denomination-specific label parser is deliberately strict enough to scan
        // the body without treating descriptive uses of the word 牧師 as people.
        val ministerText = document.body().text()
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.nazarene.or.jp").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links))
                .distinctBy { it.platform to it.url },
            ministers = parseMinisters(ministerText).ifEmpty { church.ministers },
        )
    }

    private fun parseMinisters(text: String) = (
        labeledMinister.findAll(text).flatMap { match ->
            val candidate = match.groupValues[2].let { value ->
                val lastToken = value.substringAfterLast(' ', "")
                if (lastToken.contains("教会")) value.substringBeforeLast(' ') else value
            }
            ChurchMinisterParser.fromRoleAndNames(match.groupValues[1], candidate).asSequence()
        } + districtMinister.findAll(text).flatMap { match ->
            ChurchMinisterParser.fromRoleAndNames("牧師", match.groupValues[1]).asSequence()
        }
    ).distinctBy { it.roleId to it.name }.toList()

    private companion object {
        val labeledMinister = Regex(
            "((?:主任|担任|副|協力)?牧師)\\s*[：:]\\s*" +
                "([\\p{L}・･]+(?:[\\s　]+(?![・･])[\\p{L}・･]+)?(?:[（(][^）)]{1,40}[）)])?)",
        )
        val districtMinister = Regex(
            "牧師\\s*[：:]\\s*[^（(]{1,24}地区担当[（(]\\s*([\\p{L}・･\\s　]+?)(?=［|\\[)"
        )
    }
}
