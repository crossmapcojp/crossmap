package jp.co.crossmap.crawl.denomination

import java.text.Normalizer
import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class LIVEDenominationChurchListCrawler(
    override val sourceUrls: List<String>,
) : MultiPageDenominationChurchListCrawler {
    override val denominationId = "LIVE"
    override val denominationName = "ライブチャーチ"
    override val sourceUrl = sourceUrls.first()
    override val outputFileName = "live-churches.json"
    override fun parse(html: String) = parsePage(sourceUrl, html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val language = url.substringAfter("/location/").substringBefore('/').lowercase()
        return Jsoup.parse(html, url).select("h1").mapNotNull { heading ->
            val name = heading.text().trim()
            if (name.isBlank() || name.contains("全国")) return@mapNotNull null
            val section = heading.parents().firstOrNull { it.hasClass("x-col") } ?: heading.parent() ?: heading
            val text = section.text()
            val links = section.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                localizedNames = listOf(LocalizedName(language, name)),
                address = DirectoryCrawlerSupport.addressFromText(text),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                websiteUrl = links.firstOrNull { it.absUrl("href").contains("livechurch.jp/") }?.absUrl("href").orEmpty(),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text.replace("Senior Pastor", "主任牧師").replace("Pastor", "牧師")),
                note = "sourceLanguage=$language",
            )
        }
    }

    override fun merge(churches: List<OfficialDenominationChurch>): List<OfficialDenominationChurch> = churches
        .groupBy { postalCode(it.address).ifBlank { it.phone } }
        .values.map { variants ->
            val canonical = variants.firstOrNull { it.note == "sourceLanguage=ja" } ?: variants.first()
            canonical.copy(
                localizedNames = variants.flatMap(OfficialDenominationChurch::localizedNames).distinctBy { it.languageCode },
                note = "",
            )
        }

    private fun postalCode(address: String): String = Regex("\\d{3}\\s*-?\\s*\\d{4}")
        .find(Normalizer.normalize(address, Normalizer.Form.NFKC))?.value.orEmpty().replace(Regex("[\\s-]"), "")
}
