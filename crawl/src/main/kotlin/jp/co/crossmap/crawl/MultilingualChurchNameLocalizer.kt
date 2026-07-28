package jp.co.crossmap.crawl

import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameGenerationMethod
import jp.co.crossmap.LocalizedNameMetadata
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import jp.co.crossmap.ChurchTradition
import jp.co.crossmap.Language
import jp.co.crossmap.denominationNamePart
import jp.co.crossmap.isDisplayableDenominationId
import kotlinx.serialization.Serializable

@Serializable
enum class MultilingualNameComponentRole { DENOMINATION, TRADITION, GEONAME, CONCEPT, CONGREGATION, CHURCH_NAME, OTHER }

@Serializable
data class MultilingualNameComponent(
    val source: String,
    val role: MultilingualNameComponentRole,
    val translations: Map<String, String>,
    val sourceLanguage: String = "ja",
)

data class LocalizedChurchNameResult(
    val japaneseName: String,
    val latinName: String?,
    val localizedNames: List<LocalizedName>,
    val pattern: ChurchNamePattern,
    val components: List<MultilingualNameComponent>,
)

/** One deterministic title-to-localized-names workflow shared by Google Maps resolution. */
class MultilingualChurchNameLocalizer(
    private val dictionaries: ChurchNameEnglishDictionaries,
    private val congregationTerms: CongregationTermDictionary,
    denominations: List<Denomination>,
    denominationNames: Map<Language, Map<String, String>> = emptyMap(),
    geonames: Map<String, String>,
    private val multilingualGeonames: Map<String, Map<String, String>> = emptyMap(),
    branchGeonames: Set<String> = emptySet(),
) {
    private val supportedTargets = listOf("en", "ko", "pt", "id", "vi", "zh-Hans", "zh-Hant")
    private val geonameEnglishTranslations = geonames.values.toSet()
    private val latinToJapaneseTerms = buildMap {
        listOf("en", "ko", "pt", "id", "es", "fr", "de", "it", "tl").forEach { sourceLanguage ->
            ChurchNameDictionaryCategory.entries.forEach { category ->
                putAll(dictionaries.multilingual.entries(sourceLanguage, "ja", category))
            }
            putAll(congregationTerms.translations(sourceLanguage, "ja"))
        }
        geonames.forEach { (japanese, latin) ->
            put("$latin-shi", japanese.removeSuffix("市"))
        }
        multilingualGeonames.entries
            .filter { (japanese, translations) ->
                // Skip alternative forms (e.g. simplified Chinese) of an authoritative geoname.
                // When the same English translation already exists in the canonical geonames map
                // under a different Japanese key, prefer the canonical form.
                val english = translations["en"].orEmpty()
                english.isBlank() || japanese in geonames || english !in geonameEnglishTranslations
            }
            .sortedWith(compareBy({ it.key.length }, { it.key })).forEach { (japanese, translations) ->
            translations.forEach { (language, translated) ->
                // English romaji is already handled by the authoritative geoname map.
                // Prefer the shortest Japanese base name when a localized alias is
                // shared by both a municipality and its suffixed administrative form.
                if (language in setOf("pt", "id", "es") && translated.isNotBlank()) {
                    putIfAbsent(translated, japanese)
                }
            }
        }
        dictionaries.multilingual.entries("en", "ja", ChurchNameDictionaryCategory.GEONAME)
            .forEach { (latin, japanese) -> put("$latin-shi", japanese.removeSuffix("市")) }
    }
    private val latinToJapanese = LatinChurchNameJapaneseComposer(
        concepts = dictionaries.concepts,
        geonames = geonames,
        additionalTerms = latinToJapaneseTerms,
    )
    private val decomposer = ChurchNameDecomposer(
        latinToJapanese = latinToJapanese::translate,
        branchGeonames = branchGeonames,
        knownLatinAbbreviations = denominations.knownLatinAbbreviations(),
    )
    private val excludedChurchNamePrefixes = denominations
        .filterNot(Denomination::useAsChurchNamePrefix)
        .flatMap { listOf(it.name) + it.aliases }
        .map(ChurchPublicNameNormalizer::normalize)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
    private val japaneseTerms: List<MultilingualNameComponent> = buildJapaneseTerms(
        dictionaries,
        congregationTerms,
        denominations,
        denominationNames,
        geonames,
        multilingualGeonames,
    )

    fun localize(
        title: String,
        evidencedLanguages: Collection<String> = emptyList(),
        addressContext: String = "",
    ): LocalizedChurchNameResult {
        val normalizedTitle = ChurchPublicNameNormalizer.normalize(title)
        val publicTitle = excludedChurchNamePrefixes.fold(normalizedTitle) { candidate, prefix ->
            candidate.removePrefix(prefix).trimStart(' ', '　', '-', '–', '—', ':', '：')
        }.ifBlank { normalizedTitle }
        val decomposed = decomposer.decompose(publicTitle)
        val preservedKoreanAbbreviations = JapaneseRomajiToHangul.churchAbbreviations(publicTitle)
        val initialJapaneseName = requireNotNull(decomposed.japaneseName) { "No Japanese name composed for $title" }
        val japaneseComponents = analyzeJapanese(initialJapaneseName, addressContext)
        val sourceLatinLanguage = decomposed.latinName?.let { latinName ->
            val evidenced = evidencedLanguages.map { it.substringBefore('-').lowercase() }.filter { it != "ja" }
            val detected = CybozuChurchNameLanguageIdentifier.detect(latinName)?.substringBefore('-')?.lowercase()
            evidenced.firstOrNull { it != "en" }
                ?: evidenced.firstOrNull { it == detected }
                ?: evidenced.firstOrNull()
                ?: decomposed.localizedNames.firstOrNull { it.name == latinName }?.languageCode?.substringBefore('-')?.lowercase()
                ?: detected
        }
        val sourceComponents = decomposed.latinName
            ?.takeIf { latinName -> latinName.none(::isJapaneseCharacter) }
            ?.let { analyzeLatin(it, sourceLatinLanguage ?: "en", initialJapaneseName) }
            ?.takeIf(List<MultilingualNameComponent>::isNotEmpty)
        val components = sourceComponents ?: japaneseComponents
        val japaneseName = sourceComponents
            ?.let { composeJapanese(it, sourceLatinLanguage ?: "en") }
            ?.takeIf(String::isNotBlank)
            ?: initialJapaneseName
        val generated = supportedTargets.mapNotNull { language ->
            compose(components, language)?.let { composed ->
                LocalizedName(
                    language,
                    if (language == "ko") {
                        JapaneseRomajiToHangul.transliterateLatinFragments(composed, preservedKoreanAbbreviations)
                    } else {
                        composed
                    },
                    metadata = if (
                        Language.fromCode(language) in chineseLanguages ||
                        Language.fromCode(language) == Language.VIETNAMESE
                    ) {
                        generatedMetadata(components, language)
                    } else {
                        null
                    },
                )
            }
        }
        val latinName = generated.firstOrNull { it.languageCode == "en" }?.name
            ?: decomposed.latinName?.takeIf { sourceLatinLanguage == "en" }
        val retainedDecomposedNames = decomposed.localizedNames.filterNot { localized ->
            sourceComponents != null && localized.languageCode.substringBefore('-').lowercase() == "ja"
        }
        val originalLanguages = retainedDecomposedNames.map(::canonicalLanguageCode).toSet()
        val localizedNames = (
            listOf(LocalizedName("ja", japaneseName)) +
                retainedDecomposedNames +
                generated.filter { canonicalLanguageCode(it.languageCode) !in originalLanguages }
            )
            .filter { it.name.isNotBlank() }
            .map { localized ->
                if (localized.languageCode.substringBefore('-').lowercase() == "ko") {
                    localized.copy(
                        name = JapaneseRomajiToHangul.transliterateLatinFragments(
                            localized.name,
                            preservedKoreanAbbreviations,
                        ),
                    )
                } else {
                    localized
                }
            }
            .distinctBy { canonicalLanguageCode(it.languageCode) to it.name }
        return LocalizedChurchNameResult(
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = localizedNames,
            pattern = decomposed.pattern,
            components = components,
        )
    }

    private fun analyzeLatin(
        value: String,
        sourceLanguage: String,
        expectedJapanese: String,
    ): List<MultilingualNameComponent> {
        val parts = latinToJapanese.translateParts(value, sourceLanguage)
        if (parts.joinToString("") { it.japanese } != expectedJapanese.replace(Regex("""\s+"""), "")) {
            return emptyList()
        }
        return parts.map { part ->
            val japaneseParts = analyzeJapanese(part.japanese)
            val translations = buildMap {
                put("ja", part.japanese)
                supportedTargets.forEach { target ->
                    compose(japaneseParts, target)?.let { put(target, it) }
                }
                put(sourceLanguage, part.source)
            }
            MultilingualNameComponent(
                source = part.source,
                role = japaneseParts.singleOrNull()?.role ?: MultilingualNameComponentRole.OTHER,
                translations = translations,
                sourceLanguage = sourceLanguage,
            )
        }
    }

    private fun composeJapanese(
        components: List<MultilingualNameComponent>,
        sourceLanguage: String,
    ): String {
        if (sourceLanguage !in setOf("pt", "es", "id")) {
            return components.joinToString("") { it.translations["ja"] ?: it.source }
        }
        val churchPrefix = components.firstOrNull()?.takeIf { component ->
            component.source.lowercase().trim('.', ',', '-', ' ') in setOf("igreja", "iglesia", "gereja") &&
                component.role == MultilingualNameComponentRole.CONGREGATION
        }
        val body = if (churchPrefix == null) components else components.drop(1)
        val hasRomanceChurchStructure = churchPrefix != null || body.any { component ->
            component.sourceLanguage in setOf("pt", "es", "id") &&
                component.role in setOf(
                    MultilingualNameComponentRole.CONGREGATION,
                    MultilingualNameComponentRole.CONCEPT,
                    MultilingualNameComponentRole.CHURCH_NAME,
                )
        }
        val terminalGeoname = body.lastOrNull()?.takeIf {
            hasRomanceChurchStructure && it.role == MultilingualNameComponentRole.GEONAME
        }
        val bodyWithoutTerminal = if (terminalGeoname == null) body else body.dropLast(1)
        val contentBody = bodyWithoutTerminal.dropLastWhile { component ->
            terminalGeoname != null &&
                component.role == MultilingualNameComponentRole.OTHER &&
                component.source.lowercase().trim('.', ',', '-', ' ') in setOf("de", "do", "da", "del", "di")
        }
        val ordered = buildList {
            terminalGeoname?.let(::add)
            addAll(contentBody)
            churchPrefix?.let(::add)
        }
        return ordered.joinToString("") { it.translations["ja"] ?: it.source }
    }

    private fun analyzeJapanese(value: String, addressContext: String = ""): List<MultilingualNameComponent> {
        val components = mutableListOf<MultilingualNameComponent>()
        val unknown = StringBuilder()
        fun flushUnknown() {
            val source = unknown.toString().trim(' ', '・', '-', 'ー')
            unknown.clear()
            if (source.isBlank()) return
            val english = when {
                source.all { it.code < 128 } -> source
                else -> JapaneseNameRomanizer.romanize(source)
            }
            val translations = when {
                source.all { it.code < 128 } -> supportedTargets.associateWith { source }
                else -> english?.let { mapOf("en" to it) }.orEmpty()
            }
            components += MultilingualNameComponent(
                source = source,
                role = MultilingualNameComponentRole.OTHER,
                translations = translations + ("ja" to source),
            )
        }

        var index = 0
        while (index < value.length) {
            val term = japaneseTerms.firstOrNull { value.startsWith(it.source, index) }
            if (term == null) {
                unknown.append(value[index++])
            } else {
                flushUnknown()
                components += term
                index += term.source.length
            }
        }
        flushUnknown()
        if (addressContext.isBlank()) return components
        return components.map { component ->
            if (component.role != MultilingualNameComponentRole.GEONAME) return@map component
            val contextualEnglish = multilingualGeonames.entries
                .asSequence()
                .filter { (japanese, translations) ->
                    japanese.length > component.source.length &&
                        japanese.contains(component.source) &&
                        addressContext.contains(japanese) &&
                        !translations["en"].isNullOrBlank()
                }
                .maxByOrNull { (japanese) -> japanese.length }
                ?.value
                ?.get("en")
                ?.removeEnglishAdministrativeSuffix()
                ?.takeIf(String::isNotBlank)
                ?: return@map component
            component.copy(translations = component.translations + ("en" to contextualEnglish))
        }
    }

    private fun compose(components: List<MultilingualNameComponent>, targetLanguage: String): String? {
        val withoutLocationConnector = if (components.lastOrNull()?.role == MultilingualNameComponentRole.GEONAME) {
            components.dropLast(1).dropLastWhile { component ->
                component.role == MultilingualNameComponentRole.OTHER &&
                    component.source.lowercase().trim('.', ',', '-', ' ') in setOf("de", "do", "da", "del", "di")
            } + components.last()
        } else {
            components
        }
        val terminalTranslation = withoutLocationConnector.lastOrNull()?.translations?.get(targetLanguage).orEmpty()
        val terminalChurchPrefix = targetLanguage in setOf("pt", "id", "vi") &&
            (terminalTranslation.startsWith("Igreja ") || terminalTranslation.startsWith("Gereja ") || terminalTranslation.startsWith("Hội Thánh "))
        val orderedComponents = if (
            targetLanguage in setOf("pt", "id", "vi") &&
            (withoutLocationConnector.lastOrNull()?.role == MultilingualNameComponentRole.CONGREGATION || terminalChurchPrefix)
        ) {
            listOf(withoutLocationConnector.last()) + withoutLocationConnector.dropLast(1)
        } else {
            withoutLocationConnector
        }
        val isChinese = Language.fromCode(targetLanguage) in chineseLanguages
        val translated = orderedComponents.map { component ->
            component.translations[targetLanguage]
                ?: (if (isChinese) null else component.translations["en"])
                ?: component.translations[component.sourceLanguage]
                ?: component.source
        }
        val separator = if (isChinese) "" else " "
        return translated.filter(String::isNotBlank).joinToString(separator).takeIf(String::isNotBlank)
    }

    private fun generatedMetadata(
        components: List<MultilingualNameComponent>,
        targetLanguage: String,
    ): LocalizedNameMetadata {
        val matched = components.filter { component ->
            val translated = component.translations[targetLanguage]
            !translated.isNullOrBlank() && !(translated == component.source && japaneseKana.containsMatchIn(translated))
        }
        val unmatched = components.filterNot { it in matched }.map(MultilingualNameComponent::source)
        val reviewReasons = buildList {
            if (unmatched.isNotEmpty()) add("Unmatched source segments were preserved")
            if (unmatched.any(japaneseKana::containsMatchIn)) {
                add("${Language.fromCode(targetLanguage)?.displayName ?: targetLanguage} output retains Japanese Kana")
            }
        }
        return LocalizedNameMetadata(
            source = LocalizedNameSource.GENERATED,
            generationMethod = if (unmatched.isEmpty()) {
                LocalizedNameGenerationMethod.TOKEN_RULE
            } else {
                LocalizedNameGenerationMethod.ORIGINAL_FALLBACK
            },
            dictionaryVersion = LOCALIZATION_DICTIONARY_VERSION,
            confidence = if (unmatched.isEmpty()) 0.9 else 0.6,
            reviewStatus = if (unmatched.isEmpty()) {
                LocalizedNameReviewStatus.UNREVIEWED
            } else {
                LocalizedNameReviewStatus.NEEDS_REVIEW
            },
            reviewReasons = reviewReasons,
            matchedDictionaryEntries = matched.map(MultilingualNameComponent::source),
            unmatchedSegments = unmatched,
        )
    }

    private fun buildJapaneseTerms(
        dictionaries: ChurchNameEnglishDictionaries,
        congregationTerms: CongregationTermDictionary,
        denominations: List<Denomination>,
        denominationNames: Map<Language, Map<String, String>>,
        geonames: Map<String, String>,
        multilingualGeonames: Map<String, Map<String, String>>,
    ): List<MultilingualNameComponent> {
        data class MutableTerm(
            var role: MultilingualNameComponentRole,
            val translations: MutableMap<String, String> = linkedMapOf(),
        )
        val terms = linkedMapOf<String, MutableTerm>()
        fun rolePriority(role: MultilingualNameComponentRole): Int = when (role) {
            MultilingualNameComponentRole.DENOMINATION -> 0
            MultilingualNameComponentRole.TRADITION -> 1
            MultilingualNameComponentRole.CHURCH_NAME -> 2
            MultilingualNameComponentRole.CONGREGATION -> 3
            MultilingualNameComponentRole.CONCEPT -> 4
            MultilingualNameComponentRole.GEONAME -> 5
            MultilingualNameComponentRole.OTHER -> 6
        }
        fun add(source: String, role: MultilingualNameComponentRole, targetLanguage: String, target: String) {
            if (source.isBlank() || target.isBlank()) return
            val term = terms.getOrPut(source) { MutableTerm(role) }
            if (rolePriority(role) < rolePriority(term.role)) term.role = role
            term.translations[targetLanguage] = target
        }

        denominations.forEach { denomination ->
            if (!denomination.id.isDisplayableDenominationId() || !denomination.useAsChurchNamePrefix) return@forEach
            (listOf(denomination.name) + denomination.aliases).filter(String::isNotBlank).forEach { alias ->
                supportedTargets.forEach { languageCode ->
                    val language = requireNotNull(Language.fromCode(languageCode))
                    val catalogName = denominationNames[language]?.get(denomination.id)
                    val namePart = if (denomination.id == "CATHOLIC_JP" && alias.endsWith("教会")) {
                        catholicChurchName(language)
                    } else {
                        catalogName?.let { denominationNamePart(it, language) }
                    }
                    val acronym = denomination.id.takeIf {
                        language == Language.ENGLISH && it.matches(Regex("[A-Z][A-Z0-9]{1,8}"))
                    }
                    add(alias, MultilingualNameComponentRole.DENOMINATION, languageCode, acronym ?: namePart.orEmpty())
                }
            }
        }
        ChurchTradition.entries.forEach { tradition ->
            tradition.aliases.filter { alias -> alias.any(::isJapaneseCharacter) }.forEach { alias ->
                supportedTargets.forEach { languageCode ->
                    val language = requireNotNull(Language.fromCode(languageCode))
                    add(alias, MultilingualNameComponentRole.TRADITION, languageCode, tradition.namePart(language))
                }
            }
        }
        geonames.forEach { (japanese, english) ->
            add(japanese, MultilingualNameComponentRole.GEONAME, "en", english)
        }
        multilingualGeonames
            .filter { (japanese, translations) ->
                // Skip alternative forms (e.g. simplified Chinese) of an authoritative geoname.
                val english = translations["en"].orEmpty()
                english.isBlank() || japanese in geonames || english !in geonameEnglishTranslations
            }
            .forEach { (japanese, translations) ->
            translations.forEach { (language, translated) ->
                if (language in supportedTargets) {
                    add(japanese, MultilingualNameComponentRole.GEONAME, language, translated)
                }
            }
        }
        supportedTargets.forEach { targetLanguage ->
            ChurchNameDictionaryCategory.entries.forEach { category ->
                val role = when (category) {
                    ChurchNameDictionaryCategory.CHURCHNAME -> MultilingualNameComponentRole.CHURCH_NAME
                    ChurchNameDictionaryCategory.CONCEPT -> MultilingualNameComponentRole.CONCEPT
                    ChurchNameDictionaryCategory.GEONAME -> MultilingualNameComponentRole.GEONAME
                }
                dictionaries.multilingual.entries("ja", targetLanguage, category).forEach { (source, target) ->
                    add(source, role, targetLanguage, target)
                }
            }
            congregationTerms.translations("ja", targetLanguage).forEach { (source, target) ->
                add(source, MultilingualNameComponentRole.CONGREGATION, targetLanguage, target)
            }
        }
        return terms.map { (source, term) ->
            MultilingualNameComponent(source, term.role, term.translations.toMap() + ("ja" to source))
        }.sortedByDescending { it.source.length }
    }

    private fun isJapaneseCharacter(value: Char): Boolean =
        value in '\u3040'..'\u30ff' || value in '\u3400'..'\u9fff'

    private fun catholicChurchName(language: Language): String = when (language) {
        Language.JAPANESE -> "カトリック教会"
        Language.ENGLISH -> "Catholic Church"
        Language.KOREAN -> "가톨릭교회"
        Language.PORTUGUESE -> "Igreja Católica"
        Language.INDONESIAN -> "Gereja Katolik"
        Language.VIETNAMESE -> "Nhà thờ Công giáo"
        Language.CHINESE_SIMPLIFIED -> "天主教堂"
        Language.CHINESE_TRADITIONAL -> "天主教堂"
    }

    private fun String.removeEnglishAdministrativeSuffix(): String =
        replace(Regex("(?i)(?:[- ](?:ku|shi|cho|machi|mura)| (?:ward|city|town|village))$"), "").trim()

    private fun canonicalLanguageCode(value: LocalizedName): String = canonicalLanguageCode(value.languageCode)

    private fun canonicalLanguageCode(value: String): String =
        Language.fromCode(value)?.code ?: value.substringBefore('-').lowercase()

    private companion object {
        val chineseLanguages = setOf(Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL)
        val japaneseKana = Regex("[ぁ-ゟ゠-ヿ]")
        const val LOCALIZATION_DICTIONARY_VERSION = "2026-07-28"
    }

}
