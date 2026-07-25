package jp.co.crossmap.crawl.denomination

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SocialProfile
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
    val email: String = "",
    val socialProfiles: List<SocialProfile> = emptyList(),
    val denominationChurchListDetailPage: String = "",
    val ministers: List<ChurchMinister> = emptyList(),
    val membershipStatus: OfficialChurchMembershipStatus = OfficialChurchMembershipStatus.LISTED,
    val note: String = "",
    val localizedNames: List<LocalizedName> = emptyList(),
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

/** A denomination-specific parser for an authoritative, official church directory. */
interface DenominationChurchListCrawler {
    val denominationId: String
    val denominationName: String
    val sourceUrl: String
    val pageUrls: List<String>
    val outputFileName: String

    fun parse(html: String): List<OfficialDenominationChurch>

    fun parsePage(url: String, html: String): List<OfficialDenominationChurch> = parse(html)

    fun parseLoadedPage(page: LoadedDenominationChurchPage): List<OfficialDenominationChurch> =
        parsePage(page.url, page.html)

    fun merge(churches: List<OfficialDenominationChurch>): List<OfficialDenominationChurch> =
        churches.distinctBy { Triple(it.name, it.address, it.jurisdiction) }

    fun parseDetailPage(
        church: OfficialDenominationChurch,
        html: String,
    ): OfficialDenominationChurch = church
}

/** An official church directory whose complete list is contained in one page. */
interface SinglePageDenominationChurchListCrawler : DenominationChurchListCrawler {
    override val pageUrls: List<String>
        get() = listOf(sourceUrl)
}

/** An official church directory whose complete list must be assembled from multiple pages. */
interface MultiPageDenominationChurchListCrawler : DenominationChurchListCrawler {
    val sourceUrls: List<String>

    override val pageUrls: List<String>
        get() = sourceUrls

}

data class LoadedDenominationChurchPage(
    val url: String,
    val html: String,
    val fetchedAt: String,
    val cacheHit: Boolean,
    val bytes: ByteArray = html.toByteArray(Charsets.UTF_8),
)

fun interface DenominationChurchPageLoader {
    fun load(url: String, forceRefresh: Boolean): LoadedDenominationChurchPage
}

@Serializable
private data class DenominationChurchPageCacheMetadata(
    val sourceUrl: String,
    val fetchedAt: String,
    val contentSha256: String,
    val contentType: String = "",
)

internal object DenominationDirectoryCachePolicy {
    val maxAge: Duration = Duration.ofDays(30)

    fun isFresh(fetchedAt: String, now: Instant = Instant.now()): Boolean = runCatching {
        !Instant.parse(fetchedAt).isBefore(now.minus(maxAge))
    }.getOrDefault(false)
}

