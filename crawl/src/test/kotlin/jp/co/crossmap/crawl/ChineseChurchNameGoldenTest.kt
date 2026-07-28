package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChineseScriptNormalizer
import jp.co.crossmap.LocalizedNameReviewStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ChineseChurchNameGoldenTest {
    private val resourcesRoot = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        .resolve("resources")
    private val localizer = MultilingualChurchNameLocalizer(
        dictionaries = ChurchNameEnglishDictionary.load(resourcesRoot),
        congregationTerms = CongregationTermDictionary.load(resourcesRoot),
        denominations = emptyList(),
        geonames = mapOf("東京" to "Tokyo", "江戸" to "Edo"),
        multilingualGeonames = mapOf(
            "東京" to mapOf("zh-Hans" to "东京", "zh-Hant" to "東京"),
            "江戸" to mapOf("zh-Hans" to "江户", "zh-Hant" to "江戶"),
        ),
    )

    @Test
    fun representativeChurchNamePatternsMatchTheReviewedGoldenSet() {
        val expected = listOf(
            golden("東京バプテスト教会", "东京浸信会教会", "東京浸信會教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "東京", "バプテスト", "教会"),
            golden("恵み教会", "恩典教会", "恩典教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "恵み", "教会"),
            golden("福音教会", "福音教会", "福音教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "福音", "教会"),
            golden("聖書教会", "圣经教会", "聖經教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "聖書", "教会"),
            golden("日本キリスト教会", "日本基督教会", "日本基督教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "キリスト", "教会"),
            golden("希望チャペル", "盼望礼拜堂", "盼望禮拜堂", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "希望", "チャペル"),
            golden("宣教センター", "宣教センター", "宣教センター", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "宣教"),
            golden("東京カトリック教会", "东京カトリック教会", "東京カトリック教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "東京", "教会"),
            golden("東京ルーテル教会", "东京ルーテル教会", "東京ルーテル教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "東京", "教会"),
            golden("東京ペンテコステ教会", "东京ペンテコステ教会", "東京ペンテコステ教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "東京", "教会"),
            golden("独立福音教会", "独立福音教会", "独立福音教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "福音", "教会"),
            golden("在日大韓基督教会", "在日大韓基督教会", "在日大韓基督教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "教会"),
            golden("東京華人教会", "东京華人教会", "東京華人教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "東京", "教会"),
            golden("山田太郎記念教会", "山田太郎記念教会", "山田太郎記念教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "教会"),
            golden("江戸福音教会", "江户福音教会", "江戶福音教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "江戸", "福音", "教会"),
            golden("ニューライフ教会", "ニューライフ教会", "ニューライフ教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "教会"),
            golden("NEW GRACE 教会", "ニューグレイス教会", "ニューグレイス教會", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "教会"),
            golden("東京聖書バプテスト教会", "东京圣经浸信会教会", "東京聖經浸信會教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "東京", "聖書", "バプテスト", "教会"),
            golden("恵み福音キリスト教会", "恩典福音基督教会", "恩典福音基督教會", 0.9, LocalizedNameReviewStatus.UNREVIEWED, "恵み", "福音", "キリスト", "教会"),
            golden("東京チャペルセンター", "东京礼拜堂センター", "東京禮拜堂センター", 0.6, LocalizedNameReviewStatus.NEEDS_REVIEW, "東京", "チャペル"),
        )
        val actual = expected.map { fixture ->
            val source = fixture.source
            val result = localizer.localize(source)
            val hans = result.localizedNames.single { it.languageCode == "zh-Hans" }
            val hant = result.localizedNames.single { it.languageCode == "zh-Hant" }
            Golden(
                source = source,
                zhHans = hans.name,
                zhHant = hant.name,
                canonical = ChineseScriptNormalizer.toSimplified(hant.name),
                confidence = minOf(hans.metadata?.confidence ?: 0.0, hant.metadata?.confidence ?: 0.0),
                reviewStatus = if (
                    hans.metadata?.reviewStatus == LocalizedNameReviewStatus.NEEDS_REVIEW ||
                    hant.metadata?.reviewStatus == LocalizedNameReviewStatus.NEEDS_REVIEW
                ) LocalizedNameReviewStatus.NEEDS_REVIEW else hans.metadata?.reviewStatus,
                matchedRules = (hans.metadata?.matchedDictionaryEntries.orEmpty() +
                    hant.metadata?.matchedDictionaryEntries.orEmpty()).distinct(),
            )
        }

        assertEquals(expected, actual)
    }

    private fun golden(
        source: String,
        zhHans: String,
        zhHant: String,
        confidence: Double,
        reviewStatus: LocalizedNameReviewStatus,
        vararg matchedRules: String,
    ) = Golden(
        source = source,
        zhHans = zhHans,
        zhHant = zhHant,
        canonical = ChineseScriptNormalizer.toSimplified(zhHant),
        confidence = confidence,
        reviewStatus = reviewStatus,
        matchedRules = matchedRules.toList(),
    )

    private data class Golden(
        val source: String,
        val zhHans: String,
        val zhHant: String,
        val canonical: String,
        val confidence: Double,
        val reviewStatus: LocalizedNameReviewStatus?,
        val matchedRules: List<String>,
    )
}
