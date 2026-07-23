package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.JapaneseAddressNormalizer
import org.jsoup.Jsoup

class JAGDenominationChurchListCrawler : MultiPageDenominationChurchListCrawler {
    override val denominationId: String = "JAG"
    override val denominationName: String = "日本アッセンブリーズ・オブ・ゴッド教団"
    override val sourceUrl: String = "https://j-ag.org/church-info/"
    override val outputFileName: String = "jag-churches.json"
    override val sourceUrls: List<String> = listOf(
        "https://j-ag.org/church-info/hokkaido-kyoku/",
        "https://j-ag.org/church-info/hokkaido-kyoku/page/2/",
        "https://j-ag.org/church-info/tohoku-kyoku/",
        "https://j-ag.org/church-info/tohoku-kyoku/page/2/",
        "https://j-ag.org/church-info/kantohokuto-kyoku/",
        "https://j-ag.org/church-info/kantohokuto-kyoku/page/2/",
        "https://j-ag.org/church-info/kantohokuto-kyoku/page/3/",
        "https://j-ag.org/church-info/kantohokuto-kyoku/page/4/",
        "https://j-ag.org/church-info/kantonansei-kyoku/",
        "https://j-ag.org/church-info/kantonansei-kyoku/page/2/",
        "https://j-ag.org/church-info/kantonansei-kyoku/page/3/",
        "https://j-ag.org/church-info/kantonansei-kyoku/page/4/",
        "https://j-ag.org/church-info/tokai-kyoku/",
        "https://j-ag.org/church-info/tokai-kyoku/page/2/",
        "https://j-ag.org/church-info/hokuriku-kyoku/",
        "https://j-ag.org/church-info/hokuriku-kyoku/page/2/",
        "https://j-ag.org/church-info/kansai-kyoku/",
        "https://j-ag.org/church-info/kansai-kyoku/page/2/",
        "https://j-ag.org/church-info/kansai-kyoku/page/3/",
        "https://j-ag.org/church-info/kansai-kyoku/page/4/",
        "https://j-ag.org/church-info/chugoku-kyoku/",
        "https://j-ag.org/church-info/kyushu-kyoku/",
        "https://j-ag.org/church-info/kyushu-kyoku/page/2/",
        "https://j-ag.org/church-info/okinawa-kyoku/",
    )

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl)
            .select(".church:not(.church-info-kyoku_introduction)")
            .mapNotNull { card ->
                val title = card.selectFirst(".vk_post_title") ?: return@mapNotNull null
                val name = title.text().trim()
                if (name.isBlank()) return@mapNotNull null
                val excerpt = card.selectFirst(".vk_post_excerpt")?.text()?.trim().orEmpty()
                val jurisdiction = card.selectFirst(".vk_post_imgOuter_singleTermLabel")
                    ?.text()
                    ?.substringBefore('|')
                    ?.trim()
                    .orEmpty()
                OfficialDenominationChurch(
                    name = name,
                    address = addressPattern.find(excerpt)?.groupValues?.get(1)?.trim().orEmpty(),
                    jurisdiction = jurisdiction,
                    phone = phonePattern.find(excerpt)?.groupValues?.get(1)?.trim().orEmpty(),
                    fax = faxPattern.find(excerpt)?.groupValues?.get(1)?.trim().orEmpty(),
                    denominationChurchListDetailPage = title.selectFirst("a[href]")?.absUrl("href")?.trim().orEmpty(),
                )
            }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val rows = document.select("tr").associateBy { row ->
            row.select("th, td").firstOrNull()?.text()?.replace(Regex("\\s+"), "")?.trim().orEmpty()
        }
        val address = rows.entries.firstOrNull { (label, _) -> label == "住所" }
            ?.value
            ?.select("th, td")
            ?.getOrNull(1)
            ?.text()
            ?.let(::normalizeAddress)
            .orEmpty()
        val website = rows.entries.firstOrNull { (label, _) -> "ホームページ" in label || "ウェブサイト" in label }
            ?.value
            ?.selectFirst("a[href]")
            ?.absUrl("href")
            ?.trim()
            ?.takeUnless { it.startsWith("https://j-ag.org/") }
            .orEmpty()
        val phone = rows.entries.firstOrNull { (label, _) -> label == "電話" || label == "TEL" }
            ?.value?.select("th, td")?.getOrNull(1)?.text()?.trim().orEmpty()
        val fax = rows.entries.firstOrNull { (label, _) -> label == "FAX" }
            ?.value?.select("th, td")?.getOrNull(1)?.text()?.trim().orEmpty()
        return church.copy(
            address = address.ifBlank { church.address },
            phone = phone.ifBlank { church.phone },
            fax = fax.ifBlank { church.fax },
            websiteUrl = website,
        )
    }

    private fun normalizeAddress(value: String): String {
        val addressOnly = value.split(
            Regex("""\s*(?:[＊※]|徒歩|最寄り?駅|アクセス)"""),
            limit = 2,
        ).first().trim()
        return JapaneseAddressNormalizer.normalize(addressOnly).normalized
    }

    private companion object {
        val addressPattern = Regex(
            "住所\\s*(〒[^…\\[]*?)(?=\\s*(?:電話|TEL|FAX|\\[…]|\\[\\.\\.\\.]|$))",
            RegexOption.IGNORE_CASE,
        )
        val phonePattern = Regex(
            "(?:電話|TEL)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]+)",
            RegexOption.IGNORE_CASE,
        )
        val faxPattern = Regex(
            "FAX\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]+)",
            RegexOption.IGNORE_CASE,
        )
    }
}
