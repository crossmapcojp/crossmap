package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class JEBDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JEB"
    override val denominationName = "日本伝道隊"
    override val outputFileName = "jeb-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("h2").mapNotNull { heading ->
            val name = heading.text().trim()
            if (!looksLikeChurchName(name)) return@mapNotNull null
            val section = Jsoup.parseBodyFragment(
                heading.nextElementSiblings().takeWhile { it.tagName() != "h2" }.joinToString("\n") { it.outerHtml() },
                sourceUrl,
            )
            val text = section.text()
            val address = addOmittedPrefecture(DirectoryCrawlerSupport.addressFromText(text))
            val people = section.select("p").map { it.text().trim() }
                .filter(::looksLikePeopleLine).flatMap(::expandSpouseNames)
            val ministers = ChurchMinisterParser.fromRoleAndNames("牧師", people.joinToString("、")).map { minister ->
                koreanNames[minister.name.replace(Regex("[\\s　]+"), "")]?.let { korean ->
                    minister.copy(localizedNames = listOf(LocalizedName("ko", korean)))
                } ?: minister
            }
            val links = section.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                fax = DirectoryCrawlerSupport.faxFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "nihon-dendoutai.kyoukai.jp"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ministers,
            )
        }

    private fun looksLikePeopleLine(value: String): Boolean = value.isNotBlank() &&
        !Regex("〒|TEL|FAX|https?://|ゴスペル喫茶|理事長|本部事務", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
        value.length <= 80

    private fun expandSpouseNames(value: String): List<String> {
        val names = value.split(Regex("\\s*[、,]\\s*")).map(String::trim).filter(String::isNotBlank)
        val firstSurname = names.firstOrNull()?.split(Regex("[\\s　]+"))?.takeIf { it.size >= 2 }?.firstOrNull().orEmpty()
        return names.map { name ->
            if (firstSurname.isNotBlank() && !name.contains(Regex("[\\s　]")) && name.length <= 5) "$firstSurname $name" else name
        }
    }

    private fun addOmittedPrefecture(address: String): String = when {
        prefecturePattern.containsMatchIn(address) -> address
        address.contains("神戸市") -> address.replace(Regex("^(〒?\\d{3}-\\d{4}\\s*)"), "$1兵庫県")
        address.contains("和歌山市") -> address.replace(Regex("^(〒?\\d{3}-\\d{4}\\s*)"), "$1和歌山県")
        else -> address
    }

    private fun looksLikeChurchName(name: String) =
        listOf("教会", "チャペル", "チャーチ", "伝道館", "修祷園", "満羊園", "エフタの家").any(name::contains)

    private companion object {
        val prefecturePattern = Regex("(?:北海道|東京都|京都府|大阪府|兵庫県|和歌山県|香川県|徳島県|愛媛県)")
        val koreanNames = mapOf(
            "朴鐘皖" to "박종완", "孫理紹" to "손리소", "河東奇" to "하동기", "朴恩景" to "박은경",
            "金ビョンゼ" to "김병제", "姜羨義" to "강선의", "孫亮" to "손량", "劉帝彬" to "유제빈",
            "兪麗雲" to "유려운",
        )
    }
}
