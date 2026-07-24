package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup

class TPKFDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "TPKF"
    override val denominationName = "単立ペンテコステ教会フェローシップ"
    override val sourceUrl = "https://tpkf.org/localch_group.html"
    override val outputFileName = "tpkf-churches.json"
    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("table tr").mapNotNull { row ->
            val cells = row.select("th,td")
            if (cells.size < 3 || !cells[0].text().contains(Regex("教会|チャペル|センター"))) return@mapNotNull null
            val text = row.text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            if (address.isBlank()) return@mapNotNull null
            val name = cells[0].select("a[href],span.nolink,strong,b").firstOrNull { it.text().contains(Regex("教会|チャペル|センター")) }?.text()?.trim()
                ?: Regex("^(.+?(?:教会|チャペル|センター))").find(cells[0].text())?.groupValues?.get(1)
                ?: return@mapNotNull null
            val links = row.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = cells.getOrNull(3)?.text()?.trim().orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tpkf.org"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                denominationChurchListDetailPage = links.firstOrNull { it.absUrl("href").contains("tpkf.org") }?.absUrl("href").orEmpty(),
                ministers = parseMinisters(cells[0].text()),
            )
        }.distinctBy { it.name to it.address }

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        // A regional page contains many churches. The list URL fragment points at an anchor
        // inside the one LineBox that belongs to this church; parsing the whole page would
        // copy another church's website/social links onto every church in that region.
        val fragment = runCatching { URI(church.denominationChurchListDetailPage).fragment }.getOrNull().orEmpty()
        val detail = if (fragment.isNotBlank()) {
            document.getElementById(fragment)?.closest(".LineBox1_wrapper1") ?: return church
        } else {
            document.selectFirst("main,article,#contents") ?: document.body()
        }
        val text = detail.text()
        val links = detail.select("a[href]")
        return church.copy(
            address = DirectoryCrawlerSupport.addressFromText(text).ifBlank { church.address },
            phone = DirectoryCrawlerSupport.phoneFromText(text).ifBlank { church.phone },
            fax = DirectoryCrawlerSupport.faxFromText(text).ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "tpkf.org").ifBlank { church.websiteUrl },
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }).ifBlank { church.email },
            socialProfiles = (church.socialProfiles + DirectoryCrawlerSupport.socialProfiles(links)).distinctBy { it.platform to it.url },
            ministers = parseMinisters(text).ifEmpty { church.ministers },
        )
    }

    private fun parseMinisters(text: String): List<jp.co.crossmap.ChurchMinister> {
        val ministerText = text.substringBefore("〒")
        val matches = ministerRoles.findAll(ministerText).toList()
        return matches.flatMapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: ministerText.length
            val names = ministerText.substring(match.range.last + 1, end)
                .replace(Regex("^[\\s　：:・/]+"), "")
                .replace(Regex("^[（(][^）)]*[）)]\\s*"), "")
                .trim()
            ChurchMinisterParser.fromRoleAndNames(match.value, names)
        }.distinctBy { it.roleId to it.name }
    }

    private companion object {
        val ministerRoles = Regex(
                "ヨシュアプロジェクト国内宣教師|派遣牧師(?:[（(]ブライダル[）)])?|" +
                "主任牧師|担任牧師|副牧師|協力牧師|牧師|伝道師|宣教師|教師",
        )
    }
}
