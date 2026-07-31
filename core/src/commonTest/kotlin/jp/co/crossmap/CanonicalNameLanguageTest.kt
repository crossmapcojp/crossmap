package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalNameLanguageTest {
    @Test
    fun mapsEverySupportedLanguageReversibly() {
        val expected = listOf(
            Triple("ja", "ja", "name_ja"),
            Triple("en", "en", "name_en"),
            Triple("ko", "ko", "name_ko"),
            Triple("pt", "pt", "name_pt"),
            Triple("id", "id", "name_id"),
            Triple("vi", "vi", "name_vi"),
            Triple("zh-Hans", "zh_Hans", "name_zh_Hans"),
            Triple("zh-Hant", "zh_Hant", "name_zh_Hant"),
        )

        assertEquals(expected, CanonicalNameLanguage.entries.map {
            Triple(it.languageTag, it.neo4jPropertySuffix, it.neo4jNameProperty)
        })
        CanonicalNameLanguage.entries.forEach { language ->
            assertEquals(language, CanonicalNameLanguage.fromLanguageTag(language.languageTag))
            assertEquals(language, CanonicalNameLanguage.fromNeo4jNameProperty(language.neo4jNameProperty))
        }
    }

    @Test
    fun rejectsMalformedOrUnsupportedTagsAndProperties() {
        listOf("", "zh", "zh-hans", "zh_Hans", "en-US").forEach { tag ->
            assertFailsWith<IllegalArgumentException> { CanonicalNameLanguage.fromLanguageTag(tag) }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalNameLanguage.fromNeo4jNameProperty("name_zh_hans")
        }
    }

    @Test
    fun convertsNamesWithoutBlanksOrChineseScriptCollapse() {
        val names = listOf(
            LocalizedName("zh-Hant", "繁體"),
            LocalizedName("en", "  English  "),
            LocalizedName("zh-Hans", "简体"),
            LocalizedName("ja", " "),
            LocalizedName("en", "Another"),
        )

        val canonical = names.toCanonicalNameMap()
        assertEquals(
            mapOf("en" to "Another", "zh-Hans" to "简体", "zh-Hant" to "繁體"),
            canonical,
        )
        val properties = canonical.toNeo4jNameProperties()
        assertEquals("简体", properties["name_zh_Hans"])
        assertEquals("繁體", properties["name_zh_Hant"])
        assertEquals(canonical.toCanonicalLocalizedNames(), properties.neo4jNamePropertiesToLocalizedNames())
    }
}
