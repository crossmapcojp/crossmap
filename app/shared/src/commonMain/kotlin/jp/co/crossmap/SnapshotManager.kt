package jp.co.crossmap

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import okio.ByteString.Companion.toByteString

class SnapshotManager(
    private val root: Path,
    private val serverBaseUrl: String,
    private val client: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshots = root / "snapshots"
    private val activePointer = root / "active-version.txt"

    suspend fun update(): IndexManifest {
        val manifestText = client.get("${serverBaseUrl.trimEnd('/')}/api/v1/indexes/churches/latest").body<String>()
        val manifest = json.decodeFromString<IndexManifest>(manifestText)
        val archiveName = requireNotNull(manifest.archiveFile) { "Snapshot manifest has no archiveFile" }
        val expectedHash = requireNotNull(manifest.sha256) { "Snapshot manifest has no sha256" }
        val bytes = client.get("${serverBaseUrl.trimEnd('/')}/downloads/churches/$archiveName").body<ByteArray>()
        require(bytes.toByteString().sha256().hex() == expectedHash) { "Snapshot checksum mismatch" }

        fileSystem.createDirectories(root)
        fileSystem.createDirectories(snapshots)
        val part = root / "$archiveName.part"
        fileSystem.write(part) { write(bytes) }
        val staging = snapshots / "${manifest.indexVersion}.staging"
        deleteRecursively(staging)
        fileSystem.createDirectories(staging)
        val zip = fileSystem.openZip(part)
        zip.listRecursively("/".toPath()).filter { zip.metadata(it).isRegularFile }.forEach { source ->
            val relative = source.relativeTo("/".toPath())
            val destination = staging / relative
            fileSystem.createDirectories(destination.parent!!)
            zip.source(source).buffer().use { input ->
                fileSystem.sink(destination).buffer().use { output -> output.writeAll(input) }
            }
        }
        require(fileSystem.metadataOrNull(staging / "index" / "ja")?.isDirectory == true) {
            "Snapshot contains no Japanese index"
        }
        require(fileSystem.metadataOrNull(staging / "geonames.json")?.isRegularFile == true) { "Snapshot contains no geonames" }
        val destination = snapshots / manifest.indexVersion
        deleteRecursively(destination)
        fileSystem.atomicMove(staging, destination)
        val pointerPart = root / "active-version.txt.part"
        fileSystem.write(pointerPart) { writeUtf8(manifest.indexVersion) }
        fileSystem.atomicMove(pointerPart, activePointer)
        fileSystem.delete(part)
        return manifest
    }

    fun activeEngine(languageCode: String = "ja"): ChurchSearchEngine? {
        if (fileSystem.metadataOrNull(activePointer)?.isRegularFile != true) return null
        val version = fileSystem.read(activePointer) { readUtf8() }.trim()
        val directory = snapshots / version
        val language = languageCode.substringBefore('-').lowercase().takeIf { it in SUPPORTED_LANGUAGES } ?: "ja"
        val index = directory / "index" / language
        if (fileSystem.metadataOrNull(index)?.isDirectory != true) return null
        val geonames = json.decodeFromString<List<GeoName>>(fileSystem.read(directory / "geonames.json") { readUtf8() })
        return ChurchSearchEngine(index, geonames, version, languageCode = language)
    }

    private fun deleteRecursively(path: Path) {
        if (fileSystem.metadataOrNull(path) == null) return
        fileSystem.listRecursively(path).toList().asReversed().forEach(fileSystem::delete)
        fileSystem.delete(path)
    }

    private companion object {
        val SUPPORTED_LANGUAGES = setOf("ja", "en", "ko", "pt", "id")
    }
}
