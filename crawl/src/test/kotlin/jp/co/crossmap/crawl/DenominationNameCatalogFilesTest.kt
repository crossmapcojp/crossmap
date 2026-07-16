package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.Language
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

    private fun resourcesRoot(): Path = sequenceOf(Path.of("resources"), Path.of("../resources"))
        .map { it.toAbsolutePath().normalize() }
        .first { Files.exists(it.resolve("catalog/denominations.json")) }
}
