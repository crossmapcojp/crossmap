package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmEntitySimilarityTest {
    @Test
    fun normalizesJapaneseAddressVariantsBeforeLlmFallback() {
        val first = "〒160-0022 東京都新宿区新宿3丁目1番2号"
        val second = "東京都新宿区新宿３-１-２"

        assertEquals(JapaneseEntityNormalizer.address(first), JapaneseEntityNormalizer.address(second))
        assertEquals(1f, JapaneseEntityNormalizer.deterministicAddressScore(first, second))
        assertTrue(JapaneseEntityNormalizer.deterministicAddressScore(first, "大阪府大阪市北区") < 0.3f)
    }

    @Test
    fun findsFuzzyChurchNameAndAddressPairsWithSharedJapaneseCharacters() {
        val name = JapaneseEntityNormalizer.deterministicNameScore("日本聖公会東京聖アンデレ教会", "東京聖アンドレ教会")
        val address = JapaneseEntityNormalizer.deterministicAddressScore("東京都新宿区西新宿2丁目8番1号", "新宿区西新宿２－８－１")
        val entity = JapaneseEntityNormalizer.deterministicEntityScore(
            "日本聖公会東京聖アンデレ教会", "〒105-0011 東京都港区芝公園３丁目６−１８",
            "東京聖アンドレ教会", "港区芝公園3-6-18",
        )

        assertTrue(name > 0.45f)
        assertTrue(address > 0.70f)
        assertTrue(entity > 0.55f)
    }

    @Test
    fun precomputedNormalizedScoresAreEquivalentToDirectScores() {
        val leftName = "日本聖公会東京聖アンデレ教会"
        val rightName = "東京聖アンドレ教会"
        val leftAddress = "〒105-0011 東京都港区芝公園３丁目６−１８"
        val rightAddress = "港区芝公園3-6-18"
        val nameScore = JapaneseEntityNormalizer.deterministicNormalizedNameScore(
            JapaneseEntityNormalizer.name(leftName),
            JapaneseEntityNormalizer.name(rightName),
        )
        val addressScore = JapaneseEntityNormalizer.deterministicNormalizedAddressScore(
            JapaneseEntityNormalizer.address(leftAddress),
            JapaneseEntityNormalizer.address(rightAddress),
        )

        assertEquals(JapaneseEntityNormalizer.deterministicNameScore(leftName, rightName), nameScore)
        assertEquals(JapaneseEntityNormalizer.deterministicAddressScore(leftAddress, rightAddress), addressScore)
        assertEquals(
            JapaneseEntityNormalizer.deterministicEntityScore(leftName, leftAddress, rightName, rightAddress),
            JapaneseEntityNormalizer.deterministicEntityScore(nameScore, addressScore),
        )
    }
}
