package jp.co.crossmap.crawl.denomination

import java.text.Normalizer
import jp.co.crossmap.ChurchMinister
import org.jsoup.Jsoup

class JCGFDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCGF"
    override val denominationName = "日本神の教会連盟"
    override val outputFileName = "jcgf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("a[href*=/kakukyoukai/]").mapNotNull { link ->
            val name = normalizeName(link.text())
            if (!churchNamePattern.containsMatchIn(name)) return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val links = document.select("a[href]")
        val text = normalizeLegacyText(document.body().text())
            .replace(telephoneLabel, "TEL")
            .replace(faxLabel, "FAX")
        val addressText = document.select("td")
            .firstOrNull { it.text().contains("所在地") }
            ?.text()
            ?: text
        val normalizedAddressText = normalizeLegacyText(addressText)
        val address = DirectoryCrawlerSupport.addressFromText(normalizedAddressText.replace("所在地", "住所"))
            .ifBlank {
                normalizedAddressText.substringAfter("所在地", "")
                    .trimStart('：', ':', ' ')
                    .let(DirectoryCrawlerSupport::normalizeAddress)
            }
        val ministers = document.select("td")
            .filter { ministerRolePattern.containsMatchIn(it.text()) }
            .flatMap { parseMinisterCell(it.text()) }
            .distinctBy { it.roleId to it.name }
        return church.copy(
            address = address,
            jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
            phone = DirectoryCrawlerSupport.phoneFromText(text.replace(telephoneFaxLabel, "TEL")),
            fax = DirectoryCrawlerSupport.faxFromText(text.replace(telephoneFaxLabel, "FAX")),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, sourceHost),
            email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = ministers,
        )
    }

    private fun normalizeName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeLegacyText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(legacyHyphen, "-")

    private fun parseMinisterCell(value: String): List<ChurchMinister> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        twoMinisterCell.matchEntire(normalized)?.let { match ->
            return ChurchMinisterParser.fromRoleAndNames(match.groupValues[3], match.groupValues[1]) +
                ChurchMinisterParser.fromRoleAndNames(match.groupValues[4], match.groupValues[2])
        }
        val compacted = spacedJapaneseMinister.matchEntire(normalized)?.let { match ->
            "${match.groupValues[1].replace(" ", "")} ${match.groupValues[2]}"
        } ?: normalized
        return ChurchMinisterParser.parse(compacted)
    }

    private companion object {
        const val sourceHost = "xn--u9j463geip7pa94cc38by5dpv1d.com"
        val churchNamePattern = Regex("""(?:教会|チャーチ)(?:\s*[（(].*[）)])?$""")
        val telephoneLabel = Regex("""電\s*話\s*[：:]?""")
        val faxLabel = Regex("""F\s*A\s*X\s*[：:]?""", RegexOption.IGNORE_CASE)
        val telephoneFaxLabel = Regex("""TEL\s*/\s*FAX\s*[：:]?""", RegexOption.IGNORE_CASE)
        val legacyHyphen = Regex("""[−ー－‐]""")
        val ministerRolePattern = Regex("""(?:牧師|伝道師|宣教師|教職|長老)""")
        val spacedJapaneseMinister = Regex(
            """^([一-龯](?:\s+[一-龯]){1,12})\s+((?:(?:主任|協力|名誉)\s*)?(?:牧師|伝道師|宣教師|教職|長老))$""",
        )
        val twoMinisterCell = Regex(
            """^(.+?)\s+(.+?)\s+((?:主任|協力|名誉)?牧師)\s+((?:主任|協力|名誉)?牧師)$""",
        )
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}
