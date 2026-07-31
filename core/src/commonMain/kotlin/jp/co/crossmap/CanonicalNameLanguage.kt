package jp.co.crossmap

/** Languages whose resolved entity names may be stored in the canonical catalog. */
enum class CanonicalNameLanguage(
    val languageTag: String,
    val neo4jPropertySuffix: String,
) {
    JAPANESE("ja", "ja"),
    ENGLISH("en", "en"),
    KOREAN("ko", "ko"),
    PORTUGUESE("pt", "pt"),
    INDONESIAN("id", "id"),
    VIETNAMESE("vi", "vi"),
    CHINESE_SIMPLIFIED("zh-Hans", "zh_Hans"),
    CHINESE_TRADITIONAL("zh-Hant", "zh_Hant"),
    ;

    val neo4jNameProperty: String get() = "name_$neo4jPropertySuffix"

    companion object {
        fun fromLanguageTag(languageTag: String): CanonicalNameLanguage =
            entries.firstOrNull { it.languageTag == languageTag }
                ?: throw IllegalArgumentException("Unsupported canonical name language tag: '$languageTag'")

        fun fromNeo4jNameProperty(property: String): CanonicalNameLanguage =
            entries.firstOrNull { it.neo4jNameProperty == property }
                ?: throw IllegalArgumentException("Unsupported canonical Neo4j name property: '$property'")
    }
}

fun Iterable<LocalizedName>.toCanonicalNameMap(): Map<String, String> {
    val values = mapNotNull { localized ->
        localized.name.trim().takeIf(String::isNotEmpty)?.let { name ->
            CanonicalNameLanguage.fromLanguageTag(localized.languageCode) to name
        }
    }.groupBy({ it.first }, { it.second })
    return CanonicalNameLanguage.entries.mapNotNull { language ->
        values[language]?.minOrNull()?.let { language.languageTag to it }
    }.toMap(linkedMapOf())
}

fun Map<String, String>.toCanonicalLocalizedNames(): List<LocalizedName> =
    CanonicalNameLanguage.entries.mapNotNull { language ->
        get(language.languageTag)?.trim()?.takeIf(String::isNotEmpty)?.let { LocalizedName(language.languageTag, it) }
    }

fun Map<String, String>.toNeo4jNameProperties(): Map<String, String> =
    CanonicalNameLanguage.entries.mapNotNull { language ->
        get(language.languageTag)?.trim()?.takeIf(String::isNotEmpty)?.let { language.neo4jNameProperty to it }
    }.toMap(linkedMapOf())

fun Map<String, Any?>.neo4jNamePropertiesToLocalizedNames(): List<LocalizedName> =
    CanonicalNameLanguage.entries.mapNotNull { language ->
        (get(language.neo4jNameProperty) as? String)?.trim()?.takeIf(String::isNotEmpty)
            ?.let { LocalizedName(language.languageTag, it) }
    }
