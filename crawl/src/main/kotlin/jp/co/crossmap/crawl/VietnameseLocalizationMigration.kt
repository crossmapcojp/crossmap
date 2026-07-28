package jp.co.crossmap.crawl

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
data class VietnameseLocalizationReviewEntry(
    val churchId: String,
    val officialNameJa: String,
    val generatedVi: String,
    val confidence: Double,
    val reviewReasons: List<String>,
    val matchedDictionaryEntries: List<String>,
    val unmatchedSegments: List<String>,
)

@Serializable
data class VietnameseLocalizationMigrationReport(
    val churchesProcessed: Int,
    val viNamesGenerated: Int,
    val namesPreservedBecauseReviewedOrOfficial: Int,
    val ministersLocalized: Int,
    val churchesRequiringReview: Int,
    val indexingChanges: Int,
    val dictionaryCoverageRate: Double,
    val unmatchedTokenFrequency: Map<String, Int>,
    val reviewEntries: List<VietnameseLocalizationReviewEntry>,
)

data class VietnameseLocalizationMigrationResult(
    val churches: List<ChurchRecord>,
    val report: VietnameseLocalizationMigrationReport,
)

/** Deterministic, idempotent reprocessing of stored Vietnamese church and minister names. */
class VietnameseLocalizationMigration(
    private val localize: (ChurchRecord) -> LocalizedChurchNameResult,
) {
    constructor(localizer: MultilingualChurchNameLocalizer) : this(
        { church -> localizer.localize(church.name, church.titleLanguages, church.address) },
    )

    fun process(churches: List<ChurchRecord>): VietnameseLocalizationMigrationResult {
        var generatedCount = 0
        var preservedCount = 0
        var ministersLocalized = 0
        var indexingChanges = 0
        var matchedSegments = 0
        var totalSegments = 0
        val unmatchedFrequency = mutableMapOf<String, Int>()
        val reviewEntries = mutableListOf<VietnameseLocalizationReviewEntry>()
        val migrated = churches.map { church ->
            val candidateWithDiagnostics = localize(church).localizedNames
                .firstOrNull { Language.fromCode(it.languageCode) == Language.VIETNAMESE }
            candidateWithDiagnostics?.metadata?.let { metadata ->
                matchedSegments += metadata.matchedDictionaryEntries.size
                totalSegments += metadata.matchedDictionaryEntries.size + metadata.unmatchedSegments.size
                metadata.unmatchedSegments.forEach { unmatchedFrequency.merge(it, 1, Int::plus) }
            }
            candidateWithDiagnostics?.toReviewEntry(church)?.let(reviewEntries::add)
            val mergedChurchNames = merge(
                church.localizedNames,
                candidateWithDiagnostics?.withoutReviewDiagnostics(),
            ) { outcome ->
                when (outcome) {
                    MergeOutcome.GENERATED -> generatedCount++
                    MergeOutcome.PRESERVED -> preservedCount++
                    MergeOutcome.UNCHANGED -> Unit
                }
            }
            val migratedMinisters = church.ministers.map { minister ->
                val merged = merge(minister.localizedNames, personalNameFallback(minister)) { outcome ->
                    when (outcome) {
                        MergeOutcome.GENERATED -> ministersLocalized++
                        MergeOutcome.PRESERVED -> preservedCount++
                        MergeOutcome.UNCHANGED -> Unit
                    }
                }
                minister.copy(localizedNames = merged)
            }
            val updated = church.copy(localizedNames = mergedChurchNames, ministers = migratedMinisters)
            if (updated != church) indexingChanges++
            updated
        }
        return VietnameseLocalizationMigrationResult(
            churches = migrated,
            report = VietnameseLocalizationMigrationReport(
                churchesProcessed = churches.size,
                viNamesGenerated = generatedCount,
                namesPreservedBecauseReviewedOrOfficial = preservedCount,
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

    private fun merge(
        existing: List<LocalizedName>,
        candidate: LocalizedName?,
        observed: (MergeOutcome) -> Unit,
    ): List<LocalizedName> {
        if (candidate == null) return existing
        val sameLanguage = existing.filter { Language.fromCode(it.languageCode) == Language.VIETNAMESE }
        return when {
            sameLanguage.any(::isProtected) -> existing.also { observed(MergeOutcome.PRESERVED) }
            sameLanguage.size == 1 && sameLanguage.single() == candidate -> existing.also { observed(MergeOutcome.UNCHANGED) }
            else -> (existing.filterNot { Language.fromCode(it.languageCode) == Language.VIETNAMESE } + candidate)
                .also { observed(MergeOutcome.GENERATED) }
        }
    }

    private fun personalNameFallback(minister: ChurchMinister): LocalizedName? {
        val source = minister.name.trim()
        if (source.isBlank()) return null
        return LocalizedName(
            Language.VIETNAMESE.code,
            source,
            LocalizedNameMetadata(
                source = LocalizedNameSource.GENERATED,
                generationMethod = LocalizedNameGenerationMethod.ORIGINAL_FALLBACK,
                confidence = 0.5,
                reviewStatus = LocalizedNameReviewStatus.NEEDS_REVIEW,
            ),
        )
    }

    private fun LocalizedName.toReviewEntry(church: ChurchRecord): VietnameseLocalizationReviewEntry? {
        val metadata = metadata ?: return null
        if (metadata.reviewStatus != LocalizedNameReviewStatus.NEEDS_REVIEW) return null
        return VietnameseLocalizationReviewEntry(
            churchId = church.id,
            officialNameJa = church.name,
            generatedVi = name,
            confidence = metadata.confidence,
            reviewReasons = metadata.reviewReasons,
            matchedDictionaryEntries = metadata.matchedDictionaryEntries,
            unmatchedSegments = metadata.unmatchedSegments,
        )
    }

    private fun LocalizedName.withoutReviewDiagnostics(): LocalizedName = copy(
        metadata = metadata?.copy(
            reviewReasons = emptyList(),
            matchedDictionaryEntries = emptyList(),
            unmatchedSegments = emptyList(),
        ),
    )

    private fun isProtected(name: LocalizedName): Boolean = name.metadata?.let { metadata ->
        metadata.source in setOf(LocalizedNameSource.MANUAL, LocalizedNameSource.OFFICIAL) ||
            metadata.reviewStatus == LocalizedNameReviewStatus.REVIEWED
    } ?: false

    private enum class MergeOutcome { GENERATED, PRESERVED, UNCHANGED }
}
