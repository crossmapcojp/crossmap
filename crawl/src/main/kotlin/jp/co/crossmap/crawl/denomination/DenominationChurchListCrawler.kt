package jp.co.crossmap.crawl.denomination

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class OfficialChurchMembershipStatus { LISTED, PENDING }

@Serializable
data class OfficialDenominationChurch(
    val name: String,
    val address: String = "",
    val jurisdiction: String = "",
    val phone: String = "",
    val fax: String = "",
    val websiteUrl: String = "",
    val membershipStatus: OfficialChurchMembershipStatus = OfficialChurchMembershipStatus.LISTED,
    val note: String = "",
) {
    val eligibleForDenominationEvidence: Boolean
        get() = membershipStatus == OfficialChurchMembershipStatus.LISTED
}

@Serializable
data class OfficialDenominationChurchList(
    val denominationId: String,
    val denominationName: String,
    val sourceUrl: String,
    val fetchedAt: String,
    val churches: List<OfficialDenominationChurch>,
)

/** A denomination-specific parser for one authoritative, official church directory. */
interface DenominationChurchListCrawler {
    val denominationId: String
    val denominationName: String
    val sourceUrl: String
    val outputFileName: String

    fun parse(html: String): List<OfficialDenominationChurch>
}

data class LoadedDenominationChurchPage(
    val url: String,
    val html: String,
    val fetchedAt: String,
    val cacheHit: Boolean,
)

fun interface DenominationChurchPageLoader {
    fun load(url: String, forceRefresh: Boolean): LoadedDenominationChurchPage
}

@Serializable
private data class DenominationChurchPageCacheMetadata(
    val sourceUrl: String,
    val fetchedAt: String,
    val contentSha256: String,
)

class CachedHttpDenominationChurchPageLoader(
    private val cacheDirectory: Path,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) : DenominationChurchPageLoader {
    override fun load(url: String, forceRefresh: Boolean): LoadedDenominationChurchPage {
        val pageFile = cacheDirectory.resolve("source.html")
        val metadataFile = cacheDirectory.resolve("source.json")
        if (forceRefresh) {
            Files.deleteIfExists(pageFile)
            Files.deleteIfExists(metadataFile)
        }
        if (Files.isRegularFile(pageFile) && Files.isRegularFile(metadataFile)) {
            val metadata = json.decodeFromString<DenominationChurchPageCacheMetadata>(Files.readString(metadataFile))
            if (metadata.sourceUrl == url) {
                return LoadedDenominationChurchPage(url, Files.readString(pageFile), metadata.fetchedAt, cacheHit = true)
            }
        }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $url" }
        val fetchedAt = Instant.now().toString()
        val html = response.body()
        require(html.isNotBlank()) { "Official denomination directory returned an empty page: $url" }
        Files.createDirectories(cacheDirectory)
        atomicWrite(pageFile, html)
        atomicWrite(
            metadataFile,
            json.encodeToString(DenominationChurchPageCacheMetadata(response.uri().toString(), fetchedAt, html.sha256())),
        )
        return LoadedDenominationChurchPage(response.uri().toString(), html, fetchedAt, cacheHit = false)
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}

data class DenominationChurchListCrawlResult(
    val list: OfficialDenominationChurchList,
    val outputFile: Path,
    val cacheHit: Boolean,
)

class DenominationChurchListCrawlerRunner(
    private val pageLoader: DenominationChurchPageLoader? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun crawl(
        crawler: DenominationChurchListCrawler,
        resourcesRoot: Path,
        cacheRoot: Path,
        forceRefresh: Boolean = false,
    ): DenominationChurchListCrawlResult {
        if (forceRefresh) invalidateLegacyCache(cacheRoot.resolve("church-web-pages"), crawler.sourceUrl)
        val loader = pageLoader ?: CachedHttpDenominationChurchPageLoader(
            cacheRoot.resolve("denomination-church-lists/${crawler.denominationId.lowercase()}"),
            json = json,
        )
        val page = loader.load(crawler.sourceUrl, forceRefresh)
        val churches = crawler.parse(page.html)
            .distinctBy { Triple(it.name, it.address, it.jurisdiction) }
        require(churches.isNotEmpty()) { "${crawler.denominationId} official directory contained no church rows" }
        val list = OfficialDenominationChurchList(
            denominationId = crawler.denominationId,
            denominationName = crawler.denominationName,
            sourceUrl = crawler.sourceUrl,
            fetchedAt = page.fetchedAt,
            churches = churches,
        )
        val output = resourcesRoot.resolve("crawl/${crawler.outputFileName}")
        Files.createDirectories(output.parent)
        atomicWrite(output, json.encodeToString(list))
        return DenominationChurchListCrawlResult(list, output, page.cacheHit)
    }

    private fun invalidateLegacyCache(cacheDirectory: Path, url: String) {
        val mapFile = cacheDirectory.resolve("url-cache-map.json")
        if (!Files.isRegularFile(mapFile)) return
        val map = json.decodeFromString<Map<String, String>>(Files.readString(mapFile)).toMutableMap()
        val removedContentHash = map.remove(url.sha1()) ?: return
        atomicWrite(mapFile, json.encodeToString<Map<String, String>>(map))
        if (removedContentHash !in map.values) {
            Files.deleteIfExists(cacheDirectory.resolve("pages/$removedContentHash.html"))
        }
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}

private fun String.sha1(): String = digest("SHA-1")
private fun String.sha256(): String = digest("SHA-256")
private fun String.digest(algorithm: String): String = MessageDigest.getInstance(algorithm)
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }
