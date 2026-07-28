package jp.co.crossmap.crawl

import java.text.Normalizer

/** Deterministic comparison of a romanized Japanese address with its Japanese-script equivalent. */
object EnglishJapaneseAddressMatcher {
    const val MATCH_THRESHOLD = 0.90

    fun compareEnglishJapaneseAddress(english: String, japanese: String): Boolean =
        similarity(english, japanese) >= MATCH_THRESHOLD

    fun similarity(english: String, japanese: String): Double {
        if (english.isBlank() || japanese.isBlank()) return 0.0
        val englishPostalCode = postalCode(english)
        val japanesePostalCode = postalCode(japanese)
        if (englishPostalCode != null && japanesePostalCode != null && englishPostalCode != japanesePostalCode) return 0.0

        var score = 0.0
        if (englishPostalCode != null && englishPostalCode == japanesePostalCode) score += 0.60

        val englishNumbers = streetNumbers(english)
        val japaneseNumbers = streetNumbers(japanese)
        if (englishNumbers.isNotEmpty() && englishNumbers == japaneseNumbers) score += 0.32

        val prefecture = prefectures.entries.firstOrNull { (japaneseName, englishNames) ->
            japaneseName in japanese && englishNames.any { englishName -> english.containsWord(englishName) }
        }
        if (prefecture != null) score += 0.08

        return score.coerceAtMost(1.0)
    }

    private fun postalCode(value: String): String? = postalCodePattern.find(normalize(value))
        ?.groupValues?.get(1)
        ?.replace("-", "")

    private fun streetNumbers(value: String): List<String> {
        val normalized = normalize(value).replace(postalCodePattern, "")
        return numberPattern.findAll(normalized)
            .map(MatchResult::value)
            .map { it.trimStart('0').ifBlank { "0" } }
            .toList()
    }

    private fun String.containsWord(value: String): Boolean =
        Regex("""(?:^|[^a-z])${Regex.escape(value.lowercase())}(?:$|[^a-z])""").containsMatchIn(lowercase())

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace('−', '-')
        .replace('ー', '-')

    private val postalCodePattern = Regex("""(?:〒\s*)?(\d{3}-?\d{4})""")
    private val numberPattern = Regex("""\d+""")
    private val prefectures = mapOf(
        "東京都" to setOf("tokyo"),
        "埼玉県" to setOf("saitama"),
        "千葉県" to setOf("chiba"),
        "新潟県" to setOf("niigata"),
        "福井県" to setOf("fukui"),
        "静岡県" to setOf("shizuoka"),
        "愛知県" to setOf("aichi"),
        "群馬県" to setOf("gunma"),
    )
}
