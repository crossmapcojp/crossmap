package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.Language
import jp.co.crossmap.ChurchTradition
import jp.co.crossmap.DenominationNameMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DenominationNameCatalogFilesTest {
    @Test
    fun all185DenominationsHaveNamesInAllFiveSupportedLanguages() {
        val resources = resourcesRoot()
        val denominations = Json { ignoreUnknownKeys = true }.decodeFromString<List<Denomination>>(
            Files.readString(resources.resolve("catalog/denominations.json")),
        )
        val expectedIds = denominations
            .map(Denomination::id)
            .filterNot { it == "NOT_DETERMINED" || it == "INDEPENDENT_CHURCH" }
            .toSet()
        val catalogs = DenominationNameCatalogFiles.load(resources)

        assertEquals(185, expectedIds.size)
        assertTrue(expectedIds.none { it.startsWith("XLSX_") }, "Spreadsheet row hashes must not be public IDs")
        assertTrue(
            expectedIds.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) },
            "Denomination IDs must be stable, readable uppercase abbreviations",
        )
        assertEquals(Language.entries.toSet(), catalogs.keys)
        Language.entries.forEach { language ->
            val names = catalogs.getValue(language)
            assertEquals(expectedIds, names.keys, "${language.code} denomination IDs")
            assertEquals(185, names.size, "${language.code} denomination count")
            assertTrue(names.values.all(String::isNotBlank), "${language.code} contains a blank name")
        }
        assertEquals(185 * 5, catalogs.values.sumOf(Map<*, *>::size))
        assertTrue(
            catalogs.getValue(Language.KOREAN).values.all { it.any { character -> character in '\uac00'..'\ud7a3' } },
            "Every Korean denomination name must contain Hangul",
        )
        assertTrue(
            catalogs.getValue(Language.KOREAN).values.none { it.any { character -> character in 'A'..'Z' || character in 'a'..'z' } },
            "Korean denomination names must not contain Latin alphabet fragments",
        )
        assertFalse(Files.exists(resources.resolve("catalog/denomination-english-names.json")))
    }

    @Test
    fun everyDenominationLanguageRecordsProvenanceAndSeparateTraditionMetadata() {
        val reviewed = DenominationNameCatalogFiles.loadReviewed(resourcesRoot())

        assertEquals(185, reviewed.size)
        assertEquals(185 * Language.entries.size, reviewed.values.sumOf { it.evidence.size })
        reviewed.values.forEach { denomination ->
            assertEquals(Language.entries.toSet(), denomination.evidence.keys, denomination.id)
            denomination.evidence.values.forEach { evidence ->
                if (evidence.method == DenominationNameMethod.OFFICIAL_WEBSITE) {
                    assertTrue(evidence.sourceUrl?.startsWith("http") == true, denomination.id)
                }
                if (evidence.method == DenominationNameMethod.ESTABLISHED_USAGE) {
                    assertTrue(!evidence.sourceUrl.isNullOrBlank() || !evidence.note.isNullOrBlank(), denomination.id)
                }
            }
        }

        val jelc = reviewed.getValue("JELC")
        assertEquals(ChurchTradition.LUTHERAN, jelc.tradition)
        assertEquals("Japan Evangelical Lutheran Church", jelc.name(Language.ENGLISH))
        assertEquals("일본 복음 루터", jelc.namePart(Language.KOREAN))
        assertEquals(DenominationNameMethod.OFFICIAL_WEBSITE, jelc.evidence(Language.ENGLISH).method)

        val uccj = reviewed.getValue("UCCJ")
        assertEquals(null, uccj.tradition, "A united denomination must not be conflated with one tradition")
        assertEquals("United Church of Christ in Japan", uccj.name(Language.ENGLISH))

        mapOf(
            "CATHOLIC_JP" to "Catholic Church in Japan",
            "ANGLICAN_JP" to "Nippon Sei Ko Kai (Anglican Church in Japan)",
            "JAG" to "Japan Assemblies of God",
            "SDA_JP" to "Seventh-day Adventist Church",
            "COTN_JP" to "The Japan Church of the Nazarene",
            "ORTHODOX_JP" to "The Orthodox Church in Japan",
            "SA_JP" to "The Salvation Army",
        ).forEach { (id, officialEnglishName) ->
            val denomination = reviewed.getValue(id)
            assertEquals(officialEnglishName, denomination.name(Language.ENGLISH), id)
            assertEquals(DenominationNameMethod.OFFICIAL_WEBSITE, denomination.evidence(Language.ENGLISH).method, id)
        }
    }

    private fun resourcesRoot(): Path = sequenceOf(Path.of("resources"), Path.of("../resources"))
        .map { it.toAbsolutePath().normalize() }
        .first { Files.exists(it.resolve("catalog/denominations.json")) }
}
