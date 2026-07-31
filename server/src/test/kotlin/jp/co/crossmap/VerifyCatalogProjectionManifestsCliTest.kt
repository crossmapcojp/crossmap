package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VerifyCatalogProjectionManifestsCliTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun acceptsMatchingPinnedCatalogRevisionAndHash() {
        val (search, pages) = manifests("revision-3", "revision-3")
        VerifyCatalogProjectionManifestsCli.main(arrayOf(search.toString(), pages.toString()))
    }

    @Test
    fun rejectsMixedCatalogRevisions() {
        val (search, pages) = manifests("revision-3", "revision-4")
        assertFailsWith<IllegalStateException> {
            VerifyCatalogProjectionManifestsCli.main(arrayOf(search.toString(), pages.toString()))
        }
    }

    private fun manifests(searchRevision: String, pageRevision: String) =
        Files.createTempDirectory("projection-manifests").let { directory ->
            val search = directory.resolve("latest.json")
            val pages = directory.resolve("church-pages-manifest.json")
            Files.writeString(
                search,
                json.encodeToString(
                    IndexManifest(
                        indexVersion = "test",
                        luceneVersion = "test",
                        createdAt = "2026-07-31T00:00:00Z",
                        documentCount = 1,
                        sourceSha256 = "logical-hash",
                        catalogRevision = searchRevision,
                        catalogContentHash = "logical-hash",
                    ),
                ),
            )
            Files.writeString(
                pages,
                json.encodeToString(
                    ChurchPageManifest(
                        sourceSha256 = "logical-hash",
                        catalogRevision = pageRevision,
                        catalogContentHash = "logical-hash",
                        pages = mapOf("church:1" to "church-1.html"),
                    ),
                ),
            )
            search to pages
        }
}
