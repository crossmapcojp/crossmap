package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals
import jp.co.crossmap.supportedLanguageCodes

class SnapshotBuilderTest {
    @Test
    fun geonamesAreUniqueInAllEightLanguageIndexes() {
        val geonamesByLanguage = mapOf(
            "ja" to listOf("大阪", " 大阪 ", "東京"),
            "en" to listOf("Osaka", "osaka", "Ｏｓａｋａ", "Tokyo"),
            "ko" to listOf("오사카", " 오사카 ", "도쿄"),
            "pt" to listOf("Osaka", "OSAKA", "Tóquio"),
            "id" to listOf("Osaka", "osaka", "Tokyo"),
            "vi" to listOf("Osaka", "osaka", "Tokyo"),
            "zh-Hans" to listOf("大阪", " 大阪 ", "东京"),
            "zh-Hant" to listOf("大阪", "大阪", "東京"),
        )

        val unique = geonamesByLanguage.mapValues { (_, names) -> names.distinctGeoNames() }

        assertEquals(listOf("大阪", "東京"), unique.getValue("ja"))
        assertEquals(listOf("Osaka", "Tokyo"), unique.getValue("en"))
        assertEquals(listOf("오사카", "도쿄"), unique.getValue("ko"))
        assertEquals(listOf("Osaka", "Tóquio"), unique.getValue("pt"))
        assertEquals(listOf("Osaka", "Tokyo"), unique.getValue("id"))
        assertEquals(listOf("Osaka", "Tokyo"), unique.getValue("vi"))
        assertEquals(listOf("大阪", "东京"), unique.getValue("zh-Hans"))
        assertEquals(listOf("大阪", "東京"), unique.getValue("zh-Hant"))
        assertEquals(supportedLanguageCodes.toSet(), unique.keys)
    }

    @Test
    fun languageIndexOmitsGeonamesWhoseTranslationIsMissing() {
        val usage = ChurchGeoNameUsage(
            churchId = "google:8998728770320543438",
            title = listOf("布佐"),
            address = listOf("千葉県", "我孫子市"),
        )
        val translations = mapOf(
            "布佐" to ChurchGeoNameTranslation("布佐", mapOf("en" to "Fusa", "ko" to "부사")),
            "千葉県" to ChurchGeoNameTranslation("千葉県", mapOf("en" to "Chiba Prefecture")),
            "我孫子市" to ChurchGeoNameTranslation("我孫子市"),
        )

        assertEquals(listOf("布佐", "千葉県", "我孫子市"), translatedGeoNamesForLanguage(usage, translations, "ja"))
        assertEquals(listOf("Fusa", "Chiba Prefecture"), translatedGeoNamesForLanguage(usage, translations, "en"))
        assertEquals(listOf("부사"), translatedGeoNamesForLanguage(usage, translations, "ko"))
        assertEquals(emptyList(), translatedGeoNamesForLanguage(usage, translations, "pt"))
        assertEquals(emptyList(), translatedGeoNamesForLanguage(usage, translations, "id"))
    }
}
