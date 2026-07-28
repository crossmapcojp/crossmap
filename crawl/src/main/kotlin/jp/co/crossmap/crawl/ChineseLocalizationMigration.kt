package jp.co.crossmap.crawl

import jp.co.crossmap.ChineseScriptNormalizer
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.Language
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameGenerationMethod
import jp.co.crossmap.LocalizedNameMetadata
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import kotlinx.serialization.Serializable

@Serializable
data class ChineseLocalizationReviewEntry(
    val churchId: String,
    val officialNameJa: String,
    val generatedZhHans: String,
    val generatedZhHant: String,
    val confidence: Double,
    val reviewReasons: List<String>,
    val matchedDictionaryEntries: List<String>,
    val unmatchedSegments: List<String>,
    val generationMethods: List<String>,
)

@Serializable
data class ChineseLocalizationMigrationReport(
    val churchesProcessed: Int,
    val zhHansNamesGenerated: Int,
    val zhHantNamesGenerated: Int,
    val namesPreservedBecauseReviewedOrOfficial: Int,
    val ministersLocalized: Int,
    val churchesRequiringReview: Int,
    val indexingChanges: Int,
    val dictionaryCoverageRate: Double,
    val unmatchedTokenFrequency: Map<String, Int>,
    val reviewEntries: List<ChineseLocalizationReviewEntry>,
)

data class ChineseLocalizationMigrationResult(
    val churches: List<ChurchRecord>,
    val report: ChineseLocalizationMigrationReport,
)

