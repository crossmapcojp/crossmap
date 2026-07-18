package jp.co.crossmap.crawl

import jp.co.crossmap.LightPanda
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.ChurchWebsitePolicy
import jp.co.crossmap.LocalizedName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

@Serializable
data class GooglePlaceChurchCandidate(
    val id: String,
    val googleCid: String,
    val name: String,
    val titleLanguages: List<String> = emptyList(),
    val latinName: String? = null,
    val localizedNames: List<LocalizedName> = emptyList(),
    val nameComponents: List<MultilingualNameComponent> = emptyList(),
    val namePattern: ChurchNamePattern = ChurchNamePattern.SINGLE_NAME,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val category: String? = null,
    val denominationHint: String? = null,
    val sourceLists: List<String>,
    val resolvedAt: String,
)

@Serializable
data class GoogleMapsResolutionError(val id: String, val name: String, val message: String)

@Serializable
data class GoogleMapsResolutionReport(
    val seeds: Int,
    val candidates: Int,
    val cacheHits: Int,
    val fetched: Int,
    val catholicNonChurchesFiltered: Int,
    val namePatternCounts: Map<String, Int> = emptyMap(),
    val languageCounts: Map<String, Int> = emptyMap(),
    val localizedNameCounts: Map<String, Int> = emptyMap(),
    val nameComponentRoleCounts: Map<String, Int> = emptyMap(),
    val candidatesWithUnresolvedNameComponents: Int = 0,
    val errors: List<GoogleMapsResolutionError>,
)

data class GoogleMapsPage(val html: String, val cacheHit: Boolean)

fun interface GoogleMapsPageSource {
    fun load(seed: GoogleSavedPlaceSeed): GoogleMapsPage
}

