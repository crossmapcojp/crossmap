package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jp.co.crossmap.ChurchIndex
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.IndexManifest
import jp.co.crossmap.Language
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.supportedLanguageCodes
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class SnapshotBuilder(private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }) {
    fun build(
        resourcesRoot: Path,
        version: String,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): IndexManifest {
        val catalogBytes = Files.readAllBytes(resourcesRoot.resolve("catalog/churches.json"))
        val churches = json.decodeFromString<List<ChurchRecord>>(catalogBytes.toString(Charsets.UTF_8))
        val denominationNames = DenominationNameCatalogFiles.load(resourcesRoot)
        val indexedChurches = churches.map { church ->
            church.copy(
                localizedDenominationNames = church.denominationId?.let { denominationId ->
                    Language.entries.mapNotNull { language ->
                        denominationNames.getValue(language)[denominationId]
                            ?.takeIf(String::isNotBlank)
                            ?.let { LocalizedName(language.code, it) }
                    }
                }.orEmpty(),
            )
        }
        val indexes = CrossmapPaths(resourcesRoot, cacheRoot).searchIndexes
        val snapshotDir = indexes.resolve(version)
        val indexDir = snapshotDir.resolve("index")
        Files.createDirectories(indexDir)
        val geonameTranslationsFile = resourcesRoot.resolve("geonames/church-ja-all.json")
        val geonameUsagesFile = resourcesRoot.resolve("geonames/church-usage.json")
        val translations = if (Files.isRegularFile(geonameTranslationsFile)) {
            json.decodeFromString<List<ChurchGeoNameTranslation>>(Files.readString(geonameTranslationsFile))
                .associateBy { it.ja }
        } else emptyMap()
        val usages = if (Files.isRegularFile(geonameUsagesFile)) {
            json.decodeFromString<List<ChurchGeoNameUsage>>(Files.readString(geonameUsagesFile)).associateBy { it.churchId }
        } else emptyMap()
        supportedLanguageCodes.forEach { language ->
            val translatedGeoNames = usages.mapValues { (_, usage) ->
                translatedGeoNamesForLanguage(usage, translations, language)
            }
            val languageIndex = indexDir.resolve(language)
            Files.createDirectories(languageIndex)
            ChurchIndex.build(languageIndex.toString().toPath(), indexedChurches, language, translatedGeoNames)
        }
        Files.copy(
            resourcesRoot.resolve("geonames/japan.json"),
            snapshotDir.resolve("geonames.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        var manifest = IndexManifest(
            schemaVersion = ChurchIndex.SCHEMA_VERSION,
            indexVersion = version,
            luceneVersion = "10.2.0-alpha14",
            createdAt = Instant.now().toString(),
            documentCount = churches.size,
            languages = supportedLanguageCodes,
            sourceSha256 = catalogBytes.sha256(),
            archiveFile = "churches-$version.zip",
        )
        Files.createDirectories(snapshotDir)
        Files.writeString(snapshotDir.resolve("manifest.json"), json.encodeToString(manifest))
        val archive = indexes.resolve("churches-$version.zip")
        zip(snapshotDir, archive)
        val bytes = Files.readAllBytes(archive)
        manifest = manifest.copy(archiveSize = bytes.size.toLong(), sha256 = bytes.sha256())
        Files.writeString(snapshotDir.resolve("manifest.json"), json.encodeToString(manifest))
        Files.writeString(indexes.resolve("latest.json"), json.encodeToString(manifest))
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

internal fun translatedGeoNamesForLanguage(
    usage: ChurchGeoNameUsage,
    translations: Map<String, ChurchGeoNameTranslation>,
    language: String,
): List<String> = (usage.title + usage.address).distinctGeoNames().mapNotNull { japanese ->
    if (language == "ja") japanese else translations[japanese]?.translations?.get(language)?.takeIf(String::isNotBlank)
}.distinctGeoNames()

/**
 * Keeps the first display spelling while preventing the same geoname from being indexed more than once.
 * NFKC also folds full-width Latin text; case and whitespace differences are not distinct search terms.
 */
internal fun Iterable<String>.distinctGeoNames(): List<String> {
    val seen = linkedSetOf<String>()
    return mapNotNull { raw ->
        val value = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (value.isBlank()) return@mapNotNull null
        val key = value.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        value.takeIf { seen.add(key) }
    }
}
