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

class ChineseLocalizationMigrationTest {
    @Test
    fun preservesReviewedValuesReportsFallbacksAndIsIdempotent() {
        val reviewedHans = localized(
            "zh-Hans",
            "人工审定教会",
            LocalizedNameSource.MANUAL,
            LocalizedNameReviewStatus.REVIEWED,
        )
        val source = church(
            localizedNames = listOf(reviewedHans),
            ministers = listOf(
                ChurchMinister(
                    name = "山田太郎",
                    roleId = "pastor",
                    roleName = "牧師",
                    localizedRoleNames = emptyList(),
                ),
            ),
        )
        val migration = ChineseLocalizationMigration { generatedResult() }

        val first = migration.process(listOf(source))
        val second = migration.process(first.churches)
        val migrated = first.churches.single()

        assertEquals(reviewedHans, migrated.localizedNames.single { it.languageCode == "zh-Hans" })
        assertEquals("東京恩典教會", migrated.localizedNames.single { it.languageCode == "zh-Hant" }.name)
        assertEquals(setOf("zh-Hans", "zh-Hant"), migrated.ministers.single().localizedNames.map { it.languageCode }.toSet())
        assertEquals(1, first.report.namesPreservedBecauseReviewedOrOfficial)
        assertEquals(1, first.report.zhHantNamesGenerated)
        assertEquals(2, first.report.ministersLocalized)
        assertEquals(1, first.report.churchesRequiringReview)
        assertEquals(mapOf("東京" to 2), first.report.unmatchedTokenFrequency)
        assertEquals(first.churches, second.churches)
        assertEquals(0, second.report.indexingChanges)
    }

    @Test
    fun replacesUnreviewedGeneratedValuesWithoutDuplicatingLocales() {
        val stale = localized(
            "zh-Hans",
            "旧自动名称",
            LocalizedNameSource.GENERATED,
            LocalizedNameReviewStatus.NEEDS_REVIEW,
        )

        val migrated = ChineseLocalizationMigration { generatedResult() }
            .process(listOf(church(localizedNames = listOf(stale))))
            .churches.single()

        assertEquals("东京恩典教会", migrated.localizedNames.single { it.languageCode == "zh-Hans" }.name)
        assertEquals(2, migrated.localizedNames.count { it.languageCode.startsWith("zh-") })
    }

    private fun generatedResult() = LocalizedChurchNameResult(
        japaneseName = "東京恵み教会",
        latinName = "Tokyo Grace Church",
        localizedNames = listOf(
            generated("zh-Hans", "东京恩典教会"),
            generated("zh-Hant", "東京恩典教會"),
        ),
        pattern = ChurchNamePattern.SINGLE_NAME,
        components = emptyList(),
    )

    private fun generated(language: String, name: String) = LocalizedName(
        language,
        name,
        LocalizedNameMetadata(
            source = LocalizedNameSource.GENERATED,
            generationMethod = LocalizedNameGenerationMethod.SCRIPT_CONVERSION,
            confidence = 0.65,
            reviewStatus = LocalizedNameReviewStatus.NEEDS_REVIEW,
            reviewReasons = listOf("fallback"),
            matchedDictionaryEntries = listOf("教会"),
            unmatchedSegments = listOf("東京"),
        ),
    )

    private fun localized(
        language: String,
        name: String,
        source: LocalizedNameSource,
        reviewStatus: LocalizedNameReviewStatus,
    ) = LocalizedName(
        language,
        name,
        LocalizedNameMetadata(
            source = source,
            generationMethod = LocalizedNameGenerationMethod.EXACT_OVERRIDE,
            confidence = 1.0,
            reviewStatus = reviewStatus,
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
