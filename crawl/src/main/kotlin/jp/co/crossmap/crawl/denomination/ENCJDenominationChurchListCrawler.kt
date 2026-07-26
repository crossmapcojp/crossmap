package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class ENCJDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "ENCJ"
    override val denominationName = "エブリネイションチャーチズジャパン"
    override val outputFileName = "encj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("div.et_pb_column:has(h4)").mapNotNull { card ->
            val name = card.selectFirst("h4")?.text()?.trim().orEmpty()
            if (!churchNamePattern.containsMatchIn(name)) return@mapNotNull null
            val text = card.text()
            val details = text.removePrefix(name).trim()
            val address = DirectoryCrawlerSupport.addressFromText(details).let { value ->
                if (name == "エブリネイション 静岡" && " 静岡市" in value) {
                    value.replace(" 静岡市", " 静岡県静岡市")
                } else {
                    value
                }
            }
            val phone = phonePattern.find(details)?.groupValues?.get(1)?.let(::normalizePhone).orEmpty()
            val links = card.select("a[href]")
            val ministers = ministerPattern.findAll(details.substringBefore("〒")).flatMap { match ->
                val role = if (match.groupValues[2].startsWith("主任")) "主任牧師" else "牧師"
                ChurchMinisterParser.fromRoleAndNames(
                    role,
                    expandPastoralCouple(match.groupValues[1]),
                ).asSequence()
            }.toList()
            val website = links.firstOrNull { link ->
                val href = link.absUrl("href")
                href.startsWith("http") && DirectoryCrawlerSupport.socialProfiles(listOf(link)).isEmpty()
            }?.absUrl("href").orEmpty()
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                fax = phone.takeIf { telFaxPattern.containsMatchIn(details) }.orEmpty(),
                email = DirectoryCrawlerSupport.extractEmail(details, links.map { it.attr("href") }),
                websiteUrl = website,
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministers.distinctBy { it.roleId to it.name },
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private fun expandPastoralCouple(value: String): String {
        val names = value.trim().replace('＆', '&').split('&', limit = 2)
        if (names.size != 2) return names.single()
        val first = names[0].trim()
        val spouse = names[1].trim()
        val separator = when {
            '・' in first -> "・"
            ' ' in first -> " "
            else -> return "$first、$spouse"
        }
        return "$first、${first.substringBefore(separator)}$separator$spouse"
    }

    private fun normalizePhone(value: String): String = value.map { character ->
        when {
            character in '０'..'９' -> (character.code - 0xFEE0).toChar()
            character in "ー－‐" -> '-'
            else -> character
        }
    }.joinToString("")

    private companion object {
        val churchNamePattern = Regex("""(?:チャーチ|エブリネイション)""")
        val ministerPattern = Regex("""(.+?)\s*((?:主任)?牧師(?:夫妻)?)""")
        val phonePattern = Regex(
            """Tel(?:\s*/\s*Fax)?\s*[:：]\s*([0-9０-９+ー－‐-]{8,})""",
            RegexOption.IGNORE_CASE,
        )
        val telFaxPattern = Regex("""Tel\s*/\s*Fax""", RegexOption.IGNORE_CASE)
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
    }
}
