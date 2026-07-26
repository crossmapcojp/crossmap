package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.LocalizedName
import org.jsoup.Jsoup

class CCMJDenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "CCMJ"
    override val denominationName = "カルバリーチャペルミニストリー JAPAN"
    override val outputFileName = "ccmj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = Jsoup.parse(html, sourceUrl)
        .select("div.paragraph")
        .flatMap { paragraph -> paragraph.html().split(recordSeparator) }
        .mapNotNull { fragment -> parseRecord(fragment) }
        .distinctBy(OfficialDenominationChurch::name)

    private fun parseRecord(fragment: String): OfficialDenominationChurch? {
        val document = Jsoup.parseBodyFragment(fragment, sourceUrl)
        val text = document.text().replace(zeroWidth, "").replace(whitespace, " ").trim()
        if (!text.contains("Pastor ") || !text.contains("牧師")) return null
        val prePastor = text.substringBefore("Pastor ").trim()
        val jurisdictionMatch = jurisdictionMarkerPattern.findAll(prePastor).lastOrNull() ?: return null
        val prefix = prePastor.substring(0, jurisdictionMatch.range.first).trim()
        val englishStart = englishNameStart(prefix)
        if (englishStart <= 0) return null
        val name = prefix.substring(0, englishStart).trim()
        val englishName = prefix.substring(englishStart).trim()
        val location = prePastor.substring(jurisdictionMatch.range.first).trim()
            .replace("千葉県船橋村", "千葉県船橋市")
        val pastorSection = text.substringAfter("Pastor ").substringBefore("牧師")
        val japaneseStart = pastorSection.indexOfFirst(::isJapaneseCharacter)
        val pastor = pastorSection.substring(japaneseStart.coerceAtLeast(0))
            .replace(readingPattern, "")
            .trim()
        val links = document.select("a[href]")
        return OfficialDenominationChurch(
            name = name,
            address = location,
            jurisdiction = jurisdiction(location),
            websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.calvaryjapan.com"),
            socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
            ministers = pastor.takeIf(String::isNotBlank)
                ?.let { ChurchMinisterParser.fromRoleAndNames("牧師", it) }
                .orEmpty(),
            localizedNames = englishName.takeIf(String::isNotBlank)
                ?.let { listOf(LocalizedName("en", it)) }
                .orEmpty(),
        )
    }

    private fun englishNameStart(value: String): Int {
        var sawJapanese = false
        value.forEachIndexed { index, character ->
            if (isJapaneseCharacter(character)) sawJapanese = true
            if (sawJapanese && character in 'A'..'Z' && index > 0 && value[index - 1].isWhitespace()) return index
        }
        return -1
    }

    private fun jurisdiction(location: String): String = when {
        location.startsWith("大阪市") -> "大阪府"
        location.startsWith("鹿児島市") -> "鹿児島県"
        else -> prefecturePattern.find(location)?.value.orEmpty()
    }

    private fun isJapaneseCharacter(character: Char): Boolean = when (Character.UnicodeScript.of(character.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        -> true
        else -> false
    }

    private companion object {
        val recordSeparator = Regex("""(?:\s*<br>\s*){2,}""", RegexOption.IGNORE_CASE)
        val zeroWidth = Regex("""[\u200B-\u200D\uFEFF]""")
        val whitespace = Regex("""\s+""")
        val jurisdictionMarkerPattern = Regex("""北海道|東京都|京都府|大阪府|大阪市|[一-龯]{2,3}県|鹿児島市""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val readingPattern = Regex("""\s*[（(][^）)]*[）)]""")
    }
}
