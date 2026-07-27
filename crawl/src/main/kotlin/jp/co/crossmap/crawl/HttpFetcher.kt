package jp.co.crossmap.crawl

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties
import jp.co.crossmap.LightPanda

data class FetchedPage(
    val html: String,
    val finalUrl: String = "",
    val contentType: String = "",
    val statusCode: Int = 200,
    val contentHash: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    val via: String = "http",
) {
    val isNotModified: Boolean get() = statusCode == 304
}

@kotlinx.serialization.Serializable
private data class CacheMetadata(
    val sourceUrl: String,
    val fetchedAt: String,
    val contentSha256: String,
    val contentType: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
)

class HttpFetcher(
    private val cacheDir: Path? = null,
    private val cacheExpDate: Instant? = null,
    private val proxiesCsv: Path = Path.of("resources/proxies.csv"),
    private val maxAttempts: Int = 3,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val playwright: PlaywrightBrowser? = null,
    private val manualCacheDir: Path? = null,
    private val cloudflareBlockedLog: Path? = null,
    private val verbose: Boolean = false,
) {
    private val lightPanda: LightPanda get() = LightPanda()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun log(message: String) {
        if (verbose) println("[fetch] $message")
    }

    /**
     * Fetch a URL with full cache lifecycle:
     * 0. If manual cache exists (cache/web-pages-manual/) → return it
     * 1. If cache exists and fresh → return cached content
     * 2. If cache exists but stale → conditional GET (If-None-Match / If-Modified-Since)
     *    - 304 → update metadata, return cached content
     *    - 200 → store new content, return
     * 3. If no cache → unconditional GET, store, return
     * 4. On network failure → fallback chain:
     *    - Google Maps URLs: Playwright
     *    - Other URLs: LightPanda → Playwright
     * 5. If Cloudflare-blocked after all fallbacks → log to cloudflare-blocked.log
     */
    fun fetch(url: String, forceRefresh: Boolean = false): FetchedPage {
        log("URL: $url")
        if (cacheDir == null) {
            log("No cache directory — fetching directly")
            val result = fetchUncached(url)
            if (isCloudflareBlocked(result.html, result.statusCode)) logCloudflareBlocked(url, url.sha256())
            logResult(url, result, null)
            return result
        }
        Files.createDirectories(cacheDir)
        val cacheKey = url.sha256()

        if (!forceRefresh) {
            val manualHit = readManualCache(url, cacheKey)
            if (manualHit != null) {
                log("Manual cache hit: ${manualCacheDir!!.resolve("$cacheKey.html")}")
                logResult(url, manualHit, cacheDir)
                return manualHit
            }
        }

        val contentFile = cacheDir.resolve("$cacheKey.html")
        val metadataFile = cacheDir.resolve("$cacheKey.json")

        if (forceRefresh) {
            log("Force refresh — clearing cache")
            Files.deleteIfExists(contentFile)
            Files.deleteIfExists(metadataFile)
        }

        val cached = readCache(url, metadataFile)
        if (cached != null && isFresh(cached.fetchedAt)) {
            log("Cache hit (fresh, fetched ${cached.fetchedAt})")
            val result = cached.toFetchedPage(contentFile, via = "cache")
                ?: fetchUncached(url)
            logResult(url, result, cacheDir)
            return result
        }

        if (cached != null) {
            log("Cache stale (fetched ${cached.fetchedAt}) — conditional GET")
            val result = fetchConditional(url, cached, contentFile, metadataFile)
                ?: fetchUncached(url)
            if (isCloudflareBlocked(result.html, result.statusCode)) logCloudflareBlocked(url, cacheKey)
            logResult(url, result, cacheDir)
            return result
        }

        log("No cache — fetching and storing")
        val result = fetchAndStore(url, contentFile, metadataFile)
        if (isCloudflareBlocked(result.html, result.statusCode)) logCloudflareBlocked(url, cacheKey)
        logResult(url, result, cacheDir)
        return result
    }

    private fun logResult(url: String, result: FetchedPage, cacheDir: Path?) {
        log("Via: ${result.via}")
        log("Status: ${result.statusCode}")
        if (result.contentHash.isNotEmpty()) log("Content hash: ${result.contentHash}")
        if (cacheDir != null) {
            val cacheKey = url.sha256()
            log("Cache file: ${cacheDir.resolve("$cacheKey.html")}")
            log("Metadata:   ${cacheDir.resolve("$cacheKey.json")}")
        }
        log("Content: ${result.html.length} chars")
    }

    fun proxyClient(): HttpClient {
        val proxies = ProxyLoader().load(proxiesCsv)
        if (proxies.isEmpty()) return defaultClient()
        return proxyClient(proxies.random())
    }

    fun proxyClient(proxy: ProxyEntry): HttpClient =
        HttpClient.newBuilder()
            .proxy(ProxySelector.of(InetSocketAddress(proxy.ip, proxy.port.toInt())))
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    fun defaultClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private fun fetchUncached(url: String): FetchedPage {
        if (isGoogleMapsUrl(url)) {
            log("Google Maps URL — using Playwright directly")
            return fetchViaPlaywright(url)
        }
        val client = proxyClient()
        return try {
            log("Trying HttpClient...")
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val html = decodeHtml(
                response.body().toByteArray(Charsets.UTF_8),
                response.headers().firstValue("content-type").orElse(""),
            )
            if (isCloudflareBlocked(html, response.statusCode())) {
                log("Cloudflare blocked (HTTP ${response.statusCode()})")
                return FetchedPage(html = html, finalUrl = url, statusCode = response.statusCode(), via = "http")
            }
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            log("HttpClient succeeded (HTTP ${response.statusCode()})")
            toFetchedPage(url, response)
        } catch (e: Exception) {
            log("HttpClient failed: ${e.message}")
            try {
                log("Trying LightPanda...")
                val html = lightPanda.fetchHtml(url)
                log("LightPanda succeeded")
                FetchedPage(html = html, finalUrl = url, statusCode = 200, via = "lightpanda")
            } catch (e2: Exception) {
                log("LightPanda failed: ${e2.message}")
                log("Trying Playwright...")
                val result = fetchViaPlaywright(url)
                log("Playwright succeeded")
                result
            }
        }
    }

    private fun fetchViaPlaywright(url: String): FetchedPage {
        val pw = playwright ?: error("Playwright not available for $url")
        val html = pw.fetchHtml(url)
        return FetchedPage(html = html, finalUrl = url, statusCode = 200, via = "playwright")
    }

    private fun isGoogleMapsUrl(url: String): Boolean =
        url.contains("google.com/maps") || url.contains("google.co.jp/maps")

    private fun fetchConditional(
        url: String,
        cached: CacheMetadata,
        contentFile: Path,
        metadataFile: Path,
    ): FetchedPage? {
        val client = proxyClient()
        return try {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
            cached.etag?.let { builder.header("If-None-Match", it) }
            cached.lastModified?.let { builder.header("If-Modified-Since", it) }
            builder.GET()
            val response = sendWithRetry(client, builder.build())
            if (response.statusCode() == 304) {
                val now = Instant.now().toString()
                val updated = cached.copy(fetchedAt = now)
                atomicWrite(metadataFile, json.encodeToString(CacheMetadata.serializer(), updated))
                return cached.toFetchedPage(contentFile, via = "cache")
            }
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            storeResponse(url, response, contentFile, metadataFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAndStore(
        url: String,
        contentFile: Path,
        metadataFile: Path,
    ): FetchedPage {
        if (isGoogleMapsUrl(url)) {
            log("Google Maps URL — using Playwright directly")
            val fetched = fetchViaPlaywright(url)
            storeBrowserContent(url, fetched.html, contentFile, metadataFile)
            return fetched
        }
        val client = proxyClient()
        return try {
            log("Trying HttpClient...")
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
                .GET()
                .build()
            val response = sendWithRetry(client, request)
            val html = decodeHtml(
                response.body(),
                response.headers().firstValue("content-type").orElse(""),
            )
            if (isCloudflareBlocked(html, response.statusCode())) {
                log("Cloudflare blocked (HTTP ${response.statusCode()})")
                return FetchedPage(html = html, finalUrl = url, statusCode = response.statusCode(), via = "http")
            }
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            log("HttpClient succeeded (HTTP ${response.statusCode()})")
            storeResponse(url, response, contentFile, metadataFile)
        } catch (e: Exception) {
            log("HttpClient failed: ${e.message}")
            try {
                log("Trying LightPanda...")
                val html = lightPanda.fetchHtml(url)
                log("LightPanda succeeded")
                storeBrowserContent(url, html, contentFile, metadataFile)
                FetchedPage(html = html, finalUrl = url, statusCode = 200, via = "lightpanda")
            } catch (e2: Exception) {
                log("LightPanda failed: ${e2.message}")
                log("Trying Playwright...")
                val fetched = fetchViaPlaywright(url)
                log("Playwright succeeded")
                storeBrowserContent(url, fetched.html, contentFile, metadataFile)
                fetched
            }
        }
    }

    private fun storeBrowserContent(
        url: String,
        html: String,
        contentFile: Path,
        metadataFile: Path,
    ) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val hash = bytes.sha256()
        val now = Instant.now().toString()
        atomicWrite(contentFile, bytes)
        atomicWrite(
            metadataFile,
            json.encodeToString(
                CacheMetadata.serializer(),
                CacheMetadata(url, now, hash, "text/html; charset=UTF-8"),
            ),
        )
    }

    private fun storeResponse(
        url: String,
        response: HttpResponse<ByteArray>,
        contentFile: Path,
        metadataFile: Path,
    ): FetchedPage {
        val bytes = response.body()
        val hash = bytes.sha256()
        val contentType = response.headers().firstValue("content-type").orElse("")
        val html = decodeHtml(bytes, contentType)
        val now = Instant.now().toString()
        val etag = response.headers().firstValue("etag").orElse(null)
        val lastModified = response.headers().firstValue("last-modified").orElse(null)
        atomicWrite(contentFile, bytes)
        atomicWrite(
            metadataFile,
            json.encodeToString(
                CacheMetadata.serializer(),
                CacheMetadata(url, now, hash, contentType, etag, lastModified),
            ),
        )
        return FetchedPage(
            html = html,
            finalUrl = response.uri().toString(),
            contentType = contentType,
            statusCode = response.statusCode(),
            contentHash = hash,
            etag = etag,
            lastModified = lastModified,
        )
    }

    private fun readCache(url: String, metadataFile: Path): CacheMetadata? {
        if (!Files.isRegularFile(metadataFile)) return null
        return runCatching {
            val metadata = json.decodeFromString(CacheMetadata.serializer(), Files.readString(metadataFile))
            if (metadata.sourceUrl == url) metadata else null
        }.getOrNull()
    }

    private fun isFresh(fetchedAt: String): Boolean {
        if (cacheExpDate == null) return true
        return runCatching {
            val fetched = Instant.parse(fetchedAt)
            !fetched.isBefore(cacheExpDate)
        }.getOrDefault(false)
    }

    private fun CacheMetadata.toFetchedPage(contentFile: Path, via: String = "http"): FetchedPage? {
        if (!Files.isRegularFile(contentFile)) return null
        val bytes = Files.readAllBytes(contentFile)
        val html = decodeHtml(bytes, contentType)
        return FetchedPage(
            html = html,
            finalUrl = sourceUrl,
            contentType = contentType,
            statusCode = 200,
            contentHash = contentSha256,
            etag = etag,
            lastModified = lastModified,
            via = via,
        )
    }

    private fun toFetchedPage(url: String, response: HttpResponse<String>): FetchedPage =
        FetchedPage(
            html = response.body(),
            finalUrl = response.uri().toString(),
            contentType = response.headers().firstValue("content-type").orElse(""),
            statusCode = response.statusCode(),
            etag = response.headers().firstValue("etag").orElse(null),
            lastModified = response.headers().firstValue("last-modified").orElse(null),
        )

    private fun sendWithRetry(client: HttpClient, request: HttpRequest): HttpResponse<ByteArray> {
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

    private fun atomicWrite(path: Path, content: ByteArray) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.write(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun readManualCache(url: String, cacheKey: String): FetchedPage? {
        val dir = manualCacheDir ?: return null
        val manualFile = dir.resolve("$cacheKey.html")
        if (!Files.isRegularFile(manualFile)) return null
        val bytes = Files.readAllBytes(manualFile)
        val html = decodeHtml(bytes, "text/html; charset=UTF-8")
        println("[manual-cache] Using manually provided HTML for $url (${cacheKey}.html)")
        return FetchedPage(html = html, finalUrl = url, statusCode = 200, contentHash = cacheKey, via = "manual")
    }

    private fun isCloudflareBlocked(html: String, statusCode: Int): Boolean {
        if (statusCode == 403 || statusCode == 503) {
            val indicators = listOf(
                "Just a moment",
                "Checking your browser",
                "Attention Required",
                "challenge-platform",
                "Verify you are human",
                "Enable JavaScript and cookies to continue",
                "Ray ID",
                "cf-browser-verification",
                "cloudflare-static",
            )
            if (indicators.any { html.contains(it, ignoreCase = true) }) return true
        }
        return false
    }

    private fun logCloudflareBlocked(url: String, cacheKey: String) {
        val logFile = cloudflareBlockedLog ?: return
        Files.createDirectories(logFile.parent)
        val entry = "[${Instant.now()}] $url\n  cache-key: $cacheKey.html\n  manual-path: $cacheDir/../web-pages-manual/${cacheKey}.html\n\n"
        Files.writeString(logFile, entry, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        println("[cloudflare] BLOCKED: $url — saved to log: $logFile")
        println("[cloudflare] To fix: open URL in browser → copy page HTML → save as $cacheDir/../web-pages-manual/${cacheKey}.html")
    }

    companion object {
        fun decodeHtml(bytes: ByteArray, contentType: String): String {
            val asciiHead = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
            val charsetPattern = Regex("charset\\s*=\\s*[\"']?([^\"';>\\s]+)", RegexOption.IGNORE_CASE)
            val charset = sequenceOf(
                charsetPattern.find(contentType)?.groupValues?.get(1),
                charsetPattern.find(asciiHead)?.groupValues?.get(1),
                "UTF-8",
            ).filterNotNull().mapNotNull { name ->
                val compatibleName = when (name.lowercase().replace('-', '_')) {
                    "shift_jis", "shiftjis", "sjis" -> "windows-31j"
                    else -> name
                }
                runCatching { Charset.forName(compatibleName) }.getOrNull()
            }.first()
            return bytes.toString(charset)
        }

        fun loadConfig(
            workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        ): HttpFetcher? {
            val propertiesFile = generateSequence(workingDirectory.toAbsolutePath().normalize()) { it.parent }
                .map { it.resolve("local.properties") }
                .firstOrNull(Files::isRegularFile) ?: return null
            val properties = Properties().apply { Files.newInputStream(propertiesFile).use(::load) }
            val dateStr = properties.getProperty("crossmap.httpFetcherCacheExpDate")?.trim()?.takeIf(String::isNotBlank)
                ?: return null
            val expDate = runCatching {
                LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant()
            }.getOrNull() ?: return null
            return HttpFetcher(cacheExpDate = expDate)
        }
    }
}
