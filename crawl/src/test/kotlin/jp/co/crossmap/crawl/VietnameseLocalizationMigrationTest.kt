package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameGenerationMethod
import jp.co.crossmap.LocalizedNameMetadata
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VietnameseLocalizationMigrationTest {
    @Test
    fun preservesReviewedValuesLocalizesMinistersAndIsIdempotent() {
        val reviewed = localized("Hội Thánh Ân Điển Tokyo", LocalizedNameSource.MANUAL, LocalizedNameReviewStatus.REVIEWED)
        val source = church(
            localizedNames = listOf(reviewed),
            ministers = listOf(
                ChurchMinister(
                    name = "Nguyễn Văn An",
                    roleId = "pastor",
                    roleName = "牧師",
                    localizedRoleNames = emptyList(),
                ),
            ),
        )
        val migration = VietnameseLocalizationMigration { generatedResult() }

        val first = migration.process(listOf(source))
        val second = migration.process(first.churches)
        val migrated = first.churches.single()

        assertEquals(reviewed, migrated.localizedNames.single { it.languageCode == "vi" })
        assertEquals("Nguyễn Văn An", migrated.ministers.single().localizedNames.single { it.languageCode == "vi" }.name)
        assertEquals(1, first.report.namesPreservedBecauseReviewedOrOfficial)
        assertEquals(1, first.report.ministersLocalized)
        assertEquals(first.churches, second.churches)
        assertEquals(0, second.report.indexingChanges)
    }

    @Test
    fun replacesGeneratedVietnameseAndKeepsDiagnosticsInReportOnly() {
        val stale = localized("Tên cũ", LocalizedNameSource.GENERATED, LocalizedNameReviewStatus.NEEDS_REVIEW)
        val result = VietnameseLocalizationMigration { generatedResult() }.process(listOf(church(listOf(stale))))
        val generated = result.churches.single().localizedNames.single { it.languageCode == "vi" }

        assertEquals("Hội Thánh Ân Điển Tokyo", generated.name)
        assertEquals(1, result.report.viNamesGenerated)
        assertEquals(listOf("Tokyo"), result.report.reviewEntries.single().unmatchedSegments)
        assertTrue(generated.metadata!!.reviewReasons.isEmpty())
        assertTrue(generated.metadata!!.matchedDictionaryEntries.isEmpty())
        assertTrue(generated.metadata!!.unmatchedSegments.isEmpty())
    }

    private fun generatedResult() = LocalizedChurchNameResult(
        japaneseName = "東京恵み教会",
        latinName = "Tokyo Grace Church",
        localizedNames = listOf(
            LocalizedName(
                "vi",
                "Hội Thánh Ân Điển Tokyo",
                LocalizedNameMetadata(
                    source = LocalizedNameSource.GENERATED,
                    generationMethod = LocalizedNameGenerationMethod.ORIGINAL_FALLBACK,
                    confidence = 0.6,
                    reviewStatus = LocalizedNameReviewStatus.NEEDS_REVIEW,
                    reviewReasons = listOf("fallback"),
                    matchedDictionaryEntries = listOf("恵み", "教会"),
                    unmatchedSegments = listOf("Tokyo"),
                ),
            ),
        ),
        pattern = ChurchNamePattern.SINGLE_NAME,
        components = emptyList(),
    )

    private fun localized(name: String, source: LocalizedNameSource, review: LocalizedNameReviewStatus) = LocalizedName(
        "vi",
        name,
        LocalizedNameMetadata(
            source = source,
            generationMethod = LocalizedNameGenerationMethod.EXACT_OVERRIDE,
            confidence = 1.0,
            reviewStatus = review,
        ),
    )

    private fun church(
        localizedNames: List<LocalizedName>,
        ministers: List<ChurchMinister> = emptyList(),
    ) = ChurchRecord(
        id = "google:fixture",
        name = "東京恵み教会",
        englishName = "Tokyo Grace Church",
        localizedNames = localizedNames,
        address = "東京都",
        location = GeoPoint(35.0, 139.0),
        websiteUrl = "https://example.org/",
        ministers = ministers,
    )
}