/** Deterministic, idempotent reprocessing of stored Chinese church and minister names. */
class ChineseLocalizationMigration(
    private val localize: (ChurchRecord) -> LocalizedChurchNameResult,
) {
    constructor(localizer: MultilingualChurchNameLocalizer) : this(
        { church -> localizer.localize(church.name, church.titleLanguages, church.address) },
    )

    fun process(churches: List<ChurchRecord>): ChineseLocalizationMigrationResult {
        var generatedHans = 0
        var generatedHant = 0
        var preserved = 0
        var ministersLocalized = 0
        var indexingChanges = 0
        var matchedSegments = 0
        var totalSegments = 0
        val unmatchedFrequency = mutableMapOf<String, Int>()
        val reviewEntries = mutableListOf<ChineseLocalizationReviewEntry>()
        val migrated = churches.map { church ->
            val generated = localize(church)
            val generatedChinese = generated.localizedNames.filter(::isChinese)
            val mergedChurchNames = mergeChineseNames(church.localizedNames, generatedChinese) { language, outcome ->
                when (outcome) {
                    MergeOutcome.PRESERVED -> preserved++
                    MergeOutcome.UNCHANGED -> Unit
                    MergeOutcome.GENERATED -> when (language) {
                        Language.CHINESE_SIMPLIFIED -> generatedHans++
                        Language.CHINESE_TRADITIONAL -> generatedHant++
                        else -> Unit
                    }
                }
            }
            generatedChinese.mapNotNull(LocalizedName::metadata).forEach { metadata ->
                matchedSegments += metadata.matchedDictionaryEntries.size
                totalSegments += metadata.matchedDictionaryEntries.size + metadata.unmatchedSegments.size
                metadata.unmatchedSegments.forEach { segment -> unmatchedFrequency.merge(segment, 1, Int::plus) }
            }
            val migratedMinisters = church.ministers.map { minister ->
                val additions = personalNameFallbacks(minister)
                val merged = mergeChineseNames(minister.localizedNames, additions) { _, outcome ->
                    when (outcome) {
                        MergeOutcome.PRESERVED -> preserved++
                        MergeOutcome.GENERATED -> ministersLocalized++
                        MergeOutcome.UNCHANGED -> Unit
                    }
                }
                minister.copy(localizedNames = merged)
            }
            val updated = church.copy(localizedNames = mergedChurchNames, ministers = migratedMinisters)
            if (updated != church) indexingChanges++
            reviewEntry(church, mergedChurchNames)?.let(reviewEntries::add)
            updated
        }
        return ChineseLocalizationMigrationResult(
            churches = migrated,
            report = ChineseLocalizationMigrationReport(
                churchesProcessed = churches.size,
                zhHansNamesGenerated = generatedHans,
                zhHantNamesGenerated = generatedHant,
                namesPreservedBecauseReviewedOrOfficial = preserved,
                ministersLocalized = ministersLocalized,
                churchesRequiringReview = reviewEntries.size,
                indexingChanges = indexingChanges,
                dictionaryCoverageRate = if (totalSegments == 0) 1.0 else matchedSegments.toDouble() / totalSegments,
                unmatchedTokenFrequency = unmatchedFrequency.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .associate { it.toPair() },
                reviewEntries = reviewEntries,
            ),
        )
    }

    private fun mergeChineseNames(
        existing: List<LocalizedName>,
        generated: List<LocalizedName>,
        observed: (Language, MergeOutcome) -> Unit,
    ): List<LocalizedName> = chineseLanguages.fold(existing) { names, language ->
        val candidate = generated.firstOrNull { Language.fromCode(it.languageCode) == language } ?: return@fold names
        val sameLanguage = names.filter { Language.fromCode(it.languageCode) == language }
        val protected = sameLanguage.any(::isProtected)
        when {
            protected -> names.also { observed(language, MergeOutcome.PRESERVED) }
            sameLanguage.size == 1 && sameLanguage.single() == candidate ->
                names.also { observed(language, MergeOutcome.UNCHANGED) }
            else -> (names.filterNot { Language.fromCode(it.languageCode) == language } + candidate)
                .also { observed(language, MergeOutcome.GENERATED) }
        }
    }

    private fun personalNameFallbacks(minister: ChurchMinister): List<LocalizedName> {
        val source = minister.name.trim()
        if (source.isBlank()) return emptyList()
        val metadata = LocalizedNameMetadata(
            source = LocalizedNameSource.GENERATED,
            generationMethod = LocalizedNameGenerationMethod.SCRIPT_CONVERSION,
            confidence = if (hanOnly.matches(source)) 0.55 else 0.30,
            reviewStatus = LocalizedNameReviewStatus.NEEDS_REVIEW,
            reviewReasons = listOf("Personal-name script fallback requires human review"),
            unmatchedSegments = listOf(source),
        )
        return listOf(
            LocalizedName(Language.CHINESE_SIMPLIFIED.code, ChineseScriptNormalizer.toSimplified(source), metadata),
            LocalizedName(Language.CHINESE_TRADITIONAL.code, source, metadata),
        )
    }

    private fun reviewEntry(church: ChurchRecord, names: List<LocalizedName>): ChineseLocalizationReviewEntry? {
        val chinese = names.filter(::isChinese)
        val reviewable = chinese.filter { it.metadata?.reviewStatus == LocalizedNameReviewStatus.NEEDS_REVIEW }
        if (reviewable.isEmpty()) return null
        val metadata = reviewable.mapNotNull(LocalizedName::metadata)
        return ChineseLocalizationReviewEntry(
            churchId = church.id,
            officialNameJa = church.name,
            generatedZhHans = chinese.firstOrNull { Language.fromCode(it.languageCode) == Language.CHINESE_SIMPLIFIED }?.name.orEmpty(),
            generatedZhHant = chinese.firstOrNull { Language.fromCode(it.languageCode) == Language.CHINESE_TRADITIONAL }?.name.orEmpty(),
            confidence = metadata.minOfOrNull(LocalizedNameMetadata::confidence) ?: 0.0,
            reviewReasons = metadata.flatMap(LocalizedNameMetadata::reviewReasons).distinct(),
            matchedDictionaryEntries = metadata.flatMap(LocalizedNameMetadata::matchedDictionaryEntries).distinct(),
            unmatchedSegments = metadata.flatMap(LocalizedNameMetadata::unmatchedSegments).distinct(),
            generationMethods = metadata.map { it.generationMethod.name }.distinct(),
        )
    }

    private fun isProtected(name: LocalizedName): Boolean = name.metadata?.let { metadata ->
        metadata.source in setOf(LocalizedNameSource.MANUAL, LocalizedNameSource.OFFICIAL) ||
            metadata.reviewStatus == LocalizedNameReviewStatus.REVIEWED
    } ?: false

    private fun isChinese(name: LocalizedName): Boolean = Language.fromCode(name.languageCode) in chineseLanguages

    private companion object {
        val chineseLanguages = listOf(Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL)
        val hanOnly = Regex("[\\p{IsHan}・･·ー\\s]+")
    }

    private enum class MergeOutcome { GENERATED, PRESERVED, UNCHANGED }
}
