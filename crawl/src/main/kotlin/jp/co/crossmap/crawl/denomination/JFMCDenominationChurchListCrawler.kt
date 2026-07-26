package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JFMCDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JFMC"
    override val denominationName = "日本自由メソヂスト教団"
    override val outputFileName = "jfmc-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("ul#menu-link > li > a[href]").mapNotNull { link ->
            val name = link.text().trim()
            if (!name.endsWith("教会")) return@mapNotNull null
            OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = link.absUrl("href"),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val rows = Jsoup.parse(html, church.denominationChurchListDetailPage)
            .select("figure.wp-block-table table tr")
        val values = rows.mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            val label = cells.firstOrNull()?.text()?.trim().orEmpty()
            val value = cells.getOrNull(1)?.text()?.trim().orEmpty()
            label.takeIf(String::isNotBlank)?.let { it to value }
        }.toMap()
        val address = DirectoryCrawlerSupport.normalizeAddress(
            values["現住所"].orEmpty().replace(postalCodeWithoutSpace, "$1 "),
        )
        val phone = contactNumberPattern.find(values["電話番号"].orEmpty())?.value.orEmpty()
        val faxText = values["ファックス番号"].orEmpty()
        val fax = when {
            faxText == "同上" -> phone
            faxText == "なし" -> ""
            else -> contactNumberPattern.find(faxText)?.value.orEmpty()
        }
        val ministers = rows.flatMap { row ->
            val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            val role = cells.firstOrNull()?.text()?.trim().orEmpty()
            if (!ministerRolePattern.containsMatchIn(role)) return@flatMap emptyList()
            val names = cells.getOrNull(1)?.html()
                ?.replace(lineBreak, "、")
                ?.let(Jsoup::parseBodyFragment)
                ?.text()
                .orEmpty()
            names.split('、').flatMap { rawName ->
                val name = rawName.replace('　', ' ').trim()
                val suffix = ministerRoleSuffix.matchEntire(name)
                if (suffix == null) {
                    ChurchMinisterParser.fromRoleAndNames(role, name)
                } else {
                    ChurchMinisterParser.fromRoleAndNames(
                        suffix.groupValues[2],
                        suffix.groupValues[1],
                    )
                }
            }
        }.distinctBy { it.roleId to it.name }
        return church.copy(
            name = values["正式名称"].orEmpty().ifBlank { church.name },
            address = address.ifBlank { church.address },
            jurisdiction = prefecturePattern.find(address.ifBlank { church.address })?.value.orEmpty(),
            phone = phone.ifBlank { church.phone },
            fax = fax.ifBlank { church.fax },
            ministers = ministers.ifEmpty { church.ministers },
        )
    }

    private companion object {
        val ministerRolePattern = Regex("""(?:牧師|伝道師|宣教師|教職|長老)""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val postalCodeWithoutSpace = Regex("""^(〒\d{3}-\d{4})(?=\S)""")
        val contactNumberPattern = Regex("""(?<!\d)0\d{1,4}-\d{1,4}-\d{3,4}(?!\d)""")
        val lineBreak = Regex("""(?i)<br\s*/?>""")
        val ministerRoleSuffix = Regex("""^(.+?)\s+((?:主任|副|協力)?牧師)$""")
    }
}
