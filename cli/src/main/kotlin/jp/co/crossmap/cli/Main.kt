package jp.co.crossmap.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.choice
import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchSearchEngine
import jp.co.crossmap.ChurchSearchRequest
import jp.co.crossmap.GeoName
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.IndexManifest
import jp.co.crossmap.QueryLanguageDetector
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

private val compactJson = Json { encodeDefaults = true }
private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }

internal class Cm : CliktCommand(name = "cm") {
    override fun run() = Unit
}

private class Church : CliktCommand(name = "church") {
    override fun run() = Unit
}

private class Index : CliktCommand(name = "index") {
    override fun run() = Unit
}

private abstract class IndexCommand(name: String) : CliktCommand(name = name) {
    private val configuredIndexPath by option("--index", envvar = "CROSSMAP_INDEX_DIR")
    protected val language by option("--language")
        .choice("auto", "ja", "en", "ko", "pt", "id", "vi", "zh-Hans", "zh-Hant")
        .default("auto")
    protected val geonamesPath by option("--geonames", envvar = "CROSSMAP_GEONAMES")
        .default("resources/geonames/japan.json")
    protected val indexPath: String
        get() = indexPath(language.takeUnless { it == "auto" } ?: "ja")

    private fun indexPath(languageCode: String): String = configuredIndexPath ?: defaultIndexPath(languageCode)

    private fun defaultIndexPath(languageCode: String): String {
        val latestFile = listOf(
            Path.of("cache/search-indexes/churches/latest.json"),
            Path.of("resources/indexes/churches/latest.json"),
        ).firstOrNull(Files::isRegularFile) ?: Path.of("cache/search-indexes/churches/latest.json")
        require(Files.isRegularFile(latestFile)) {
            "No default index found; run build-snapshot or pass --index"
        }
        val latest = compactJson.decodeFromString<IndexManifest>(Files.readString(latestFile))
        return latestFile.parent.resolve(latest.indexVersion).resolve("index").resolve(languageCode).toString()
    }

    protected fun manifest(languageCode: String = language.takeUnless { it == "auto" } ?: "ja"): IndexManifest? {
        val file = Path.of(indexPath(languageCode)).parent?.parent?.resolve("manifest.json") ?: return null
        return if (Files.isRegularFile(file)) compactJson.decodeFromString(Files.readString(file)) else null
    }

    protected fun engine(languageCode: String): ChurchSearchEngine {
        val geonames = compactJson.decodeFromString<List<GeoName>>(Files.readString(Path.of(geonamesPath)))
        return ChurchSearchEngine(
            indexPath(languageCode).toPath(),
            geonames,
            manifest(languageCode)?.indexVersion ?: "development",
            languageCode = languageCode,
        )
    }
}

private class ChurchSearch : IndexCommand("search") {
    private val query by argument(help = "Church search query")
    private val offset by option("--offset").int().default(0).validate { require(it >= 0) { "must not be negative" } }
    private val limit by option("--limit").int().default(20).validate { require(it in 1..100) { "must be between 1 and 100" } }
    private val radiusKm by option("--radius-km").double().validate { require(it > 0.0) { "must be positive" } }
    private val latitude by option("--latitude").double().validate { require(it in -90.0..90.0) { "must be between -90 and 90" } }
    private val longitude by option("--longitude").double().validate { require(it in -180.0..180.0) { "must be between -180 and 180" } }
    private val titleLanguages by option("--title-language").multiple()
    private val jsonOutput by option("--json").flag()
    private val pretty by option("--pretty").flag()

    override fun run() {
        if ((latitude == null) != (longitude == null)) {
            throw UsageError("--latitude and --longitude must be supplied together")
        }
        val queryLanguage = language.takeUnless { it == "auto" } ?: QueryLanguageDetector.detect(query)
        val response = engine(queryLanguage).search(
            ChurchSearchRequest(
                query = query,
                offset = offset,
                limit = limit,
                radiusKm = radiusKm,
                userLocation = latitude?.let { GeoPoint(it, requireNotNull(longitude)) },
                titleLanguages = titleLanguages,
            )
        )
        if (jsonOutput) {
            echo((if (pretty) prettyJson else compactJson).encodeToString(response))
            return
        }
        if (response.hits.isEmpty()) {
            echo("No churches found.")
            return
        }
        response.hits.forEachIndexed { index, hit ->
            val distance = hit.distanceKm?.let { " · ${formatDistance(it)} km" }.orEmpty()
            echo("${response.offset + index + 1}. ${hit.name}$distance")
            echo("   ${hit.address}")
            if (hit.websiteUrl.isNotBlank()) echo("   ${hit.websiteUrl}")
            hit.matchedPages.firstOrNull()?.snippet?.takeIf { it.isNotBlank() }?.let { echo("   $it") }
        }
        echo("Showing ${response.offset + 1}-${response.offset + response.hits.size} of ${response.total}")
    }

    private fun formatDistance(value: Double): String = ((value * 10).toInt() / 10.0).toString()
}

private class IndexInfo : IndexCommand("info") {
    override fun run() {
        val manifest = manifest()
        if (manifest == null) {
            echo("No manifest found for $indexPath", err = true)
            return
        }
        echo(prettyJson.encodeToString(manifest))
    }
}

internal fun rootCommand() = Cm().subcommands(
    Church().subcommands(ChurchSearch(), Index().subcommands(IndexInfo())),
)

fun main(args: Array<String>) = rootCommand().main(args)
