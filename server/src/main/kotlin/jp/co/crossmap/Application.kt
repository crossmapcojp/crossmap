package jp.co.crossmap

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import io.ktor.server.application.log
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource

private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun ChurchSearchResponse.withPublicWebsiteUrls(policy: ChurchWebsitePolicy): ChurchSearchResponse =
    copy(
        hits = hits.map { hit ->
            hit.copy(websiteUrl = policy.publicWebsiteUrl(hit.websiteUrl, null, hit.churchId))
        },
    )

private fun ChurchDetailResponse.withPublicWebsiteUrl(policy: ChurchWebsitePolicy): ChurchDetailResponse =
    copy(websiteUrl = policy.publicWebsiteUrl(websiteUrl, null, churchId))

internal fun renderHttpSearchTiming(timings: Map<String, Duration>, total: Duration): String {
    val totalNanoseconds = total.inWholeNanoseconds.coerceAtLeast(1L)
    fun percentage(duration: Duration): Double =
        ((duration.inWholeNanoseconds.toDouble() / totalNanoseconds * 1_000.0).roundToInt() / 10.0)
    val measured = timings.values.fold(Duration.ZERO) { accumulated, duration -> accumulated + duration }
    val other = (total - measured).coerceAtLeast(Duration.ZERO)
    return buildString {
        appendLine("search-http-timing:")
        timings.forEach { (name, duration) ->
            appendLine("  $name=$duration (${percentage(duration)}%)")
        }
        appendLine("  other=$other (${percentage(other)}%)")
        append("  total=$total (100.0%)")
    }
}

fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = {
            module(
                searchEngine = null,
                reloadSearchEngines = true,
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
    reloadSearchEngines: Boolean = false,
) {
    val excludedDomainsFile = resourcesRoot.resolve("catalog/excludedChurchListingDomains.txt")
    val websitePolicy = ChurchWebsitePolicy(
        if (Files.isRegularFile(excludedDomainsFile)) {
            ChurchWebsitePolicy.parse(Files.readString(excludedDomainsFile))
        } else {
            emptySet()
        },
    )
    val initialSearchEngines = buildMap {
        searchEngine?.let { put("ja", it) }
        putAll(searchEngines)
    }
    val searchEngineRegistry = ReloadingSearchEngineRegistry(
        initial = initialSearchEngines,
        fingerprint = if (reloadSearchEngines) {
            { searchIndexFingerprint(resourcesRoot, cacheRoot) }
        } else {
            null
        },
        loader = if (reloadSearchEngines) {
            { loadSearchEngines(resourcesRoot, cacheRoot, webRoot) }
        } else {
            null
        },
        onReload = { languages -> log.info("Reloaded latest search engines: [${languages.joinToString()}]") },
    )
    val startupSearchEngines = searchEngineRegistry.current()
    monitor.subscribe(ApplicationStopped) {
        searchEngineRegistry.close()
    }
    log.info("Available search engines: [${startupSearchEngines.keys.joinToString()}]")
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
            val availableSearchEngines = searchEngineRegistry.current()
            call.respond(mapOf("status" to if (availableSearchEngines.isEmpty()) "not_ready" else "ok"))
        }
        get("/api/v1/churches/search") {
            val timeSource = TimeSource.Monotonic
            val totalMark = timeSource.markNow()
            val timings = linkedMapOf<String, Duration>()
            var stepMark = timeSource.markNow()
            fun finishStep(name: String) {
                timings[name] = stepMark.elapsedNow()
                stepMark = timeSource.markNow()
            }

            val displayLanguage = Language.fromCode(call.request.queryParameters["lang"])?.code ?: "ja"
            val query = call.request.queryParameters["q"].orEmpty()
            finishStep("request.readQuery")
            val queryLanguage = QueryLanguageDetector.detect(query, displayLanguage)
            finishStep("language.detect")
            call.application.environment.log.debug("search-request: query='$query', displayLang=$displayLanguage, detectedLang=$queryLanguage")
            val availableSearchEngines = searchEngineRegistry.current()
            val engine = availableSearchEngines[queryLanguage]
                ?: availableSearchEngines[displayLanguage]
                ?: availableSearchEngines["ja"]
                ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("index_unavailable", "Church index is not configured"),
            )
            finishStep("engine.select")
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val radius = call.request.queryParameters["radiusKm"]?.toDoubleOrNull()
            val latitude = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val longitude = call.request.queryParameters["lon"]?.toDoubleOrNull()
            require((latitude == null) == (longitude == null)) { "lat and lon must be provided together" }
            val userLocation = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
            val titleLanguages = call.request.queryParameters.getAll("titleLanguage").orEmpty()
            finishStep("request.parseOptions")
            val response = engine.search(ChurchSearchRequest(query, offset, limit, radius, userLocation, titleLanguages))
                .withPublicWebsiteUrls(websitePolicy)
            finishStep("engine.search")
            call.respond(response)
            finishStep("response.send")
            val total = totalMark.elapsedNow()
            call.application.environment.log.info("search-response: query='$query', lang=$queryLanguage, total=${response.total}, returned=${response.hits.size}, locations=[${response.resolvedLocations.joinToString { it.name }}], duration=$total")
            call.application.environment.log.info(renderHttpSearchTiming(timings, total))
        }
        get("/api/v1/churches/{id}") {
            val availableSearchEngines = searchEngineRegistry.current()
            val engine = availableSearchEngines["ja"] ?: availableSearchEngines.values.firstOrNull() ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("index_unavailable", "Church index is not configured"),
            )
            val id = call.parameters["id"] ?: throw IllegalArgumentException("church id is required")
            val church = engine.church(id) ?: return@get call.respond(
                HttpStatusCode.NotFound,
                ApiError("church_not_found", "No church exists with id $id"),
            )
            call.respond(church.withPublicWebsiteUrl(websitePolicy))
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
        get("/sitemap.xml") { call.respondWebFile(webRoot.resolve("sitemap.xml"), ContentType.Application.Xml) }
        get("/{language}/") {
            val language = Language.fromCode(call.parameters["language"])
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("language_not_supported", "Unsupported language"))
            call.respondWebFile(webRoot.resolve(language.code).resolve("index.html"), ContentType.Text.Html)
        }
        get("/{language}/index.html") {
            val language = Language.fromCode(call.parameters["language"])
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("language_not_supported", "Unsupported language"))
            call.respondWebFile(webRoot.resolve(language.code).resolve("index.html"), ContentType.Text.Html)
        }
        get("/{language}/result.html") {
            val language = Language.fromCode(call.parameters["language"])
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("language_not_supported", "Unsupported language"))
            call.respondWebFile(webRoot.resolve(language.code).resolve("result.html"), ContentType.Text.Html)
        }
        get("/{language}/{file}") {
            val language = Language.fromCode(call.parameters["language"])
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("language_not_supported", "Unsupported language"))
            val name = call.parameters["file"].orEmpty()
            require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.html"))) { "invalid church page name" }
            call.respondWebFile(webRoot.resolve(language.code).resolve(name), ContentType.Text.Html)
        }
        get("/app.js") { call.respondWebFile(webRoot.resolve("app.js"), ContentType.Application.JavaScript) }
        get("/styles.css") { call.respondWebFile(webRoot.resolve("styles.css"), ContentType.Text.CSS) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondWebFile(path: Path, type: ContentType) {
    if (!Files.isRegularFile(path)) return respond(HttpStatusCode.NotFound, ApiError("asset_not_found", path.fileName.toString()))
    respondText(Files.readString(path), type)
}

private class ReloadingSearchEngineRegistry(
    initial: Map<String, ChurchSearchEngine>,
    private val fingerprint: (() -> String)? = null,
    private val loader: (() -> Map<String, ChurchSearchEngine>)? = null,
    private val onReload: (Set<String>) -> Unit = {},
) {
    @Volatile
    private var engines = initial
    @Volatile
    private var loadedFingerprint = if (loader == null) "fixed" else "unchecked"
    private val retiredEngines = mutableListOf<ChurchSearchEngine>()

    init {
        initial.values.distinct().forEach(ChurchSearchEngine::warmUp)
    }

    fun current(): Map<String, ChurchSearchEngine> {
        val load = loader ?: return engines
        val currentFingerprint = requireNotNull(fingerprint).invoke()
        if (loadedFingerprint == currentFingerprint) return engines
        return synchronized(this) {
            val refreshedFingerprint = requireNotNull(fingerprint).invoke()
            if (loadedFingerprint != refreshedFingerprint) {
                val replacement = load()
                replacement.values.distinct().forEach(ChurchSearchEngine::warmUp)
                retiredEngines += engines.values.distinct()
                engines = replacement
                loadedFingerprint = refreshedFingerprint
                if (replacement.isNotEmpty()) onReload(replacement.keys)
            }
            engines
        }
    }

    fun close() = synchronized(this) {
        (retiredEngines + engines.values).distinct().forEach(ChurchSearchEngine::close)
        retiredEngines.clear()
        engines = emptyMap()
    }
}

private fun searchIndexFingerprint(resourcesRoot: Path, cacheRoot: Path): String =
    listOf(
        cacheRoot.resolve("search-indexes/churches/latest.json"),
    ).joinToString("|") { path ->
        if (Files.isRegularFile(path)) {
            "${Files.getLastModifiedTime(path).toMillis()}:${Files.size(path)}"
        } else {
            "missing"
        }
    }

private fun loadSearchEngines(
    resourcesRoot: Path,
    cacheRoot: Path,
    webRoot: Path,
): Map<String, ChurchSearchEngine> =
    Language.entries.mapNotNull { language ->
        loadSearchEngine(language.code, resourcesRoot, cacheRoot, webRoot)?.let { language.code to it }
    }.toMap()

private fun loadSearchEngine(
    languageCode: String,
    resourcesRoot: Path = Path.of(System.getenv("CROSSMAP_RESOURCES") ?: "resources"),
    cacheRoot: Path = Path.of(
        System.getenv("CROSSMAP_CACHE") ?: resourcesRoot.toAbsolutePath().normalize().parent.resolve("cache").toString(),
    ),
    webRoot: Path = Path.of(System.getenv("CROSSMAP_WEB_DIR") ?: "webclient"),
): ChurchSearchEngine? = runCatching {
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
    val churchPageUrls = loadChurchPageUrls(
        resourcesRoot,
        webRoot,
        languageCode,
        indexManifest.catalogRevision,
        indexManifest.catalogContentHash,
    ).orEmpty()
    ChurchSearchEngine(
        index.toString().toPath(),
        geonames,
        indexManifest.indexVersion,
        churchPageUrls,
        languageCode,
    )
}.getOrNull()

internal fun loadChurchPageUrls(
    resourcesRoot: Path,
    webRoot: Path,
    languageCode: String = Language.ENGLISH.code,
    expectedCatalogRevision: String? = null,
    expectedCatalogContentHash: String? = null,
): Map<String, String>? {
    val language = Language.fromCode(languageCode) ?: return null
    val manifestFile = webRoot.resolve("manifest.json")
    if (!Files.isRegularFile(manifestFile)) return null
    val manifest = runCatching {
        wireJson.decodeFromString<ChurchPageManifest>(Files.readString(manifestFile))
    }.getOrNull() ?: return null
    if (manifest.schemaVersion != 2 || manifest.catalogRevision.isBlank() || manifest.catalogContentHash.isBlank() ||
        manifest.sourceSha256 != manifest.catalogContentHash
    ) return null
    if (expectedCatalogRevision != null && manifest.catalogRevision != expectedCatalogRevision) return null
    if (expectedCatalogContentHash != null && manifest.catalogContentHash != expectedCatalogContentHash) return null
    val pages = manifest.localizedPages.mapValues { (_, variants) -> variants[language.code].orEmpty() }
        .filterValues(String::isNotBlank)
        .ifEmpty { if (language == Language.ENGLISH) manifest.pages else emptyMap() }
    if (pages.isEmpty() || pages.values.any { page ->
            !page.matches(Regex("""/${Regex.escape(language.code)}/[a-z0-9]+(?:-[a-z0-9]+)*\.html"""))
        }
    ) return null
    return pages
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
    if (manifest.catalogRevision.isBlank() || manifest.catalogContentHash.isBlank() ||
        manifest.sourceSha256 != manifest.catalogContentHash
    ) return null
    val snapshot = indexes.resolve(manifest.indexVersion)
    val snapshotManifest = runCatching {
        wireJson.decodeFromString<IndexManifest>(Files.readString(snapshot.resolve("manifest.json")))
    }.getOrNull() ?: return null
    if (snapshotManifest != manifest) return null
    val archive = manifest.archiveFile?.let(indexes::resolve) ?: return null
    if (!Files.isRegularFile(archive) || manifest.archiveSize != Files.size(archive) || manifest.sha256 != archive.sha256()) return null
    return snapshot.resolve("index/ja").takeIf(Files::isDirectory)
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
