package jp.co.crossmap.crawl.denomination

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
                    websiteUrl = title.selectFirst("a[href]")?.absUrl("href")?.trim().orEmpty(),
                )
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
