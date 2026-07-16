package jp.co.crossmap.crawl

import java.nio.file.Path
import java.time.LocalDateTime
import jp.co.crossmap.DeterminationSource

internal enum class NamePartTranslationMethod(val logLabel: String) {
    DENOMINATION_DATA("denomination data"),
    GEONAME_DATA("geoname data"),
    GEONAME_DICTIONARY("geoname dictionary"),
    CONCEPT_DATA("concept data"),
    CONCEPT_DICTIONARY("concept dictionary"),
    TRADITION_DATA("tradition data"),
    CONGREGATION_DATA("congregation data"),
    OTHER_DETERMINISTIC("other deterministic"),
    LLM("llm"),
}

internal data class LlmComposedNamePartDetail(
    val type: String,
    val japanese: String,
    val english: String,
    val translationMethod: NamePartTranslationMethod,
    val evidence: String,
)

internal data class LlmComposedNameDetail(
    val churchId: String,
    val japaneseName: String,
    val englishName: String,
    val model: String?,
    val parts: List<LlmComposedNamePartDetail>,
)

internal fun buildLlmComposedNameDetails(
    inputs: List<ChurchEnglishNameInput>,
    resolutions: Map<String, ResolvedChurchEnglishName>,
    analyzer: ChurchNameComponentAnalyzer,
    denominations: List<Denomination>,
    denominationEnglishNames: Map<String, String>,
    conceptDictionaryKeys: Set<String>,
    specialGeonameDictionaryKeys: Set<String>,
    knownGeonames: Set<String>,
): List<LlmComposedNameDetail> = inputs.mapNotNull { church ->
    val resolution = resolutions[church.id]?.takeIf { it.source == DeterminationSource.LLM } ?: return@mapNotNull null
    val analysis = analyzer.analyze(church)
    val translatedParts = resolution.parts.groupBy { it.role to it.japanese }
        .mapValues { (_, parts) -> ArrayDeque(parts) }
    val parts = buildList {
        denominationPart(church, analysis, denominations, denominationEnglishNames)?.let(::add)
        if (analysis != null) {
            analysis.components.forEach { component ->
                val translated = translatedParts[component.role to component.japanese]?.removeFirstOrNull()
                add(
                    LlmComposedNamePartDetail(
                        type = component.role.name,
                        japanese = component.japanese,
                        english = component.english ?: translated?.english.orEmpty(),
                        translationMethod = component.translationMethod(
                            conceptDictionaryKeys,
                            specialGeonameDictionaryKeys,
                        ),
                        evidence = component.evidence,
                    ),
                )
            }
            add(
                LlmComposedNamePartDetail(
                    type = ChurchNamePartRole.CONGREGATION.name,
                    japanese = analysis.congregationJapanese,
                    english = analysis.congregationEnglish,
                    translationMethod = NamePartTranslationMethod.CONGREGATION_DATA,
                    evidence = "congregation word lexicon",
                ),
            )
        } else {
            resolution.parts.forEach { part ->
                add(
                    LlmComposedNamePartDetail(
                        type = part.role.name,
                        japanese = part.japanese,
                        english = part.english,
                        translationMethod = part.translationMethod(
                            conceptDictionaryKeys,
                            specialGeonameDictionaryKeys,
                            knownGeonames,
                        ),
                        evidence = "whole-name LLM fallback output",
                    ),
                )
            }
        }
    }
    LlmComposedNameDetail(
        churchId = church.id,
        japaneseName = church.name,
        englishName = resolution.englishName,
        model = resolution.model,
        parts = parts,
    )
}.sortedWith(compareBy(LlmComposedNameDetail::japaneseName, LlmComposedNameDetail::churchId))

internal fun writeLlmComposedNameDetailLog(
    details: List<LlmComposedNameDetail>,
    logsDirectory: Path = projectLogsDirectory(),
    now: LocalDateTime = LocalDateTime.now(),
): Path {
    CrawlReportLogging.configureIfNeeded(logsDirectory, now)
    return CrawlReportLogging.log(CrawlReport.LLM_COMPOSED_NAME_DETAIL, renderLlmComposedNameDetails(details))
}

