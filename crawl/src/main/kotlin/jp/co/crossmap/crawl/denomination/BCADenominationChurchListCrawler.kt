package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class BCADenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "BCA"
    override val denominationName = "ベタニヤ・クリスチャン・アッセンブリーズ"
    override val outputFileName = "bca-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        return document.select("h6").mapNotNull { heading ->
            val name = heading.text().replace(Regex("\\s+"), "").trim()
            if (!name.endsWith("教会") && !name.endsWith("チャペル")) return@mapNotNull null
            val section = listOf(heading) + siblingsUntilNextChurch(heading)
            val text = section.joinToString(" ") { it.text() }
            val contactText = text.replace("℡", "TEL")
            val address = normalizeAddress(contactText)
            val phone = phoneNumber.find(text.substringAfter("℡", text))
                ?.value
                ?.let(::normalizeContactNumber)
                .orEmpty()
            val fax = explicitFax.find(text)?.groupValues?.get(1)
                ?.let(::normalizeContactNumber)
                ?: phone.takeIf { text.contains("℡&FAX", ignoreCase = true) }.orEmpty()
            val links = section.flatMap { it.select("a[href]") }
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = phone,
                fax = fax,
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "church.ne.jp"),
                email = DirectoryCrawlerSupport.extractEmail(text, links.map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text.replace("先生", "")),
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private fun normalizeAddress(text: String): String {
        val corrected = text.toAsciiDigits()
            .replace('－', '-')
            .replace("〒407-0355", "〒470-0355")
        val address = DirectoryCrawlerSupport.addressFromText(corrected)
        return address.replace(
            Regex("""^(〒\d{3}-\d{4}\s+)(?=(?:日進市|犬山市|豊田市))"""),
            "$1愛知県",
        ).replace(Regex("""(都|道|府|県)\s+"""), "$1")
    }

    private fun siblingsUntilNextChurch(heading: Element): List<Element> {
        val result = mutableListOf<Element>()
        var sibling = heading.nextElementSibling()
        while (sibling != null && sibling.tagName() != "h6") {
            result.add(sibling)
            sibling = sibling.nextElementSibling()
        }
        return result
    }

    private fun normalizeContactNumber(value: String): String {
        val normalized = value.toAsciiDigits().replace('（', '(').replace('）', ')')
        val match = phoneNumber.find(normalized) ?: return ""
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }

    private fun String.toAsciiDigits(): String = buildString(length) {
        this@toAsciiDigits.forEach { character ->
            append(
                if (character in '０'..'９') {
                    (character.code - 0xFEE0).toChar()
                } else {
                    character
                },
            )
        }
    }

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val phoneNumber = Regex("""(0[0-9０-９]{1,4})[（(]([0-9０-９]{1,4})[）)]([0-9０-９]{3,4})""")
        val explicitFax = Regex(
            """FAX\s*((?:0[0-9０-９]{1,4})[（(](?:[0-9０-９]{1,4})[）)](?:[0-9０-９]{3,4}))""",
            RegexOption.IGNORE_CASE,
        )
    }
}