class CachedGoogleMapsPageSource(
    private val cacheDirectory: Path,
    private val allowNetwork: Boolean = true,
    private val lightPanda: LightPanda = LightPanda(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : GoogleMapsPageSource {
    override fun load(seed: GoogleSavedPlaceSeed): GoogleMapsPage {
        // Retain gmap's verified edge-case redirect while keeping the Saved Places CID as entity identity.
        val pageCid = if (seed.googleCid == "3576720766476721565") "6907614827878617439" else seed.googleCid
        val cache = cacheDirectory.resolve("$pageCid.html")
        if (Files.isRegularFile(cache)) return GoogleMapsPage(Files.readString(cache), cacheHit = true)
        require(allowNetwork) { "No cached Google Maps page for CID ${seed.googleCid}" }
        val url = "https://www.google.com/maps?cid=$pageCid"
        val bytes = runCatching { fetchWithHttp(url) }
            .getOrNull()
            ?.takeIf { it.toString(Charsets.UTF_8).contains("google.com/maps/preview/place/") }
            ?: lightPanda.fetchHtml(url).toByteArray(Charsets.UTF_8)
        Files.createDirectories(cache.parent)
        Files.write(cache, bytes)
        return GoogleMapsPage(bytes.toString(Charsets.UTF_8), cacheHit = false)
    }

    private fun fetchWithHttp(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(45))
            //.header("User-Agent", "Mozilla/5.0 CrossmapCrawler/1.0")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "Google Maps HTTP ${response.statusCode()}" }
        return response.body()
    }

}

class GoogleMapsPlaceParser(
    private val multilingualNameLocalizer: MultilingualChurchNameLocalizer? = null,
    private val websitePolicy: ChurchWebsitePolicy = ChurchWebsitePolicy(emptySet()),
) {
    private val nameDecomposer = ChurchNameDecomposer()
    fun parse(seed: GoogleSavedPlaceSeed, html: String, now: String = Instant.now().toString()): GooglePlaceChurchCandidate {
        require(html.contains("google.com/maps/preview/place/")) { "Not a Google Maps place page" }
        val document = Jsoup.parse(html)
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val titleParts = ogTitle.split(" · ", limit = 2)
        val name = titleParts.firstOrNull()?.trim().orEmpty().ifBlank { seed.title.trim() }
        require(name.isNotBlank()) { "Google place name is blank" }

        val preview = PREVIEW_PLACE.find(html) ?: error("Google preview URL has no coordinates")
        val decodedPlacePath = URLDecoder.decode(preview.groupValues[1].replace("+", "%20"), Charsets.UTF_8)
        val addressFromPath = decodedPlacePath.removeSuffix(name).trim().trimEnd(',')
        val address = titleParts.getOrNull(1)?.trim().orEmpty().ifBlank { addressFromPath }
        require(address.isNotBlank()) { "Google place address is blank" }
        val latitude = preview.groupValues[2].toDouble()
        val longitude = preview.groupValues[3].toDouble()

        val website = websitePolicy.publicWebsiteUrl(extractWebsite(document, html), seed.googleCid, seed.id)
        val category = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.substringAfter(" · ")
            ?.trim()
            ?.ifBlank { null }
        val rawName = name.replace("\n", "").replace(Regex("""\s+"""), " ").trim()
        val savedTitle = seed.title.replace("\n", "").replace(Regex("""\s+"""), " ").trim()
        val localizationName = savedTitle.ifBlank { rawName }
        val localized = multilingualNameLocalizer?.localize(localizationName, seed.titleLanguages)
        val decomposed = localized?.let {
            DecomposedChurchName(localizationName, it.japaneseName, it.latinName, it.localizedNames, it.pattern)
        } ?: nameDecomposer.decompose(localizationName)
        val seedHasRicherName = localized == null &&
            (seed.japaneseName != null || seed.latinName != null || seed.localizedNames.isNotEmpty()) &&
            (decomposed.pattern == ChurchNamePattern.SINGLE_NAME ||
                (decomposed.latinName != null && decomposed.latinName == seed.latinName))
        val japaneseName = if (seedHasRicherName) seed.japaneseName else decomposed.japaneseName
        val latinName = if (seedHasRicherName) seed.latinName else decomposed.latinName
        val localizedNames = if (seedHasRicherName) seed.localizedNames else decomposed.localizedNames
        return GooglePlaceChurchCandidate(
            id = seed.id,
            googleCid = seed.googleCid,
            name = japaneseName ?: rawName,
            titleLanguages = seed.titleLanguages,
            latinName = latinName,
            localizedNames = localizedNames,
            nameComponents = localized?.components.orEmpty(),
            namePattern = decomposed.pattern,
            address = address,
            location = GeoPoint(latitude, longitude),
            websiteUrl = website,
            category = category,
            denominationHint = GoogleSavedPlacesLists.deterministicDenominationId(seed.sourceLists),
            sourceLists = seed.sourceLists,
            resolvedAt = now,
        )
    }

    private fun extractWebsite(document: org.jsoup.nodes.Document, html: String): String? {
        document.selectFirst("a[data-item-id=authority][href]")?.attr("href")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { return it }
        return ENCODED_WEBSITE.find(html)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.replace("\\u0026", "&")
    }

    companion object {
        private val PREVIEW_PLACE = Regex(
            """https://www\.google\.com/maps/preview/place/([^\"']+?)/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""",
        )
        private val ENCODED_WEBSITE = Regex(
            """/url\?q\\\\u003d(https?://.+?)\\\\u0026opi""",
        )
    }
}

class GoogleMapsPlaceResolver(
    private val pageSource: GoogleMapsPageSource,
    private val parser: GoogleMapsPlaceParser = GoogleMapsPlaceParser(),
    private val maxConcurrency: Int = 6,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun resolve(resourcesRoot: Path, cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot)): GoogleMapsResolutionReport {
        require(maxConcurrency in 1..32)
        val raw = CrossmapPaths(resourcesRoot, cacheRoot).googleSavedPlaces
        val seeds = json.decodeFromString<List<GoogleSavedPlaceSeed>>(Files.readString(raw.resolve("seeds.json")))
        val executor = Executors.newFixedThreadPool(maxConcurrency)
        val results = try {
            executor.invokeAll(seeds.map { seed -> Callable { resolveOne(seed) } }).map { it.get() }
        } finally {
            executor.shutdown()
        }
        val candidates = results.mapNotNull(Result::candidate).sortedBy(GooglePlaceChurchCandidate::id)
        atomicWrite(raw.resolve("google-place-candidates.json"), json.encodeToString(candidates))
        val candidatesById = candidates.associateBy(GooglePlaceChurchCandidate::id)
        val enrichedSeeds = seeds.map { seed ->
            candidatesById[seed.id]?.let { candidate ->
                seed.copy(
                    japaneseName = candidate.name,
                    latinName = candidate.latinName,
                    localizedNames = candidate.localizedNames,
                    nameComponents = candidate.nameComponents,
                    namePattern = candidate.namePattern,
                )
            } ?: seed
        }
        atomicWrite(raw.resolve("seeds.json"), json.encodeToString(enrichedSeeds))
        val errors = results.mapNotNull(Result::error)
        val report = GoogleMapsResolutionReport(
            seeds = seeds.size,
            candidates = candidates.size,
            cacheHits = results.count { it.cacheHit },
            fetched = results.count { it.fetched },
            catholicNonChurchesFiltered = results.count { it.filtered },
            namePatternCounts = candidates.groupingBy { it.namePattern.name }.eachCount().toSortedMap(),
            languageCounts = candidates.flatMap { it.titleLanguages.distinct() }.groupingBy { it }.eachCount().toSortedMap(),
            localizedNameCounts = candidates.flatMap { candidate ->
                candidate.localizedNames.map { it.languageCode.substringBefore('-').lowercase() }.distinct()
            }.groupingBy { it }.eachCount().toSortedMap(),
            nameComponentRoleCounts = candidates.flatMap { candidate ->
                candidate.nameComponents.map { it.role.name }
            }.groupingBy { it }.eachCount().toSortedMap(),
            candidatesWithUnresolvedNameComponents = candidates.count { candidate ->
                candidate.nameComponents.any { it.role == MultilingualNameComponentRole.OTHER }
            },
            errors = errors,
        )
        atomicWrite(raw.resolve("google-place-resolution-report.json"), json.encodeToString(report))
        return report
    }

    private data class Result(
        val candidate: GooglePlaceChurchCandidate? = null,
        val cacheHit: Boolean = false,
        val fetched: Boolean = false,
        val filtered: Boolean = false,
        val error: GoogleMapsResolutionError? = null,
    )

    private fun resolveOne(seed: GoogleSavedPlaceSeed): Result = runCatching {
        val page = pageSource.load(seed)
        val candidate = parser.parse(seed, page.html)
        if (GoogleSavedPlacesLists.CATHOLIC_CHURCH in seed.sourceLists && !isCatholicChurchName(candidate.name)) {
            Result(cacheHit = page.cacheHit, fetched = !page.cacheHit, filtered = true)
        } else {
            Result(candidate = candidate, cacheHit = page.cacheHit, fetched = !page.cacheHit)
        }
    }.getOrElse { error ->
        Result(error = GoogleMapsResolutionError(seed.id, seed.title, error.message ?: error::class.simpleName.orEmpty()))
    }

    private fun isCatholicChurchName(name: String): Boolean = !name.contains("宣教会") && (
        listOf("教会", "教会堂", "聖堂", "Church", "church", "天主堂", "天主堂)", "集会所", "教会（巡回）",
            "（サレジオ教会）", "(多治見修道院）", "教會", "大聖堂）", "礼拝堂", "伝道所")
            .any(name::endsWith) || name.startsWith("Igreja") || name.startsWith("Church of")
        )

    private fun atomicWrite(destination: Path, content: String) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
