package jp.co.crossmap

enum class DenominationNameMethod {
    OFFICIAL_WEBSITE,
    ESTABLISHED_USAGE,
    TRANSLATED,
}

data class DenominationNameEvidence(
    val method: DenominationNameMethod,
    val sourceUrl: String? = null,
) {
    init {
        if (method != DenominationNameMethod.TRANSLATED) {
            require(!sourceUrl.isNullOrBlank()) { "$method denomination names require a source URL" }
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
    val tradition: ChurchTradition,
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
