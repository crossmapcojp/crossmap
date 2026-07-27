package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class NihonKiristoKaiDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCA"
    override val denominationName = "日本基督会"
    override val outputFileName = "jca-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val cells = document.select("table td")
        if (cells.isEmpty()) {
            val text = document.body().text()
            val address = DirectoryCrawlerSupport.addressFromText(text)
            val phone = DirectoryCrawlerSupport.phoneFromText(text)
            val email = DirectoryCrawlerSupport.extractEmail(text, emptyList())
            return listOf(
                OfficialDenominationChurch(
                    name = "日本基督会",
                    address = address,
                    jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                    phone = phone,
                    email = email,
                )
            )
        }
        return cells.mapNotNull { td ->
            val bold = td.selectFirst("b") ?: return@mapNotNull null
            val name = bold.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val cellText = td.text()
            val address = DirectoryCrawlerSupport.addressFromText(cellText)
            val phone = DirectoryCrawlerSupport.phoneFromText(cellText)
            val link = td.selectFirst("a[href]")
            val website = link?.absUrl("href") ?: ""
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                websiteUrl = website,
            )
        }.filter { it.name.isNotBlank() }
            .distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}