package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryLanguageDetectorTest {
    @Test
    fun detectsTranslatedAddressGeonamesWithoutUiLanguageHint() {
        assertEquals("pt", QueryLanguageDetector.detect("Distrito de Minato"))
        assertEquals("id", QueryLanguageDetector.detect("Distrik Minato"))
        assertEquals("en", QueryLanguageDetector.detect("Minato City"))
        assertEquals("ko", QueryLanguageDetector.detect("미나토구"))
        assertEquals("ja", QueryLanguageDetector.detect("港区"))
        assertEquals("vi", QueryLanguageDetector.detect("Hội Thánh Tin Lành"))
        assertEquals("vi", QueryLanguageDetector.detect("Giáo hội Hiệp nhất Đấng Christ tại Nhật Bản"))
    }

    @Test
    fun usesTheSelectedChineseScriptForAmbiguousHanQueries() {
        assertEquals("zh-Hans", QueryLanguageDetector.detect("日本圣公会", "zh-CN"))
        assertEquals("zh-Hant", QueryLanguageDetector.detect("日本聖公會", "zh-Hant"))
        assertEquals("zh-Hant", QueryLanguageDetector.detect("东京教会", "zh-TW"))
        assertEquals("ja", QueryLanguageDetector.detect("東京教会", "ja"))
    }

    @Test
    fun keepsAmbiguousLatinQueriesOnVietnameseWhenSelected() {
        assertEquals("vi", QueryLanguageDetector.detect("Tokyo", "vi-VN"))
    }
}
