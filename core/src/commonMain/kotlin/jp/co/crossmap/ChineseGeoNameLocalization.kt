package jp.co.crossmap

/** Adds first-class Chinese GeoName records while retaining every reviewed non-Chinese translation. */
fun GeoName.withChineseTranslations(): GeoName {
    fun existing(language: Language): String? = translations.entries
        .firstOrNull { (code, value) -> Language.fromCode(code) == language && value.isNotBlank() }
        ?.value
    val simplified = existing(Language.CHINESE_SIMPLIFIED)
        ?: ChineseScriptNormalizer.toSimplified(name)
    val traditional = existing(Language.CHINESE_TRADITIONAL)
        ?: ChineseScriptNormalizer.toTraditional(name)
    return copy(
        translations = translations.filterKeys { Language.fromCode(it) !in chineseLanguages } + mapOf(
            Language.CHINESE_SIMPLIFIED.code to simplified,
            Language.CHINESE_TRADITIONAL.code to traditional,
        ),
    )
}

private val chineseLanguages = setOf(Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL)
