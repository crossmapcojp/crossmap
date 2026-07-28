package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class ChineseScriptNormalizerTest {
    @Test
    fun canonicalizesTraditionalChurchNameWithoutChangingStoredText() {
        val official = "東京多元文化基督教會"

        assertEquals("东京多元文化基督教会", ChineseScriptNormalizer.toSimplified(official))
        assertEquals("東京多元文化基督教會", official)
    }

    @Test
    fun providesTraditionalFallbackForJapaneseShinjitaiWithoutChangingReviewedStorage() {
        assertEquals("廣島恩惠教會", ChineseScriptNormalizer.toTraditional("広島恩恵教会"))
    }
}
