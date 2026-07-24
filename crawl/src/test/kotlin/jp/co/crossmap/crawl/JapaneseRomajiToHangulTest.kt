package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JapaneseRomajiToHangulTest {
    @Test
    fun transliteratesJapaneseGeonames() {
        mapOf(
            "Kanuki" to "가누키",
            "Kamikanuki" to "가미카누키",
            "Shimokanuki" to "시모카누키",
            "Setagaya" to "세타가야",
            "Tokyo" to "도쿄",
            "Zenkaiminamimachi" to "젠카이미나미마치",
            "Kyoto" to "교토",
            "Shinjuku" to "신주쿠",
            "Chiba" to "지바",
            "Tsukuba" to "쓰쿠바",
            "Fuji" to "후지",
            "Ryukyu" to "류큐",
            "Asuka" to "아스카",
            "Kasuga" to "가스가",
            "Susukino" to "스스키노",
        ).forEach { (romaji, expected) -> assertEquals(expected, romajiToHangul(romaji), romaji) }
    }

    @Test
    fun transliteratesJapanesePersonalNamesWithTheSameRules() {
        mapOf(
            "Satou" to "사토",
            "Tarou" to "다로",
            "Yamada" to "야마다",
            "Hanako" to "하나코",
            "Suzuki" to "스즈키",
            "Takahashi" to "다카하시",
            "Nakamura" to "나카무라",
            "Kobayashi" to "고바야시",
            "Watanabe" to "와타나베",
            "Yamamoto" to "야마모토",
        ).forEach { (romaji, expected) -> assertEquals(expected, romajiToHangul(romaji), romaji) }
    }

    @Test
    fun normalizesCaseDiacriticsAndWhitespace() {
        assertEquals("도쿄", romajiToHangul("  Tōkyō  "))
        assertEquals("사토 다로", romajiToHangul("SATOU   TAROU"))
    }

    @Test
    fun acceptsTenseSsangSiotForJapaneseSuAndTsu() {
        assertTrue(JapaneseRomajiToHangul.hasCompatibleInitial("Susukino", "스스키노"))
        assertTrue(JapaneseRomajiToHangul.hasCompatibleInitial("Tsukunocho", "쓰쿠노초"))
    }

    @Test
    fun rejectsValuesWithoutAConvertibleLatinReading() {
        assertNull(romajiToHangul(""))
        assertNull(romajiToHangul("   "))
        assertNull(romajiToHangul("東京"))
        assertNull(romajiToHangul("교회"))
    }
}
