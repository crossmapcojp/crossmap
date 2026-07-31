package jp.co.crossmap.catalog.export

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.catalog.neo4j.StaticChurchCatalogSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class CatalogLogicalExporterTest {
    @Test
    fun writesGeneratedProjectionWithNeighboringRevisionManifest() {
        val directory = Files.createTempDirectory("catalog-logical-export")
        try {
            val output = directory.resolve("churches.json")
            val result = CatalogLogicalExporter().write(
                StaticChurchCatalogSnapshot(
                    churches = listOf(
                        ChurchRecord(
                            id = "church:1",
                            name = "日本語名",
                            englishName = "English Name",
                            address = "東京都",
                            location = GeoPoint(35.0, 139.0),
                            websiteUrl = "",
                        ),
                    ),
                    sourceChecksum = "source",
                    catalogRevision = "revision-7",
                    catalogRevisionSequence = 7,
                    catalogContentHash = "logical-hash",
                ),
                output,
            )

            val manifest = Json.decodeFromString<CatalogLogicalExportManifest>(Files.readString(result.manifest))
            assertEquals("revision-7", manifest.catalogRevisionId)
            assertEquals(7, manifest.catalogRevisionSequence)
            assertEquals("logical-hash", manifest.catalogContentHash)
            assertEquals(1, manifest.churchCount)
            assertTrue(manifest.fileSha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(output.resolveSibling("churches.json.manifest.json"), result.manifest)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
