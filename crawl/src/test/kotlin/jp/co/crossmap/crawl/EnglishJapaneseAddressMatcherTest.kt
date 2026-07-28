package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnglishJapaneseAddressMatcherTest {
    @Test
    fun matchesRealJcobRomanizedAndJapaneseAddresses() {
        assertEquals(
            1.0,
            EnglishJapaneseAddressMatcher.similarity(
                "143-5 Sunashinden, Kawagoe-shi, Saitama-ken 350-1133",
                "〒350-1133 埼玉県川越市砂新田143-5",
            ),
            absoluteTolerance = 0.000_001,
        )
        assertTrue(
            EnglishJapaneseAddressMatcher.compareEnglishJapaneseAddress(
                "Chiba Heights 2-24-24 Kita-Koshigaya, Koshigaya-shi, Saitama-ken 343-0026",
                "〒343-0026 埼玉県越谷市北越谷2丁目24-24",
            ),
        )
    }

    @Test
    fun rejectsDifferentPostalCodesEvenWhenThePrefectureAndStreetNumbersMatch() {
        assertFalse(
            EnglishJapaneseAddressMatcher.compareEnglishJapaneseAddress(
                "143-5 Sunashinden, Kawagoe-shi, Saitama-ken 350-1133",
                "〒350-0001 埼玉県川越市砂新田143-5",
            ),
        )
    }
}
