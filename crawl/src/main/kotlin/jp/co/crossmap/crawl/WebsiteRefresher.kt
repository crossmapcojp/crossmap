package jp.co.crossmap.crawl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledContentType
import jp.co.crossmap.CrawledPage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

data class RefreshReport(val churches: Int, val fetched: Int, val unchanged: Int, val errors: Int)

class WebsiteRefresher(
    private val maxConcurrency: Int = 6,
    private val hostDelayMillis: Long = 250,
    private val maxAttempts: Int = 3,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val hostLastRequest = ConcurrentHashMap<String, Long>()
    private val robots = ConcurrentHashMap<String, List<String>>()
    private var urlCache: Map<String, String> = emptyMap()

    fun refresh(
        resourcesRoot: Path,
        catalogFile: Path = resourcesRoot.resolve("catalog/churches.json"),
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): RefreshReport {
        require(maxConcurrency in 1..32) { "maxConcurrency must be between 1 and 32" }
        val webCache = CrossmapPaths(resourcesRoot, cacheRoot).churchWebPages
        val manifestFile = webCache.resolve("manifest.json")
        val urlCacheFile = webCache.resolve("url-cache-map.json")
        urlCache = if (Files.isRegularFile(urlCacheFile)) json.decodeFromString(Files.readString(urlCacheFile)) else emptyMap()
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile))
        val oldManifest = if (Files.isRegularFile(manifestFile)) {
            json.decodeFromString<List<CrawlManifestEntry>>(Files.readString(manifestFile))
        } else emptyList()
        val manifestByUrl = oldManifest.associateBy { it.churchId to it.requestedUrl }
        val executor = Executors.newFixedThreadPool(maxConcurrency)
        val results = try {
            executor.invokeAll(churches.map { church -> Callable { refreshChurch(church, webCache, manifestByUrl) } })
                .map { it.get() }
        } finally {
            executor.shutdown()
        }
        atomicWrite(catalogFile, json.encodeToString(results.map { it.church }.sortedBy { it.id }))
        val retained = oldManifest.filter { old -> results.none { result -> result.entries.any { it.churchId == old.churchId && it.requestedUrl == old.requestedUrl } } }
        atomicWrite(manifestFile, json.encodeToString((retained + results.flatMap { it.entries }).sortedWith(compareBy({ it.churchId }, { it.requestedUrl }))))
        return RefreshReport(
            churches = churches.size,
            fetched = results.sumOf { it.fetched },
            unchanged = results.sumOf { it.unchanged },
            errors = results.sumOf { it.errors },
        )
    }

    private data class ChurchRefresh(
        val church: ChurchRecord,
        val entries: List<CrawlManifestEntry>,
        val fetched: Int,
        val unchanged: Int,
        val errors: Int,
    )

    private fun refreshChurch(
        church: ChurchRecord,
        webCache: Path,
        previous: Map<Pair<String, String>, CrawlManifestEntry>,
    ): ChurchRefresh {
        val home = church.websiteUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return ChurchRefresh(church, emptyList(), 0, 0, 0)
        val queue = ArrayDeque<String>().apply {
            add(home)
            church.pages.map { it.url }.filter { it != home }.forEach(::add)
        }
        val visited = linkedSetOf<String>()
        val pages = mutableListOf<CrawledPage>()
        val entries = mutableListOf<CrawlManifestEntry>()
        var fetched = 0
        var unchanged = 0
        var errors = 0
        while (queue.isNotEmpty() && visited.size < 6) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue
            if (url.sha1() !in urlCache && !allowed(url)) continue
            val result = fetch(church.id, url, previous[church.id to url], webCache)
            entries += result.entry
            result.page?.let { page ->
                pages += page
                if (url == home) discoverLinks(home, page.text, result.html.orEmpty()).forEach(queue::addLast)
            }
            when {
                result.entry.error != null -> errors++
                result.entry.status == 304 -> unchanged++
                else -> fetched++
            }
        }
        val finalPages = if (pages.isEmpty() && unchanged > 0) church.pages else pages
        return ChurchRefresh(church.copy(pages = finalPages, updatedAt = Instant.now().toString()), entries, fetched, unchanged, errors)
    }

    private data class FetchResult(val entry: CrawlManifestEntry, val page: CrawledPage?, val html: String?)

    private fun fetch(churchId: String, url: String, previous: CrawlManifestEntry?, webCache: Path): FetchResult {
        if (previous == null) cached(churchId, url, webCache)?.let { return it }
        return runCatching {
            throttle(URI(url).host.orEmpty())
            val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
                .GET()
            previous?.etag?.let { builder.header("If-None-Match", it) }
            previous?.lastModified?.let { builder.header("If-Modified-Since", it) }
            val response = sendWithRetry(builder.build())
            if (response.statusCode() == 304 && previous != null) {
                return FetchResult(previous.copy(status = 304, fetchedAt = Instant.now().toString()), null, null)
            }
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            val contentType = response.headers().firstValue("content-type").orElse("")
            require(contentType.contains("html", ignoreCase = true) || contentType.isBlank()) { "Unsupported content type $contentType" }
            val bytes = response.body()
            val hash = bytes.sha256()
            val relative = Path.of("pages", "$hash.html")
            val cache = webCache.resolve(relative)
            Files.createDirectories(cache.parent)
            if (!Files.exists(cache)) Files.write(cache, bytes)
            val html = bytes.toString(Charsets.UTF_8)
            val document = Jsoup.parse(html, response.uri().toString())
            document.select("script,style,noscript,template").remove()
            val now = Instant.now().toString()
            val page = CrawledPage(
                url = url,
                finalUrl = response.uri().toString(),
                title = document.title(),
                text = document.body()?.text().orEmpty(),
                fetchedAt = now,
                contentHash = hash,
                status = response.statusCode(),
                contentType = CrawledContentType.WEBSITE_PAGE,
            )
            FetchResult(
                CrawlManifestEntry(
                    churchId, url, response.uri().toString(), relative.toString(), now, response.statusCode(), hash,
                    etag = response.headers().firstValue("etag").orElse(null),
                    lastModified = response.headers().firstValue("last-modified").orElse(null),
                ),
                page,
                html,
            )
        }.getOrElse { error ->
            val now = Instant.now().toString()
            FetchResult(
                CrawlManifestEntry(
                    churchId, url, url, previous?.cachePath.orEmpty(), now, 0, previous?.contentHash.orEmpty(),
                    error = error.message ?: error::class.simpleName,
                    etag = previous?.etag,
                    lastModified = previous?.lastModified,
                ),
                null,
                null,
            )
        }
    }

    private fun cached(churchId: String, url: String, webCache: Path): FetchResult? {
        val hash = urlCache[url.sha1()] ?: return null
        val relative = Path.of("pages", "$hash.html")
        val file = webCache.resolve(relative)
        if (!Files.isRegularFile(file)) return null
        val bytes = Files.readAllBytes(file)
        val html = bytes.toString(Charsets.UTF_8)
        val document = Jsoup.parse(html, url)
        document.select("script,style,noscript,template").remove()
        val now = Instant.now().toString()
        return FetchResult(
            CrawlManifestEntry(
                churchId = churchId,
                requestedUrl = url,
                finalUrl = url,
                cachePath = relative.toString(),
                fetchedAt = now,
                status = 200,
                contentHash = hash,
                acquisition = "IMPORTED_CACHE",
            ),
            CrawledPage(
                url = url,
                title = document.title(),
                text = document.body()?.text().orEmpty(),
                fetchedAt = now,
                contentHash = hash,
                contentType = CrawledContentType.WEBSITE_PAGE,
            ),
            html,
        )
    }

    private fun sendWithRetry(request: HttpRequest): HttpResponse<ByteArray> {
        require(maxAttempts in 1..5) { "maxAttempts must be between 1 and 5" }
        var failure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() < 500 || attempt == maxAttempts - 1) return response
                failure = IllegalStateException("HTTP ${response.statusCode()}")
            } catch (error: Throwable) {
                failure = error
                if (attempt == maxAttempts - 1) throw error
            }
            Thread.sleep(250L * (1L shl attempt))
        }
        throw requireNotNull(failure)
    }

    private fun discoverLinks(home: String, text: String, html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val origin = URI(home)
        val accepted = Regex("about|belief|faith|access|contact|ministry|church|教会|私たち|信仰|アクセス|集会|礼拝", RegexOption.IGNORE_CASE)
        return Jsoup.parse(html, home).select("a[href]").asSequence()
            .map { it.absUrl("href").substringBefore('#') }
            .filter { it.startsWith("http") }
            .filter { runCatching { URI(it).host.equals(origin.host, ignoreCase = true) }.getOrDefault(false) }
            .filter { accepted.containsMatchIn(it) || accepted.containsMatchIn(text) && it == home }
            .distinct().take(5).toList()
    }

    private fun allowed(url: String): Boolean {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.authority}"
        val disallowed = robots.computeIfAbsent(origin) { fetchRobots(origin) }
        return disallowed.none { it.isNotBlank() && uri.path.startsWith(it) }
    }

    private fun fetchRobots(origin: String): List<String> = runCatching {
        val request = HttpRequest.newBuilder(URI("$origin/robots.txt")).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "CrossmapCrawler/1.0").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return@runCatching emptyList()
        var applies = false
        buildList {
            response.body().lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                when {
                    line.startsWith("User-agent:", true) -> applies = line.substringAfter(':').trim() == "*"
                    applies && line.startsWith("Disallow:", true) -> add(line.substringAfter(':').trim())
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun throttle(host: String) = synchronized(hostLocks.computeIfAbsent(host) { Any() }) {
        val now = System.currentTimeMillis()
        val wait = hostDelayMillis - (now - (hostLastRequest[host] ?: 0L))
        if (wait > 0) Thread.sleep(wait)
        hostLastRequest[host] = System.currentTimeMillis()
    }

    private fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("${path.fileName}.part")
        Files.writeString(temp, content)
        runCatching { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}
