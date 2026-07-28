package jp.co.crossmap

enum class Language(val code: String, val displayName: String) {
    JAPANESE("ja", "日本語"),
    ENGLISH("en", "English"),
    KOREAN("ko", "한국어"),
    PORTUGUESE("pt", "Português"),
    INDONESIAN("id", "Bahasa Indonesia"),
    VIETNAMESE("vi", "Tiếng Việt"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    ;

    companion object {
        fun fromCode(code: String?): Language? {
            val normalized = code?.trim()?.replace('_', '-')?.lowercase() ?: return null
            entries.firstOrNull { it.code.lowercase() == normalized }?.let { return it }
            if (normalized == "zh" || normalized.startsWith("zh-cn") || normalized.startsWith("zh-sg") || normalized.startsWith("zh-hans")) {
                return CHINESE_SIMPLIFIED
            }
            if (normalized.startsWith("zh-tw") || normalized.startsWith("zh-hk") || normalized.startsWith("zh-mo") || normalized.startsWith("zh-hant")) {
                return CHINESE_TRADITIONAL
            }
            val base = normalized.substringBefore('-')
            return entries.firstOrNull { it.code == base }
        }

        fun fromCodeOrEnglish(code: String?): Language = fromCode(code) ?: ENGLISH
    }
}

val supportedLanguages: List<Language> = Language.entries
val supportedLanguageCodes: List<String> = supportedLanguages.map(Language::code)

fun localizedDomainText(
    language: Language,
    localizedValues: List<LocalizedName>,
    english: String? = null,
    japanese: String? = null,
): String? {
    fun valueFor(target: Language): String? = localizedValues
        .asSequence()
        .filter { Language.fromCode(it.languageCode) == target && it.name.isNotBlank() }
        .filter { it.metadata?.reviewStatus != LocalizedNameReviewStatus.REJECTED }
        .maxWithOrNull(
            compareBy<LocalizedName> { localizedNamePriority(it) }
                .thenBy { it.metadata?.confidence ?: 0.0 },
        )
        ?.name
    val alternateChinese = when (language) {
        Language.CHINESE_SIMPLIFIED -> Language.CHINESE_TRADITIONAL
        Language.CHINESE_TRADITIONAL -> Language.CHINESE_SIMPLIFIED
        else -> null
    }
    if (alternateChinese != null) {
        return valueFor(language)
            ?: valueFor(alternateChinese)
            ?: japanese?.takeIf(String::isNotBlank)
            ?: valueFor(Language.JAPANESE)
            ?: english?.takeIf(String::isNotBlank)
            ?: valueFor(Language.ENGLISH)
            ?: localizedValues.firstNotNullOfOrNull { it.name.takeIf(String::isNotBlank) }
    }
    return valueFor(language)
        ?: english?.takeIf(String::isNotBlank)
        ?: valueFor(Language.ENGLISH)
        ?: japanese?.takeIf(String::isNotBlank)
        ?: valueFor(Language.JAPANESE)
        ?: localizedValues.firstNotNullOfOrNull { it.name.takeIf(String::isNotBlank) }
}

private fun localizedNamePriority(value: LocalizedName): Int {
    val metadata = value.metadata ?: return 600
    val reviewPriority = when (metadata.reviewStatus) {
        LocalizedNameReviewStatus.REVIEWED -> 1_000
        LocalizedNameReviewStatus.UNREVIEWED -> 0
        LocalizedNameReviewStatus.NEEDS_REVIEW -> -100
        LocalizedNameReviewStatus.REJECTED -> -10_000
    }
    val sourcePriority = when (metadata.source) {
        LocalizedNameSource.MANUAL -> 800
        LocalizedNameSource.OFFICIAL -> 700
        LocalizedNameSource.GENERATED -> 500
        LocalizedNameSource.IMPORTED -> 400
    }
    return reviewPriority + sourcePriority
}

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
            chineseSimplified: String = japanese,
            chineseTraditional: String = japanese,
            vietnamese: String = english,
        ): LocalizedText = LocalizedText(
            mapOf(
                Language.JAPANESE to japanese,
                Language.ENGLISH to english,
                Language.KOREAN to korean,
                Language.PORTUGUESE to portuguese,
                Language.INDONESIAN to indonesian,
                Language.VIETNAMESE to vietnamese,
                Language.CHINESE_SIMPLIFIED to chineseSimplified,
                Language.CHINESE_TRADITIONAL to chineseTraditional,
            ),
        )
    }
}
