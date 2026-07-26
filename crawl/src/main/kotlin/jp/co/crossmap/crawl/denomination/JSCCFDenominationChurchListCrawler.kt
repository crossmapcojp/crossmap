package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JSCCFDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JSCCF"
    override val denominationName = "日本聖泉基督教会連合"
    override val outputFileName = "jsccf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.selectFirst(".post-body")?.text() ?: document.body().text()
        val matches = churchStart.findAll(text).toList()
        return matches.mapIndexed { index, match ->
            val block = text.substring(match.range.first, matches.getOrNull(index + 1)?.range?.first ?: text.length)
            val name = match.groupValues[1]
            val address = addressPattern.find(block)?.groupValues?.get(1).orEmpty()
                .substringBefore("※")
                .let(DirectoryCrawlerSupport::normalizeAddress)
            val phone = phonePattern.find(block)?.groupValues?.get(1).orEmpty()
            val fax = when {
                faxSharedPattern.containsMatchIn(block) -> phone
                else -> faxPattern.find(block)?.groupValues?.get(1).orEmpty()
            }
            val emailText = block.replace(atMarkPattern, "@")
            val website = websitePattern.find(block)?.value.orEmpty()
            val ministers = ministerPattern.find(block)?.groupValues?.get(1)
                ?.split("・")
                ?.flatMap { value ->
                    ChurchMinisterParser.fromRoleAndNames("教職", value.replace(yearPattern, "").trim())
                }
                .orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                fax = fax,
                email = DirectoryCrawlerSupport.extractEmail(emailText),
                websiteUrl = website,
                ministers = ministers.distinctBy { it.roleId to it.name },
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val churchStart = Regex(
            """([一-龯ぁ-んァ-ヶー＆・]+(?:キリスト教会|チャーチ|ワーシップ)(?:（[^）]+）)?)\s*①""",
        )
        val addressPattern = Regex("""②\s*(〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}\s*.+?)\s*③""")
        val phonePattern = Regex("""③\s*(?:Tel\s*[:：]\s*)?([0-9０-９+ー－‐-]{8,})""", RegexOption.IGNORE_CASE)
        val faxPattern = Regex("""fax\s*[:：]?\s*([0-9０-９+ー－‐-]{8,})""", RegexOption.IGNORE_CASE)
        val faxSharedPattern = Regex("""[（(]\s*Fax共\s*[）)]""", RegexOption.IGNORE_CASE)
        val atMarkPattern = Regex("""[（(]\s*アット[・･]?マーク\s*[）)]""")
        val websitePattern = Regex("""https?://[^\s　]+""")
        val ministerPattern = Regex("""⑤\s*(.+?)(?=$)""")
        val yearPattern = Regex("""[（(]\d{4}[）)]""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}
