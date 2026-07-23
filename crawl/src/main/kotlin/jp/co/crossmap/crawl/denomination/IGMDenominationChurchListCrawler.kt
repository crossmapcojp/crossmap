package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class IGMDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "IGM"
    override val denominationName: String = "イムマヌエル綜合伝道団"
    override val sourceUrl: String = "https://www.immanuel.or.jp/link.html"
    override val outputFileName: String = "igm-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val doc = Jsoup.parse(html, sourceUrl)
        val churches = mutableListOf<OfficialDenominationChurch>()
        doc.select("table").forEach { table ->
            table.select("tr").forEach { row ->
                val th = row.selectFirst("th") ?: return@forEach
                val td = row.selectFirst("td") ?: return@forEach
                val nameLink = th.selectFirst("a")
                val name = nameLink?.text()?.trim().orEmpty()
                if (name.isBlank()) return@forEach
                val websiteUrl = nameLink?.attr("href")?.trim().orEmpty()
                    .let { url ->
                        when {
                            url.isBlank() -> ""
                            url.startsWith("http://") || url.startsWith("https://") -> url
                            else -> "https://$url"
                        }
                    }
                val tdText = td.clone().apply { select("rt, rp").remove() }.text()
                val address = addressPattern.find(tdText)?.value?.trim().orEmpty()
                val phone = phonePattern.find(tdText)?.groupValues?.get(1)?.trim().orEmpty()
                val fax = faxPattern.find(tdText)?.groupValues?.get(1)?.trim().orEmpty()
                churches += OfficialDenominationChurch(
                    name = name,
                    address = address,
                    phone = phone,
                    fax = fax,
                    websiteUrl = websiteUrl,
                    ministers = ChurchMinisterParser.parse(tdText),
                )
            }
        }
        return churches
    }

    private companion object {
        val addressPattern = Regex("〒[0-9０-９\\-ー－]+\\s*[^T]+")
        val phonePattern = Regex("TEL\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]+)")
        val faxPattern = Regex("FAX\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]+)")
    }
}
