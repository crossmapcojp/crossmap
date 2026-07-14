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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = Application::module,
    ).start(wait = true)
}

fun Application.module(
    searchEngine: ChurchSearchEngine? = loadSearchEngine(),
    resourcesRoot: Path = Path.of(System.getenv("CROSSMAP_RESOURCES") ?: "resources"),
    webRoot: Path = Path.of(System.getenv("CROSSMAP_WEB_DIR") ?: "webclient"),
) {
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
            call.respond(mapOf("status" to if (searchEngine == null) "not_ready" else "ok"))
        }
        get("/api/v1/churches/search") {
            val engine = searchEngine ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("index_unavailable", "Church index is not configured"),
            )
            val query = call.request.queryParameters["q"].orEmpty()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val radius = call.request.queryParameters["radiusKm"]?.toDoubleOrNull()
            val latitude = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val longitude = call.request.queryParameters["lon"]?.toDoubleOrNull()
            require((latitude == null) == (longitude == null)) { "lat and lon must be provided together" }
            val userLocation = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
            call.respond(engine.search(ChurchSearchRequest(query, offset, limit, radius, userLocation)))
        }
        get("/api/v1/churches/{id}") {
            val engine = searchEngine ?: return@get call.respond(
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
            val latest = resourcesRoot.resolve("indexes/churches/latest.json").toFile()
            if (!latest.isFile) return@get call.respond(
                HttpStatusCode.NotFound,
                ApiError("snapshot_not_found", "No church snapshot has been published"),
            )
            call.respondFile(latest)
        }
        get("/downloads/churches/{file}") {
            val name = call.parameters["file"].orEmpty()
            require(name.matches(Regex("churches-[A-Za-z0-9._-]+\\.zip"))) { "invalid archive name" }
            val archive = resourcesRoot.resolve("indexes/churches/$name").toFile()
            if (!archive.isFile) return@get call.respond(HttpStatusCode.NotFound, ApiError("snapshot_not_found", name))
            call.respondFile(archive)
        }
        get("/") { call.respondWebFile(webRoot.resolve("index.html"), ContentType.Text.Html) }
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

private fun loadSearchEngine(): ChurchSearchEngine? = runCatching {
    val resourcesRoot = Path.of(System.getenv("CROSSMAP_RESOURCES") ?: "resources")
    val index = resolveServerIndex(resourcesRoot, System.getenv("CROSSMAP_INDEX_DIR")) ?: return null
    val geonamesFile = Path.of(System.getenv("CROSSMAP_GEONAMES") ?: resourcesRoot.resolve("geonames/japan.json").toString())
    if (!Files.isDirectory(index) || !Files.isRegularFile(geonamesFile)) return null
    val geonames = wireJson.decodeFromString<List<GeoName>>(Files.readString(geonamesFile))
    val manifestFile = index.parent.resolve("manifest.json")
    val version = if (Files.isRegularFile(manifestFile)) {
        wireJson.decodeFromString<IndexManifest>(Files.readString(manifestFile)).indexVersion
    } else "development"
    ChurchSearchEngine(index.toString().toPath(), geonames, version)
}.getOrNull()

internal fun resolveServerIndex(resourcesRoot: Path, configured: String?): Path? {
    if (!configured.isNullOrBlank()) return Path.of(configured)
    val latestFile = resourcesRoot.resolve("indexes/churches/latest.json")
    if (!Files.isRegularFile(latestFile)) return null
    val manifest = runCatching { wireJson.decodeFromString<IndexManifest>(Files.readString(latestFile)) }.getOrNull() ?: return null
    return resourcesRoot.resolve("indexes/churches/${manifest.indexVersion}/index")
}