class CachedHttpDenominationChurchPageLoader(
    private val cacheDirectory: Path,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
    private val now: () -> Instant = Instant::now,
) : DenominationChurchPageLoader {
    override fun load(url: String, forceRefresh: Boolean): LoadedDenominationChurchPage {
        val cacheKey = url.sha256()
        val pageFile = cacheDirectory.resolve("$cacheKey.body")
        val legacyPageFile = cacheDirectory.resolve("$cacheKey.html")
        val metadataFile = cacheDirectory.resolve("$cacheKey.json")
        if (forceRefresh) {
            Files.deleteIfExists(pageFile)
            Files.deleteIfExists(legacyPageFile)
            Files.deleteIfExists(metadataFile)
        }
        if ((Files.isRegularFile(pageFile) || Files.isRegularFile(legacyPageFile)) && Files.isRegularFile(metadataFile)) {
            val metadata = json.decodeFromString<DenominationChurchPageCacheMetadata>(Files.readString(metadataFile))
            if (metadata.sourceUrl == url && DenominationDirectoryCachePolicy.isFresh(metadata.fetchedAt, now())) {
                val bytes = if (Files.isRegularFile(pageFile)) Files.readAllBytes(pageFile) else Files.readAllBytes(legacyPageFile)
                val html = if (Files.isRegularFile(pageFile)) decodeHtml(bytes, metadata.contentType) else bytes.toString(Charsets.UTF_8)
                return LoadedDenominationChurchPage(url, html, metadata.fetchedAt, cacheHit = true, bytes = bytes)
            }
        }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $url" }
        val fetchedAt = now().toString()
        val contentType = response.headers().firstValue("content-type").orElse("")
        val html = decodeHtml(response.body(), contentType)
        require(response.body().isNotEmpty()) { "Official denomination directory returned an empty page: $url" }
        Files.createDirectories(cacheDirectory)
        atomicWrite(pageFile, response.body())
        atomicWrite(
            metadataFile,
            json.encodeToString(DenominationChurchPageCacheMetadata(url, fetchedAt, response.body().sha256(), contentType)),
        )
        return LoadedDenominationChurchPage(response.uri().toString(), html, fetchedAt, cacheHit = false, bytes = response.body())
    }

    private fun decodeHtml(bytes: ByteArray, contentType: String): String {
        val asciiHead = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^\"';>\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
            ?: Regex("charset\\s*=\\s*[\"']?([^\"';>\\s]+)", RegexOption.IGNORE_CASE)
                .find(asciiHead)?.groupValues?.get(1)
            ?: "UTF-8"
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return bytes.toString(charset)
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun atomicWrite(path: Path, content: ByteArray) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.write(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}

data class DenominationChurchListCrawlResult(
    val list: OfficialDenominationChurchList,
    val outputFile: Path,
    val cacheHit: Boolean,
    val pageCount: Int,
    val errors: Int = 0,
)

class DenominationChurchListCrawlerRunner(
    private val pageLoader: DenominationChurchPageLoader? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    private val personalNameTransliterators = mutableMapOf<Path, PersonalNameTransliterator>()

    fun crawl(
        crawler: DenominationChurchListCrawler,
        resourcesRoot: Path,
        cacheRoot: Path,
        forceRefresh: Boolean = false,
    ): DenominationChurchListCrawlResult {
        require(crawler.pageUrls.isNotEmpty()) { "${crawler.denominationId} official directory has no page URLs" }
        if (forceRefresh) {
            crawler.pageUrls.forEach { invalidateLegacyCache(cacheRoot.resolve("church-web-pages"), it) }
        }
        val loader = pageLoader ?: CachedHttpDenominationChurchPageLoader(
            cacheRoot.resolve("denomination-church-lists/${crawler.denominationId.lowercase()}"),
            json = json,
        )
        val listPages = crawler.pageUrls.map { loadPage(it, resourcesRoot, loader, forceRefresh) }
        var churches = crawler.merge(listPages.flatMap(crawler::parseLoadedPage))
        var detailErrors = 0
        val detailPages = churches.mapNotNull { church ->
            church.denominationChurchListDetailPage.takeIf(String::isNotBlank)?.let { url ->
                runCatching { church to loadPage(url, resourcesRoot, loader, forceRefresh) }
                    .getOrElse {
                        detailErrors++
                        null
                    }
            }
        }
        val detailsByUrl = detailPages.associate { (church, page) -> church.denominationChurchListDetailPage to page }
        churches = churches.map { church ->
            detailsByUrl[church.denominationChurchListDetailPage]
                ?.let { crawler.parseDetailPage(church, it.html) }
                ?: church
        }
        val personalNamesDirectory = resourcesRoot.resolve("personalnames").toAbsolutePath().normalize()
        if (Files.isRegularFile(personalNamesDirectory.resolve("README.md"))) {
            val transliterator = personalNameTransliterators.getOrPut(personalNamesDirectory) {
                PersonalNameTransliterator.load(personalNamesDirectory)
            }
            churches = churches.map { church ->
                church.copy(ministers = church.ministers.map(transliterator::localize))
            }
        }
        val pages = listPages + detailPages.map { it.second }
        require(churches.isNotEmpty()) { "${crawler.denominationId} official directory contained no church rows" }
        val list = OfficialDenominationChurchList(
            denominationId = crawler.denominationId,
            denominationName = crawler.denominationName,
            sourceUrl = crawler.sourceUrl,
            fetchedAt = pages.maxOf(LoadedDenominationChurchPage::fetchedAt),
            churches = churches,
        )
        val output = resourcesRoot.resolve("crawl/${crawler.outputFileName}")
        Files.createDirectories(output.parent)
        atomicWrite(output, json.encodeToString(list))
        return DenominationChurchListCrawlResult(
            list = list,
            outputFile = output,
            cacheHit = pages.all(LoadedDenominationChurchPage::cacheHit),
            pageCount = pages.size,
            errors = detailErrors,
        )
    }

    private fun loadPage(
        url: String,
        resourcesRoot: Path,
        loader: DenominationChurchPageLoader,
        forceRefresh: Boolean,
    ): LoadedDenominationChurchPage {
        if (!url.startsWith(RESOURCE_URL_PREFIX)) return loader.load(url, forceRefresh)
        val relativePath = url.removePrefix(RESOURCE_URL_PREFIX)
        val normalizedRoot = resourcesRoot.toAbsolutePath().normalize()
        val file = normalizedRoot.resolve(relativePath).normalize()
        require(file.startsWith(normalizedRoot)) { "Fixture URL escapes the resources directory: $url" }
        require(Files.isRegularFile(file)) { "Missing committed denomination fixture: $file" }
        val bytes = Files.readAllBytes(file)
        return LoadedDenominationChurchPage(
            url = url,
            html = bytes.toString(Charsets.UTF_8),
            fetchedAt = Files.getLastModifiedTime(file).toInstant().toString(),
            cacheHit = true,
            bytes = bytes,
        )
    }

    private fun invalidateLegacyCache(cacheDirectory: Path, url: String) {
        if (url.startsWith(RESOURCE_URL_PREFIX)) return
        val mapFile = cacheDirectory.resolve("url-cache-map.json")
        if (!Files.isRegularFile(mapFile)) return
        val map = json.decodeFromString<Map<String, String>>(Files.readString(mapFile)).toMutableMap()
        val removedContentHash = map.remove(url.sha1()) ?: return
        atomicWrite(mapFile, json.encodeToString<Map<String, String>>(map))
        if (removedContentHash !in map.values) {
            Files.deleteIfExists(cacheDirectory.resolve("pages/$removedContentHash.html"))
        }
    }

    private companion object {
        const val RESOURCE_URL_PREFIX = "resource:"
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
private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
private fun String.digest(algorithm: String): String = MessageDigest.getInstance(algorithm)
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }
