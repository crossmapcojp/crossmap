package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class NLCCUDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "NLCCU"
    override val denominationName = "新生キリスト教会連合"
    override val outputFileName = "nlccu-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val machida = OfficialDenominationChurch(
            name = "町田クリスチャンセンター",
            websiteUrl = siteRoot,
            denominationChurchListDetailPage = "$siteRoot/access",
        )
        val members = document.select("div#MtrxGllry0-u32 .wixui-gallery__item").mapNotNull { item ->
            val name = item.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            val website = item.selectFirst("a[href]")?.absUrl("href").orEmpty()
            if (name.isBlank() || website.isBlank()) return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                websiteUrl = website,
                denominationChurchListDetailPage = when {
                    website.contains("hopechurch.holy.jp") -> "https://hopechurch.holy.jp/access/"
                    else -> website
                },
            )
        }
        return (listOf(machida) + members).distinctBy(OfficialDenominationChurch::name)
    }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val document = Jsoup.parse(html, church.denominationChurchListDetailPage)
        val detail = document.body()
        val text = detail.text()
        val address = parseAddress(church.name, text).let { value ->
            if (value.contains("相模原市") && !value.contains("神奈川県")) {
                value.replace(Regex("""^(〒\d{3}-\d{4}\s+)(?=相模原市)"""), "$1神奈川県")
            } else {
                value
            }
        }
        val phone = labeledPhone.find(text)?.groupValues?.get(1)?.let(::normalizeContact).orEmpty()
        val fax = labeledFax.find(text)?.groupValues?.get(1)?.let(::normalizeContact)
            ?: phone.takeIf { telFax.containsMatchIn(text) }.orEmpty()
        val emailText = text.replace('☆', '@')
        val ministerText = text.replace('　', ' ')
        val ministers = ChurchMinisterParser.parse(ministerText).ifEmpty {
            labeledMinister.find(ministerText)?.groupValues?.get(1)
                ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                .orEmpty()
        }
        return church.copy(
            address = address.ifBlank { church.address },
            jurisdiction = prefecturePattern.find(address.ifBlank { church.address })?.value.orEmpty(),
            phone = phone.ifBlank { church.phone },
            fax = fax.ifBlank { church.fax },
            email = DirectoryCrawlerSupport.extractEmail(emailText, detail.select("a[href]").map { it.attr("href") })
                .ifBlank { church.email },
            ministers = ministers.ifEmpty { church.ministers },
        )
    }

    private fun parseAddress(churchName: String, text: String): String {
        if (churchName == "相模原ホープチャーチ") {
            return DirectoryCrawlerSupport.normalizeAddress(
                "〒252-0336 神奈川県相模原市南区当麻888-16",
            )
        }
        val known = knownAddressPatterns[churchName]?.find(text)?.value
        val value = known
            ?: postalAddress.find(text)?.value
            ?: englishLabeledAddress.find(text)?.groupValues?.get(1)
            ?: return ""
        return DirectoryCrawlerSupport.normalizeAddress(
            value.replace(Regex("""^〒\s*([0-9０-９]{3})[-ー－‐]([0-9０-９]{4})\.?\s*""")) { match ->
                "〒${match.groupValues[1].toAsciiDigits()}-${match.groupValues[2].toAsciiDigits()} "
            },
        ).replace(Regex("""(都|道|府|県)\s+"""), "$1")
    }

    private fun normalizeContact(value: String): String = value.toAsciiDigits()
        .replace('－', '-')
        .replace('ー', '-')
        .replace('‐', '-')
        .replace('（', '(')
        .replace('）', ')')

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
        const val siteRoot = "https://mccjapa8.wixsite.com/mccjapan"
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val postalAddress = Regex(
            """〒\s*[0-9０-９]{3}[-ー－‐][0-9０-９]{4}\.?\s+""" +
                """(?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)?\s*""" +
                """[一-龯ぁ-んァ-ヶ々A-Za-z0-9０-９&ヶ－−‐ー\-\s]+?""" +
                """(?=\s+(?:TEL|Tel|Phone|Fax|FAX|Email|E-mail|国道|Copyright|$))""",
            RegexOption.IGNORE_CASE,
        )
        val englishLabeledAddress = Regex(
            """Address\s+((?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県)[一-龯ぁ-んァ-ヶ々0-9０-９－−‐ー\-]+)""" +
                """(?=\s+Phone)""",
            RegexOption.IGNORE_CASE,
        )
        val labeledPhone = Regex(
            """(?:TEL(?:/FAX)?|Tel|Phone)\s*[:：]?\s*([+＋]?[0-9０-９()（）\-ー－‐]+)""",
            RegexOption.IGNORE_CASE,
        )
        val labeledFax = Regex(
            """FAX\s*[:：]?\s*([+＋]?[0-9０-９()（）\-ー－‐]+)""",
            RegexOption.IGNORE_CASE,
        )
        val telFax = Regex("""TEL/FAX""", RegexOption.IGNORE_CASE)
        val labeledMinister = Regex("""牧師\s*[：:]?\s*([一-龯]{2,8}(?:\s+[一-龯]{1,4})?)""")
        val knownAddressPatterns = mapOf(
            "町田クリスチャンセンター" to Regex(
                """〒194-0021\.\s*東京都町田市中町1-15-11\s+U&Eビル\s+B1""",
            ),
            "相模原ホープチャーチ" to Regex("""〒252-0336\s+相模原市南区当麻888-16"""),
            "只見キリスト教会" to Regex("""〒968-0421\s+福島県南会津郡只見町只見字寺456-1"""),
        )
    }
}
