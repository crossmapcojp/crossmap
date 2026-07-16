package jp.co.crossmap

/** Selects the language-specific index/analyzer from the query, independently of the UI display language. */
object QueryLanguageDetector {
    private val portugueseWords = setOf(
        "assembleia", "brasil", "cristo", "deus", "distrito", "evangelica", "evangélica", "igreja", "missao", "missão",
    )
    private val indonesianWords = setOf(
        "allah", "distrik", "gereja", "indonesia", "injil", "jemaat", "kristen", "misi", "yesus",
    )
    private val englishWords = setOf(
        "assembly", "baptist", "chapel", "christ", "christian", "church", "fellowship", "gospel", "mission",
    )

    fun detect(query: String, preferredLanguage: String = "ja"): String {
        val preferred = preferredLanguage.substringBefore('-').lowercase()
        if (query.any(::isHangul)) return "ko"
        if (query.any(::isJapanese)) return "ja"

        val normalized = query.lowercase()
        val words = normalized.split(Regex("[^\\p{L}]+"))
            .filter(String::isNotBlank)
            .toSet()
        if (normalized.any { it in "ãõáàâéêíóôúç" } || words.any { it in portugueseWords }) return "pt"
        if (words.any { it in indonesianWords }) return "id"
        if (words.any { it in englishWords }) return "en"
        return preferred.takeIf { it in LATIN_LANGUAGES } ?: "en"
    }

    private fun isHangul(character: Char): Boolean =
        character in '\u1100'..'\u11FF' || character in '\u3130'..'\u318F' || character in '\uAC00'..'\uD7AF'

    private fun isJapanese(character: Char): Boolean =
        character in '\u3040'..'\u30FF' || character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF'

    private val LATIN_LANGUAGES = setOf("en", "pt", "id")
}
