package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jp.co.crossmap.ChurchIndex
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.IndexManifest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class SnapshotBuilder(private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }) {
    fun build(resourcesRoot: Path, version: String): IndexManifest {
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(resourcesRoot.resolve("catalog/churches.json")))
        val snapshotDir = resourcesRoot.resolve("indexes/churches/$version")
        val indexDir = snapshotDir.resolve("index")
        Files.createDirectories(indexDir)
        ChurchIndex.build(indexDir.toString().toPath(), churches)
        Files.copy(
            resourcesRoot.resolve("geonames/japan.json"),
            snapshotDir.resolve("geonames.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        var manifest = IndexManifest(
            indexVersion = version,
            luceneVersion = "10.2.0-alpha14",
            createdAt = Instant.now().toString(),
            documentCount = churches.size,
            archiveFile = "churches-$version.zip",
        )
        Files.createDirectories(snapshotDir)
        Files.writeString(snapshotDir.resolve("manifest.json"), json.encodeToString(manifest))
        val archive = resourcesRoot.resolve("indexes/churches/churches-$version.zip")
        zip(snapshotDir, archive)
        val bytes = Files.readAllBytes(archive)
        manifest = manifest.copy(archiveSize = bytes.size.toLong(), sha256 = bytes.sha256())
        Files.writeString(snapshotDir.resolve("manifest.json"), json.encodeToString(manifest))
        Files.writeString(resourcesRoot.resolve("indexes/churches/latest.json"), json.encodeToString(manifest))
        return manifest
    }

    private fun zip(source: Path, destination: Path) {
        Files.createDirectories(destination.parent)
        ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
            Files.walk(source).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    zip.putNextEntry(ZipEntry(source.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
    }
}
