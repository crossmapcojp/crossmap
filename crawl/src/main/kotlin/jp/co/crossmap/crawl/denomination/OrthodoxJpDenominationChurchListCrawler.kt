package jp.co.crossmap.crawl.denomination

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OrthodoxJpDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId = "ORTHODOX_JP"
    override val denominationName = "日本ハリストス正教会教団"
    override val sourceUrl = "https://www.orthodoxjapan.jp/"
    override val sourceUrls = listOf(
        "https://www.orthodoxjapan.jp/area-tokyo.html",
        "https://www.orthodoxjapan.jp/area-higashi.html",
        "https://www.orthodoxjapan.jp/area-nishi.html",
    )
    override val outputFileName = "orthodox_jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val jurisdiction = when (URI(url).path.substringAfterLast('/')) {
            "area-tokyo.html" -> "東京大主教々区"
            "area-higashi.html" -> "東日本主教々区"
            "area-nishi.html" -> "西日本主教々区"
            else -> ""
        }
        return Jsoup.parse(html, url).select("a[href]").mapNotNull { link ->
            val detailUrl = link.absUrl("href").trim()
            val path = runCatching { URI(detailUrl).path.orEmpty() }.getOrDefault("")
            val name = link.text().replace(Regex("\\s+"), " ").trim()
            if (!Regex("/annai/[thn]-[^/]+\\.html$").containsMatchIn(path) ||
                name.isBlank() || !name.contains(Regex("教会|大聖堂"))
            ) {
                null
            } else {
                OfficialDenominationChurch(
                    name = name,
                    jurisdiction = jurisdiction,
                    denominationChurchListDetailPage = detailUrl,
                )
            }
        }.distinctBy(OfficialDenominationChurch::denominationChurchListDetailPage)
    }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val contact = document.select("table.tcontact").firstOrNull { "所在地" in it.selectFirst("caption")?.text().orEmpty() }
            ?: return church
        // The live site uses alternating <tr><th>label</th></tr><tr><td>value</td></tr>,
        // while some archived pages keep both cells in one row.
        val values = contact.select("tr").mapNotNull { row ->
            val label = row.selectFirst("th")?.text()?.replace(Regex("\\s+"), "")?.trim().orEmpty()
            if (label.isBlank()) null else label to (
                row.selectFirst("td") ?: row.nextElementSibling()?.selectFirst("td")
            )
        }.toMap()
        val address = values["住所"]?.clone()?.also { it.select("a").remove() }?.wholeText()
            ?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()
        val telephone = values["TEL/FAX"]?.text()?.trim().orEmpty()
        val numbers = phonePattern.findAll(telephone).map { it.value }.toList()
        val clergy: Element? = values["所属"] ?: values["管轄"]
        val emailCell: Element? = values["E-mail"]
        val email = if (emailCell == null) "" else DirectoryCrawlerSupport.extractEmail(
            emailCell.text(),
            emailCell.select("a[href]").map { it.attr("href") },
        )
        val websiteLinks = values["URL"]?.select("a[href]").orEmpty()
        return church.copy(
            address = address.ifBlank { church.address },
            phone = numbers.firstOrNull().orEmpty().ifBlank { church.phone },
            fax = numbers.getOrNull(1).orEmpty().ifBlank { church.fax },
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(websiteLinks, "www.orthodoxjapan.jp")
                .ifBlank { church.websiteUrl },
            email = email.ifBlank { church.email },
            ministers = clergy?.let(::parseClergy).orEmpty().ifEmpty { church.ministers },
        )
    }

    private fun parseClergy(cell: Element) = cell.wholeText().lineSequence().flatMap { line ->
        clergyPattern.findAll(line).flatMap { match ->
            val role = match.groupValues[1]
            val officialName = normalizeClergyName(match.groupValues[2])
            ChurchMinisterParser.fromRoleAndNames(role, officialName).asSequence()
        }
    }.distinctBy { it.roleId to it.name }.toList()

    private fun normalizeClergyName(value: String): String {
        val parenthesized = Regex("[（(]([^）)]+)[）)]").find(value)?.groupValues?.get(1)
        val raw = parenthesized ?: value
        val parts = raw.trim().split(Regex("[　\\s]+")).filter(String::isNotBlank)
        return parts.dropWhile { it.matches(Regex("[ァ-ヶー]+")) }.joinToString(" ").ifBlank { raw.trim() }
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-ー－‐][0-9０-９]{1,4}[-ー－‐][0-9０-９]{3,4}")
        val clergyPattern = Regex(
            "(?:^|[\\n、])\\s*(長司祭|修道司祭|司祭|輔祭|伝教者)\\s*([^\\n、]+?)(?=\\s*(?:長司祭|修道司祭|司祭|輔祭|伝教者)|$)",
        )
    }
}
