package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object VerifyCatalogProjectionManifestsCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: <search-manifest.json> <church-page-manifest.json>" }
        val searchPath = Path.of(args[0])
        val pagePath = Path.of(args[1])
        require(Files.isRegularFile(searchPath)) { "Missing search manifest: $searchPath" }
        require(Files.isRegularFile(pagePath)) { "Missing church-page manifest: $pagePath" }
        val json = Json { ignoreUnknownKeys = true }
        val search = json.decodeFromString<IndexManifest>(Files.readString(searchPath))
        val pages = json.decodeFromString<ChurchPageManifest>(Files.readString(pagePath))
        require(search.catalogRevision.isNotBlank() && search.catalogContentHash.isNotBlank()) {
            "Search manifest has no canonical catalog revision metadata"
        }
        require(pages.catalogRevision.isNotBlank() && pages.catalogContentHash.isNotBlank()) {
            "Church-page manifest has no canonical catalog revision metadata"
        }
        check(search.catalogRevision == pages.catalogRevision) {
            "Catalog revision mismatch: search=${search.catalogRevision} pages=${pages.catalogRevision}"
        }
        check(search.catalogContentHash == pages.catalogContentHash) {
            "Catalog content hash mismatch: search=${search.catalogContentHash} pages=${pages.catalogContentHash}"
        }
        check(search.sourceSha256 == search.catalogContentHash && pages.sourceSha256 == pages.catalogContentHash) {
            "A projection source hash does not equal its canonical logical content hash"
        }
        println("Verified catalog projection revision=${search.catalogRevision} hash=${search.catalogContentHash}")
    }
}
