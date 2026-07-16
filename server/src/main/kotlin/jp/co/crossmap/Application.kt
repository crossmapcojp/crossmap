package jp.co.crossmap

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = {
            module(
                searchEngine = loadSearchEngine("ja"),
                searchEngines = loadSearchEngines(),
            )
        },
    ).start(wait = true)
}

fun Application.module(
    searchEngine: ChurchSearchEngine? = loadSearchEngine("ja"),
    searchEngines: Map<String, ChurchSearchEngine> = emptyMap(),
    resourcesRoot: Path = Path.of(System.getenv("CROSSMAP_RESOURCES") ?: "resources"),
    cacheRoot: Path = Path.of(
        System.getenv("CROSSMAP_CACHE") ?: resourcesRoot.toAbsolutePath().normalize().parent.resolve("cache").toString(),
    ),
    webRoot: Path = Path.of(System.getenv("CROSSMAP_WEB_DIR") ?: "webclient"),
) {
    val availableSearchEngines = buildMap {
        searchEngine?.let { put("ja", it) }
        putAll(searchEngines)
    }
    val churchIndexes = cacheRoot.resolve("search-indexes/churches")
    install(ContentNegotiation) { json(wireJson) }
    install(CORS) { anyHost() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", cause.message ?: "Invalid request"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Request failed: ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Request failed"))
        }
    }
    routing {
        get("/api/v1/health") {
            call.respond(mapOf("status" to if (availableSearchEngines.isEmpty()) "not_ready" else "ok"))
        }
        get("/api/v1/churches/search") {
            val displayLanguage = call.request.queryParameters["lang"]?.substringBefore('-')?.lowercase() ?: "ja"
            val query = call.request.queryParameters["q"].orEmpty()
            val queryLanguage = QueryLanguageDetector.detect(query, displayLanguage)
            val engine = availableSearchEngines[queryLanguage]
                ?: availableSearchEngines[displayLanguage]
                ?: availableSearchEngines["ja"]
                ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("index_unavailable", "Church index is not configured"),
            )
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val radius = call.request.queryParameters["radiusKm"]?.toDoubleOrNull()
            val latitude = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val longitude = call.request.queryParameters["lon"]?.toDoubleOrNull()
            require((latitude == null) == (longitude == null)) { "lat and lon must be provided together" }
            val userLocation = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
            val titleLanguages = call.request.queryParameters.getAll("titleLanguage").orEmpty()
            call.respond(engine.search(ChurchSearchRequest(query, offset, limit, radius, userLocation, titleLanguages)))
        }
        get("/api/v1/churches/{id}") {
            val engine = availableSearchEngines["ja"] ?: availableSearchEngines.values.firstOrNull() ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("index_unavailable", "Church index is not configured"),
            )
            val id = call.parameters["id"] ?: throw IllegalArgumentException("church id is required")
            val church = engine.church(id) ?: return@get call.respond(
                HttpStatusCode.NotFound,
                ApiError("church_not_found", "No church exists with id $id"),
            )
            call.respond(church)
        }
        get("/api/v1/indexes/churches/latest") {
            val latest = churchIndexes.resolve("latest.json").toFile()
            if (!latest.isFile) return@get call.respond(
                HttpStatusCode.NotFound,
                ApiError("snapshot_not_found", "No church snapshot has been published"),
            )
            call.respondFile(latest)
        }
        get("/downloads/churches/{file}") {
            val name = call.parameters["file"].orEmpty()
            require(name.matches(Regex("churches-[A-Za-z0-9._-]+\\.zip"))) { "invalid archive name" }
            val archive = churchIndexes.resolve(name).toFile()
            if (!archive.isFile) return@get call.respond(HttpStatusCode.NotFound, ApiError("snapshot_not_found", name))
            call.respondFile(archive)
        }
        get("/") { call.respondWebFile(webRoot.resolve("index.html"), ContentType.Text.Html) }
        get("/church/{file}") {
            val name = call.parameters["file"].orEmpty()
            require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.html"))) { "invalid church page name" }
            call.respondWebFile(webRoot.resolve("church").resolve(name), ContentType.Text.Html)
        }
        get("/church.html") { call.respondWebFile(webRoot.resolve("church.html"), ContentType.Text.Html) }
        get("/result.html") { call.respondWebFile(webRoot.resolve("result.html"), ContentType.Text.Html) }
        get("/app.js") { call.respondWebFile(webRoot.resolve("app.js"), ContentType.Application.JavaScript) }
        get("/styles.css") { call.respondWebFile(webRoot.resolve("styles.css"), ContentType.Text.CSS) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondWebFile(path: Path, type: ContentType) {
    if (!Files.isRegularFile(path)) return respond(HttpStatusCode.NotFound, ApiError("asset_not_found", path.fileName.toString()))
    respondText(Files.readString(path), type)
}

private fun loadSearchEngines(): Map<String, ChurchSearchEngine> =
    listOf("ja", "en", "ko", "pt", "id").mapNotNull { language ->
        loadSearchEngine(language)?.let { language to it }
    }.toMap()

private fun loadSearchEngine(languageCode: String): ChurchSearchEngine? = runCatching {
    val resourcesRoot = Path.of(System.getenv("CROSSMAP_RESOURCES") ?: "resources")
    val cacheRoot = Path.of(
        System.getenv("CROSSMAP_CACHE") ?: resourcesRoot.toAbsolutePath().normalize().parent.resolve("cache").toString(),
    )
    val webRoot = Path.of(System.getenv("CROSSMAP_WEB_DIR") ?: "webclient")
    val japaneseIndex = resolveServerIndex(resourcesRoot, System.getenv("CROSSMAP_INDEX_DIR"), cacheRoot) ?: return null
    val index = if (languageCode == "ja") japaneseIndex else japaneseIndex.parent.resolve(languageCode)
    val geonamesFile = Path.of(System.getenv("CROSSMAP_GEONAMES") ?: resourcesRoot.resolve("geonames/japan.json").toString())
    if (!Files.isDirectory(index) || !Files.isRegularFile(geonamesFile)) return null
    val geonames = wireJson.decodeFromString<List<GeoName>>(Files.readString(geonamesFile))
    val manifestFile = index.parent.parent.resolve("manifest.json")
    if (!Files.isRegularFile(manifestFile)) return null
    val indexManifest = wireJson.decodeFromString<IndexManifest>(Files.readString(manifestFile))
    // Static detail pages are an optional presentation artifact. A stale/missing page manifest
    // must not make the JSON search API unavailable; the web client can use church.html as fallback.
    val churchPageUrls = loadChurchPageUrls(resourcesRoot, webRoot).orEmpty()
    ChurchSearchEngine(
        index.toString().toPath(),
        geonames,
        indexManifest.indexVersion,
        churchPageUrls,
        languageCode,
    )
}.getOrNull()

internal fun loadChurchPageUrls(resourcesRoot: Path, webRoot: Path): Map<String, String>? {
    val manifestFile = webRoot.resolve("church/manifest.json")
    if (!Files.isRegularFile(manifestFile)) return null
    val manifest = runCatching {
        wireJson.decodeFromString<ChurchPageManifest>(Files.readString(manifestFile))
    }.getOrNull() ?: return null
    val catalog = resourcesRoot.resolve("catalog/churches.json")
    if (!Files.isRegularFile(catalog) || manifest.sourceSha256 != catalog.sha256()) return null
    if (manifest.pages.isEmpty() || manifest.pages.values.any { page ->
            !page.matches(Regex("""/church/[a-z0-9]+(?:-[a-z0-9]+)*\.html"""))
        }
    ) return null
    return manifest.pages
}

internal fun resolveServerIndex(
    resourcesRoot: Path,
    configured: String?,
    cacheRoot: Path = Path.of(
        System.getenv("CROSSMAP_CACHE") ?: resourcesRoot.toAbsolutePath().normalize().parent.resolve("cache").toString(),
    ),
): Path? {
    if (!configured.isNullOrBlank()) return Path.of(configured)
    val indexes = cacheRoot.resolve("search-indexes/churches")
    val latestFile = indexes.resolve("latest.json")
    if (!Files.isRegularFile(latestFile)) return null
    val manifest = runCatching { wireJson.decodeFromString<IndexManifest>(Files.readString(latestFile)) }.getOrNull() ?: return null
    if (manifest.schemaVersion != ChurchIndex.SCHEMA_VERSION) return null
    val catalog = resourcesRoot.resolve("catalog/churches.json")
    if (!Files.isRegularFile(catalog) || manifest.sourceSha256.isBlank() ||
        manifest.sourceSha256 != catalog.sha256()
    ) return null
    return indexes.resolve("${manifest.indexVersion}/index/ja").takeIf(Files::isDirectory)
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
