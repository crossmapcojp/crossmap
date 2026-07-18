package jp.co.crossmap

enum class DenominationNameMethod {
    OFFICIAL_WEBSITE,
    ESTABLISHED_USAGE,
    TRANSLATED,
}

data class DenominationNameEvidence(
    val method: DenominationNameMethod,
    val sourceUrl: String? = null,
    val note: String? = null,
) {
    init {
        if (method == DenominationNameMethod.OFFICIAL_WEBSITE) {
            require(!sourceUrl.isNullOrBlank()) { "$method denomination names require a source URL" }
        }
        if (method == DenominationNameMethod.ESTABLISHED_USAGE) {
            require(!sourceUrl.isNullOrBlank() || !note.isNullOrBlank()) {
                "$method denomination names require a source URL or catalog note"
            }
        }
    }
}

/**
 * Names of one concrete church organization, not names of a theological family.
 *
 * For example, JELC and WJELC are separate denominations whose tradition is
 * [ChurchTradition.LUTHERAN]. A denomination's own published name always wins
 * over a translation inferred from its tradition. Translation work may split a
 * name into semantic parts, but the reviewed, naturally ordered final name is
 * what is stored here.
 */
data class DenominationNames(
    val id: String,
    val tradition: ChurchTradition?,
    val names: LocalizedText,
    val nameParts: LocalizedText,
    val evidence: Map<Language, DenominationNameEvidence>,
) {
    init {
        require(evidence.keys == supportedLanguages.toSet()) {
            "$id must record name provenance for every supported language"
        }
    }

    fun name(language: Language): String = names[language]
    fun namePart(language: Language): String = nameParts[language]
    fun evidence(language: Language): DenominationNameEvidence = evidence.getValue(language)
}

val nonDisplayDenominationIds: Set<String> = setOf("NOT_DETERMINED", "INDEPENDENT_CHURCH")

/** Internal classification sentinels are never user-facing denomination names. */
fun String?.isDisplayableDenominationId(): Boolean =
    !isNullOrBlank() && this !in nonDisplayDenominationIds

/** Defensive cleanup for old cached localized names produced before sentinel filtering. */
fun String.withoutInternalDenominationMarkers(): String = nonDisplayDenominationIds
    .fold(this) { value, marker -> value.replace(marker, " ") }
    .replace(Regex("\\s+"), " ")
    .trim()

/** A denomination's organization name with its congregation word removed for use inside a church name. */
fun denominationNamePart(value: String, language: Language): String {
    val trimmed = value.trim()
    val withoutCongregation = when (language) {
        Language.JAPANESE -> trimmed.removeSuffix("教会").removeSuffix("教団")
        Language.ENGLISH -> trimmed.removeSuffix(" Church").removeSuffix(" Denomination")
        Language.KOREAN -> trimmed.removeSuffix(" 교회").removeSuffix("교회").removeSuffix(" 교단").removeSuffix("교단")
        Language.PORTUGUESE -> trimmed.removePrefix("Igreja ").removePrefix("Igrejas ")
        Language.INDONESIAN -> trimmed.removePrefix("Gereja ")
    }.trim()
    return when {
        language == Language.ENGLISH && withoutCongregation.matches(Regex("[a-z][a-z0-9.-]*")) ->
            withoutCongregation.uppercase()
        else -> withoutCongregation
    }
}