internal fun renderLlmComposedNameDetails(details: List<LlmComposedNameDetail>): String = buildString {
    appendLine("llm_composed_names=${details.size}")
    details.forEach { detail ->
        appendLine("---")
        appendLine("church_id=${detail.churchId.logValue()}")
        appendLine("japanese_name=${detail.japaneseName.logValue()}")
        appendLine("english_name=${detail.englishName.logValue()}")
        appendLine("model=${detail.model.orEmpty().logValue()}")
        detail.parts.forEachIndexed { index, part ->
            appendLine("- part_${index + 1}")
            appendLine("  type=${part.type}")
            appendLine("  japanese=${part.japanese.logValue()}")
            appendLine("  english=${part.english.logValue()}")
            appendLine("  translation_method=${part.translationMethod.logLabel}")
            appendLine("  evidence=${part.evidence.logValue()}")
        }
    }
}

private fun denominationPart(
    church: ChurchEnglishNameInput,
    analysis: ChurchNameAnalysis?,
    denominations: List<Denomination>,
    denominationEnglishNames: Map<String, String>,
): LlmComposedNamePartDetail? {
    val aliases = denominations.flatMap { denomination ->
        (listOf(denomination.name) + denomination.aliases).map { alias -> alias to denomination }
    }.filter { (alias) -> alias.isNotBlank() }.sortedByDescending { (alias) -> alias.length }
    val detected = analysis?.denominationAlias?.let { alias ->
        aliases.firstOrNull { it.first == alias }
    } ?: aliases.firstOrNull { (alias, denomination) ->
        church.name.replace(" ", "").startsWith(alias.replace(" ", "")) &&
            (church.denominationId == null || church.denominationId == denomination.id)
    } ?: return null
    val (alias, denomination) = detected
    val denominationEnglish = denominationEnglishNames[denomination.id]
        ?.let { if (it.equals(denomination.id, ignoreCase = true)) denomination.id else it }
        ?: denomination.id
    return LlmComposedNamePartDetail(
        type = "DENOMINATION",
        japanese = alias,
        english = denominationEnglish,
        translationMethod = NamePartTranslationMethod.DENOMINATION_DATA,
        evidence = "denomination catalog id=${denomination.id}",
    )
}

private fun ChurchNameComponent.translationMethod(
    conceptDictionaryKeys: Set<String>,
    specialGeonameDictionaryKeys: Set<String>,
): NamePartTranslationMethod = when {
    english.isNullOrBlank() -> NamePartTranslationMethod.LLM
    role == ChurchNamePartRole.GEONAME && japanese in specialGeonameDictionaryKeys ->
        NamePartTranslationMethod.GEONAME_DICTIONARY
    role == ChurchNamePartRole.GEONAME -> NamePartTranslationMethod.GEONAME_DATA
    role == ChurchNamePartRole.CONCEPTUAL_NAME && japanese in conceptDictionaryKeys ->
        NamePartTranslationMethod.CONCEPT_DICTIONARY
    role == ChurchNamePartRole.CONCEPTUAL_NAME -> NamePartTranslationMethod.CONCEPT_DATA
    role == ChurchNamePartRole.TRADITION -> NamePartTranslationMethod.TRADITION_DATA
    role == ChurchNamePartRole.CONGREGATION -> NamePartTranslationMethod.CONGREGATION_DATA
    else -> NamePartTranslationMethod.OTHER_DETERMINISTIC
}

private fun TranslatedChurchNamePart.translationMethod(
    conceptDictionaryKeys: Set<String>,
    specialGeonameDictionaryKeys: Set<String>,
    knownGeonames: Set<String>,
): NamePartTranslationMethod = when {
    role == ChurchNamePartRole.GEONAME && japanese in specialGeonameDictionaryKeys ->
        NamePartTranslationMethod.GEONAME_DICTIONARY
    role == ChurchNamePartRole.GEONAME && japanese in knownGeonames -> NamePartTranslationMethod.GEONAME_DATA
    role == ChurchNamePartRole.CONCEPTUAL_NAME && japanese in conceptDictionaryKeys ->
        NamePartTranslationMethod.CONCEPT_DICTIONARY
    role == ChurchNamePartRole.TRADITION -> NamePartTranslationMethod.TRADITION_DATA
    role == ChurchNamePartRole.CONGREGATION -> NamePartTranslationMethod.CONGREGATION_DATA
    else -> NamePartTranslationMethod.LLM
}

private fun String.logValue(): String = replace('\n', ' ').replace('\r', ' ').trim()
