package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord

/** Removes the country qualifier from Catholic congregation names in every published language. */
internal object CatholicChurchNameNormalizer {
    fun normalize(church: ChurchRecord): ChurchRecord {
        val englishName = english(church.englishName)
        val localizedNames = church.localizedNames.map { localized ->
            localized.copy(name = when (localized.languageCode.substringBefore('-').lowercase()) {
                "en" -> english(localized.name)
                "ko" -> korean(localized.name)
                "pt" -> portuguese(localized.name)
                "id" -> indonesian(localized.name)
                else -> localized.name
            })
        }.distinctBy { it.languageCode.substringBefore('-').lowercase() to it.name }
        if (englishName == church.englishName && localizedNames == church.localizedNames) return church
        return church.copy(
            englishName = englishName,
            localizedNames = localizedNames,
            determinations = church.determinations.map { determination ->
                if (determination.field == "englishName") determination.copy(value = english(determination.value)) else determination
            },
        )
    }

    fun english(value: String): String {
        if (!value.contains(ENGLISH_ORGANIZATION)) return value
        val remainder = value.replace(ENGLISH_ORGANIZATION, " ").normalizedWords()
            .removeSuffix(" Church").trim()
        return listOf(remainder, "Catholic Church").filter(String::isNotBlank).joinToString(" ")
    }

    private fun korean(value: String): String {
        if (!value.contains(KOREAN_ORGANIZATION)) return value
        val remainder = value.replace(KOREAN_ORGANIZATION, " ").normalizedWords()
            .removeSuffix(" 교회").trim()
        return listOf(remainder, "가톨릭교회").filter(String::isNotBlank).joinToString(" ")
    }

    private fun portuguese(value: String): String {
        if (!value.contains(PORTUGUESE_ORGANIZATION)) return value
        val remainder = value.removePrefix("Igreja ").replace(PORTUGUESE_ORGANIZATION, " ").normalizedWords()
        return listOf("Igreja Católica", remainder).filter(String::isNotBlank).joinToString(" ")
    }

    private fun indonesian(value: String): String {
        if (!value.contains(INDONESIAN_ORGANIZATION)) return value
        val remainder = value.removePrefix("Gereja ").replace(INDONESIAN_ORGANIZATION, " ").normalizedWords()
        return listOf("Gereja Katolik", remainder).filter(String::isNotBlank).joinToString(" ")
    }

    private fun String.normalizedWords(): String = replace(Regex("\\s+"), " ").trim()

    private const val ENGLISH_ORGANIZATION = "Catholic Church in Japan"
    private const val KOREAN_ORGANIZATION = "일본 가톨릭"
    private const val PORTUGUESE_ORGANIZATION = "Católica no Japão"
    private const val INDONESIAN_ORGANIZATION = "Katolik di Jepang"
}
