package jp.co.crossmap.crawl

import java.nio.file.Files
import java.time.LocalDateTime
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmComposedNameDetailLogTest {
    @Test
    fun listsOrderedChildPartsAndTheirTranslationMethods() {
        val denomination = Denomination("JELC", "日本福音ルーテル教会", listOf("日本福音ルーテル"))
        val church = ChurchEnglishNameInput(
            id = "detail:jelc-shonan-samiru",
            name = "日本福音ルーテル湘南静岡サミル未知教会",
            denominationId = "JELC",
            address = "静岡県",
            location = GeoPoint(34.9, 138.3),
            websiteUrl = "https://jelc.or.jp/",
        )
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = listOf(denomination),
            geonames = mapOf("湘南" to "Shonan", "静岡" to "Shizuoka"),
            concepts = mapOf("サミル" to "Samiru"),
            romanize = { null },
            dictionaryEntries = setOf("湘南", "サミル"),
        )
        val resolution = ResolvedChurchEnglishName(
            englishName = "JELC Shonan Shizuoka Samiru Michi Church",
            source = DeterminationSource.LLM,
            confidence = 0.94f,
            evidence = listOf("component completion"),
            model = CAT_TRANSLATE_MODEL,
            parts = listOf(
                TranslatedChurchNamePart("湘南", ChurchNamePartRole.GEONAME, "Shonan"),
                TranslatedChurchNamePart("静岡", ChurchNamePartRole.GEONAME, "Shizuoka"),
                TranslatedChurchNamePart("サミル", ChurchNamePartRole.CONCEPTUAL_NAME, "Samiru"),
                TranslatedChurchNamePart("未知", ChurchNamePartRole.OTHER, "Michi"),
                TranslatedChurchNamePart("教会", ChurchNamePartRole.CONGREGATION, "Church"),
            ),
        )

        val details = buildLlmComposedNameDetails(
            inputs = listOf(church),
            resolutions = mapOf(church.id to resolution),
            analyzer = analyzer,
            denominations = listOf(denomination),
            denominationEnglishNames = mapOf("JELC" to "jelc"),
            conceptDictionaryKeys = setOf("サミル"),
            specialGeonameDictionaryKeys = setOf("湘南"),
            knownGeonames = setOf("湘南", "静岡"),
        )

        assertEquals(
            listOf(
                NamePartTranslationMethod.DENOMINATION_DATA,
                NamePartTranslationMethod.GEONAME_DICTIONARY,
                NamePartTranslationMethod.GEONAME_DATA,
                NamePartTranslationMethod.CONCEPT_DICTIONARY,
                NamePartTranslationMethod.LLM,
                NamePartTranslationMethod.CONGREGATION_DATA,
            ),
            details.single().parts.map(LlmComposedNamePartDetail::translationMethod),
        )
        assertEquals("JELC", details.single().parts.first().english)
        val rendered = renderLlmComposedNameDetails(details)
        assertTrue(rendered.contains("japanese_name=日本福音ルーテル湘南静岡サミル未知教会"))
        assertTrue(rendered.contains("  type=DENOMINATION"))
        assertTrue(rendered.contains("  japanese=未知"))
        assertTrue(rendered.contains("  translation_method=llm"))
    }

    @Test
    fun writesTimestampedLogThroughLogback() {
        val logs = Files.createTempDirectory("crossmap-llm-name-detail")
        try {
            val now = LocalDateTime.of(2026, 7, 15, 17, 30)
            val first = writeLlmComposedNameDetailLog(emptyList(), logs, now)
            assertEquals("2026-07-15-17-30-llm-composed-name-detail.log", first.fileName.toString())
            assertEquals("llm_composed_names=0\n", Files.readString(first))
        } finally {
            logs.toFile().deleteRecursively()
        }
    }
}
