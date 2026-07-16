package jp.co.crossmap

enum class Language(val code: String, val displayName: String) {
    JAPANESE("ja", "日本語"),
    ENGLISH("en", "English"),
    KOREAN("ko", "한국어"),
    PORTUGUESE("pt", "Português"),
    INDONESIAN("id", "Bahasa Indonesia"),
    ;

    companion object {
        fun fromCode(code: String?): Language? {
            val normalized = code?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return entries.firstOrNull { it.code == normalized }
        }

        fun fromCodeOrEnglish(code: String?): Language = fromCode(code) ?: ENGLISH
    }
}

val supportedLanguages: List<Language> = Language.entries
val supportedLanguageCodes: List<String> = supportedLanguages.map(Language::code)

class LocalizedText private constructor(
    private val values: Map<Language, String>,
) {
    init {
        require(values.keys == supportedLanguages.toSet()) {
            "Translations must cover exactly: ${supportedLanguageCodes.joinToString()}"
        }
        require(values.values.none(String::isBlank)) { "Translations must not be blank" }
    }

    operator fun get(language: Language): String = values.getValue(language)

    fun asMap(): Map<Language, String> = values.toMap()

    companion object {
        fun of(
            japanese: String,
            english: String,
            korean: String,
            portuguese: String,
            indonesian: String,
        ): LocalizedText = LocalizedText(
            mapOf(
                Language.JAPANESE to japanese,
                Language.ENGLISH to english,
                Language.KOREAN to korean,
                Language.PORTUGUESE to portuguese,
                Language.INDONESIAN to indonesian,
            ),
        )
    }
}
