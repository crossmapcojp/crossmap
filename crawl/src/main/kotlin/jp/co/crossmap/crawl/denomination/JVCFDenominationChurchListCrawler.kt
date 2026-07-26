package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class JVCFDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JVCF"
    override val denominationName = "日本ヴィンヤード・クリスチャン・フェロシップ"
    override val outputFileName = "jvcf-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, sourceUrl)
        val text = document.body().text()
        val kaniWebsite = document.select("a[href]")
            .map { it.absUrl("href") }
            .firstOrNull { it.contains("fvc-kani.jp") }
            .orEmpty()
        return members.mapNotNull { member ->
            if (!text.contains(member.sourceName)) return@mapNotNull null
            OfficialDenominationChurch(
                name = member.sourceName,
                jurisdiction = member.jurisdiction,
                websiteUrl = if (member.detailOnKaniSite) kaniWebsite else "",
                denominationChurchListDetailPage = if (member.detailOnKaniSite) kaniWebsite else "",
            )
        }
    }

    override fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch {
        val text = Jsoup.parse(html, church.denominationChurchListDetailPage).body().text()
        val details = when (church.name) {
            "可児福音教会" -> Detail(
                name = church.name,
                address = address(text, """〒509-0207\s+岐阜県可児市今渡1732-1"""),
                phone = contact(text, """Tel:\s*(0574-62-6272)"""),
                fax = contact(text, """FAX\s*(0574-63-6304)"""),
                ministers = minister(text, "主任牧師", """主任牧師\s*([一-龯]{2,8})"""),
            )
            "扶桑ゴスペルセンター" -> Detail(
                name = "扶桑ゴスペルチャーチ",
                address = address(text, """愛知県丹羽郡扶桑町大字斎藤字北屋敷143"""),
                phone = contact(text, """TEL\s*(058-92-9189)"""),
                ministers = minister(text, "牧師", """牧師\s*([一-龯]{2,8})""", after = "扶桑ゴスペルチャーチ"),
            )
            "多治見ビンヤード" -> Detail(
                name = "多治見ビンヤードチャーチ",
                address = address(text, """岐阜県多治見市池田町1-17"""),
                phone = contact(text, """TEL\s*(0572-26-9898)"""),
                ministers = minister(text, "牧師", """牧師\s*([一-龯]{2,8})""", after = "多治見ビンヤードチャーチ"),
            )
            else -> return church
        }
        return church.copy(
            name = details.name,
            address = details.address,
            jurisdiction = prefecturePattern.find(details.address)?.value ?: church.jurisdiction,
            phone = details.phone,
            fax = details.fax,
            ministers = details.ministers,
        )
    }

    private fun address(text: String, pattern: String): String =
        Regex(pattern).find(text)?.value?.let(DirectoryCrawlerSupport::normalizeAddress).orEmpty()

    private fun contact(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1).orEmpty()

    private fun minister(
        text: String,
        role: String,
        pattern: String,
        after: String = "",
    ) = Regex(pattern).find(text.substringAfter(after, text))?.groupValues?.get(1)
        ?.let { ChurchMinisterParser.fromRoleAndNames(role, it) }
        .orEmpty()

    private data class Member(
        val sourceName: String,
        val jurisdiction: String,
        val detailOnKaniSite: Boolean = false,
    )

    private data class Detail(
        val name: String,
        val address: String,
        val phone: String,
        val fax: String = "",
        val ministers: List<jp.co.crossmap.ChurchMinister> = emptyList(),
    )

    private companion object {
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val members = listOf(
            Member("可児福音教会", "岐阜県", detailOnKaniSite = true),
            Member("扶桑ゴスペルセンター", "岐阜県", detailOnKaniSite = true),
            Member("多治見ビンヤード", "岐阜県", detailOnKaniSite = true),
            Member("VCF所沢", "埼玉県"),
            Member("VCF矢板", "栃木県"),
            Member("パールヴィンヤード", "神奈川県"),
        )
    }
}
