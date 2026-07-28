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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.ChurchWebsitePolicy
import jp.co.crossmap.CrawledContentType
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.Language
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import jp.co.crossmap.localizedDomainText
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

data class GooglePlacesCrawlReport(val churches: Int, val fetched: Int, val unchanged: Int, val errors: Int)

class ChurchWebsiteCrawler(
    private val maxConcurrency: Int = 32,
    private val hostDelayMillis: Long = 250,
    private val maxAttempts: Int = 3,
    private val maxDepth: Int = 1,
    private val maxPagesPerChurch: Int = 12,
    private val cacheFreshness: Duration = Duration.ofDays(30),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
    client: HttpClient? = null,
) {
    private val client: HttpClient = client ?: HttpFetcher().proxyClient()
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val hostLastRequest = ConcurrentHashMap<String, Long>()
    private val robots = ConcurrentHashMap<String, List<String>>()
    private val fetches = ConcurrentHashMap<String, CompletableFuture<FetchResult>>()
    private var urlCache: Map<String, String> = emptyMap()

    fun crawl(
        resourcesRoot: Path,
        catalogFile: Path = resourcesRoot.resolve("catalog/churches.json"),
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): GooglePlacesCrawlReport {
        require(maxConcurrency in 1..32) { "maxConcurrency must be between 1 and 32" }
        require(maxDepth in 0..3) { "maxDepth must be between 0 and 3" }
        require(maxPagesPerChurch in 1..100) { "maxPagesPerChurch must be between 1 and 100" }
        require(!cacheFreshness.isNegative) { "cacheFreshness must not be negative" }
        fetches.clear()
        val webCache = CrossmapPaths(resourcesRoot, cacheRoot).churchWebPages
        val manifestFile = webCache.resolve("manifest.json")
        val urlCacheFile = webCache.resolve("url-cache-map.json")
        urlCache = if (Files.isRegularFile(urlCacheFile)) json.decodeFromString(Files.readString(urlCacheFile)) else emptyMap()
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile))
        val websitePolicy = ExcludedChurchListingDomains.policy(resourcesRoot)
        val oldManifest = if (Files.isRegularFile(manifestFile)) {
            json.decodeFromString<List<CrawlManifestEntry>>(Files.readString(manifestFile))
        } else emptyList()
        val manifestByUrl = oldManifest.associateBy { it.churchId to it.requestedUrl }
        val orderedChurches = hostFairOrder(churches, websitePolicy)
        val crawlableHomes = churches.mapNotNull { church ->
            websitePolicy.publicWebsiteUrl(church).takeIf(websitePolicy::isCrawlableChurchWebsite)?.let { church to it }
        }
        val freshHomes = crawlableHomes.count { (church, url) -> manifestByUrl[church.id to url]?.let(::isFresh) == true }
        val missingHomes = crawlableHomes.count { (church, url) -> church.id to url !in manifestByUrl }
        val socialHomes = churches.count { websitePolicy.isSocialPlatform(it.websiteUrl) }
        val uniqueHosts = crawlableHomes.mapNotNull { (_, url) -> runCatching { URI(url).host }.getOrNull() }.distinct().size
        println(
            "website_refresh event=start churches=${churches.size} crawlable_homes=${crawlableHomes.size} " +
                "fresh_homes=$freshHomes stale_homes=${crawlableHomes.size - freshHomes - missingHomes} " +
                "missing_homes=$missingHomes social_homes_skipped=$socialHomes unique_hosts=$uniqueHosts " +
                "concurrency=$maxConcurrency max_depth=$maxDepth max_pages_per_church=$maxPagesPerChurch " +
                    "cache_freshness_hours=${cacheFreshness.toHours()}",
        )
        val startedAt = System.nanoTime()
        val completed = AtomicInteger()
        val totalFetched = AtomicInteger()
        val totalUnchanged = AtomicInteger()
        val totalErrors = AtomicInteger()
        val timings = ConcurrentLinkedQueue<ChurchTiming>()
        val executor = Executors.newFixedThreadPool(maxConcurrency)
        val results = try {
            executor.invokeAll(orderedChurches.map { church ->
                Callable {
                    val churchStartedAt = System.nanoTime()
                    refreshChurch(church, webCache, manifestByUrl, websitePolicy).also { result ->
                        val elapsedMillis = (System.nanoTime() - churchStartedAt) / 1_000_000
                        totalFetched.addAndGet(result.fetched)
                        totalUnchanged.addAndGet(result.unchanged)
                        totalErrors.addAndGet(result.errors)
                        val host = crawlHost(church, websitePolicy).orEmpty()
                        timings += ChurchTiming(host, elapsedMillis, result.fetched + result.unchanged + result.errors)
                        if (elapsedMillis >= 10_000) {
                            println(
                                "website_refresh event=slow_church church_id=${church.id} host=$host " +
                                    "duration_ms=$elapsedMillis fetched=${result.fetched} unchanged=${result.unchanged} errors=${result.errors}",
                            )
                        }
                        val done = completed.incrementAndGet()
                        if (done % 250 == 0 || done == churches.size) {
                            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                            println(
                                "website_refresh event=progress completed=$done total=${churches.size} " +
                                    "elapsed_seconds=${"%.3f".format(java.util.Locale.ROOT, elapsedSeconds)} " +
                                    "fetched=${totalFetched.get()} unchanged=${totalUnchanged.get()} errors=${totalErrors.get()}",
                            )
                        }
                    }
                }
            })
                .map { it.get() }
        } finally {
            executor.shutdown()
        }
        atomicWrite(catalogFile, json.encodeToString(results.map { it.church }.sortedBy { it.id }))
        val retained = oldManifest.filter { old -> results.none { result -> result.entries.any { it.churchId == old.churchId && it.requestedUrl == old.requestedUrl } } }
        atomicWrite(manifestFile, json.encodeToString((retained + results.flatMap { it.entries }).sortedWith(compareBy({ it.churchId }, { it.requestedUrl }))))
        timings.groupBy(ChurchTiming::host)
            .map { (host, values) -> HostTiming(host, values.sumOf(ChurchTiming::elapsedMillis), values.sumOf(ChurchTiming::requests), values.size) }
            .sortedByDescending(HostTiming::elapsedMillis)
            .take(20)
            .forEach { timing ->
                println(
                    "website_refresh event=slow_host host=${timing.host} aggregate_duration_ms=${timing.elapsedMillis} " +
                        "churches=${timing.churches} requests=${timing.requests}",
                )
            }
        return GooglePlacesCrawlReport(
            churches = churches.size,
            fetched = results.sumOf { it.fetched },
            unchanged = results.sumOf { it.unchanged },
            errors = results.sumOf { it.errors },
        )
    }

    private data class ChurchTiming(val host: String, val elapsedMillis: Long, val requests: Int)

    private data class HostTiming(val host: String, val elapsedMillis: Long, val requests: Int, val churches: Int)

    private fun hostFairOrder(churches: List<ChurchRecord>, websitePolicy: ChurchWebsitePolicy): List<ChurchRecord> {
        val byHost = linkedMapOf<String, MutableList<ChurchRecord>>()
        churches.forEach { church ->
            val host = crawlHost(church, websitePolicy) ?: "non-crawlable:${church.id}"
            byHost.getOrPut(host, ::mutableListOf) += church
        }
        val largestGroup = byHost.values.maxOfOrNull(List<ChurchRecord>::size) ?: 0
        return buildList(churches.size) {
            repeat(largestGroup) { index ->
                byHost.values.forEach { group -> group.getOrNull(index)?.let(::add) }
            }
        }
    }

    private fun crawlHost(church: ChurchRecord, websitePolicy: ChurchWebsitePolicy): String? {
        val url = websitePolicy.publicWebsiteUrl(church).takeIf(websitePolicy::isCrawlableChurchWebsite) ?: return null
        return runCatching { URI(url).host?.lowercase() }.getOrNull()
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
        websitePolicy: ChurchWebsitePolicy,
    ): ChurchRefresh {
        val sanitizedChurch = church.copy(
            websiteUrl = websitePolicy.publicWebsiteUrl(church),
            pages = church.pages.filter { websitePolicy.isCrawlableChurchWebsite(it.url) },
        )
        val home = sanitizedChurch.websiteUrl.takeIf(websitePolicy::isCrawlableChurchWebsite)
            ?: return ChurchRefresh(sanitizedChurch, emptyList(), 0, 0, 0)
        data class QueuedUrl(val url: String, val depth: Int)
        val queue = ArrayDeque<QueuedUrl>().apply { add(QueuedUrl(home, 0)) }
        val visited = linkedSetOf<String>()
        val pages = mutableListOf<CrawledPage>()
        val officialNames = mutableListOf<LocalizedName>()
        val entries = mutableListOf<CrawlManifestEntry>()
        var fetched = 0
        var unchanged = 0
        var errors = 0
        while (queue.isNotEmpty() && visited.size < maxPagesPerChurch) {
            val (url, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            if (!visited.add(url)) continue
            val previousEntry = previous[church.id to url]
            if (url.sha1() !in urlCache && previousEntry?.let(::isFresh) != true && !allowed(url)) continue
            val result = fetch(church.id, url, previousEntry, webCache)
            entries += result.entry
            result.page?.let { page ->
                val outgoingLinks = discoverLinks(home, page.finalUrl, result.html.orEmpty())
                val officialName = ChurchWebsiteOfficialNameExtractor.extract(page.finalUrl, result.html.orEmpty())
                officialName?.localizedName?.let(officialNames::add)
                pages += page.copy(
                    depth = depth,
                    outgoingLinks = outgoingLinks,
                    languageCode = officialName?.pageLanguageCode,
                )
                if (depth < maxDepth) {
                    outgoingLinks.forEach { queue.addLast(QueuedUrl(it, depth + 1)) }
                }
            }
            when {
                result.entry.error != null -> errors++
                result.entry.status == 304 -> unchanged++
                else -> fetched++
            }
        }
        val finalPages = if (pages.isEmpty() && unchanged > 0) sanitizedChurch.pages else pages
        val localizedNames = mergeOfficialNames(sanitizedChurch.localizedNames, officialNames)
        val japaneseName = localizedDomainText(
            Language.JAPANESE,
            localizedNames,
            english = sanitizedChurch.englishName,
            japanese = sanitizedChurch.name,
        ) ?: sanitizedChurch.name
        val englishName = localizedDomainText(
            Language.ENGLISH,
            localizedNames,
            english = sanitizedChurch.englishName,
            japanese = japaneseName,
        ) ?: sanitizedChurch.englishName
        return ChurchRefresh(
            sanitizedChurch.copy(
                name = japaneseName,
                englishName = englishName,
                localizedNames = localizedNames,
                pages = finalPages,
                updatedAt = Instant.now().toString(),
            ),
            entries,
            fetched,
            unchanged,
            errors,
        )
    }

    private fun mergeOfficialNames(
        existing: List<LocalizedName>,
        extracted: List<LocalizedName>,
    ): List<LocalizedName> = extracted.distinctBy { it.languageCode to it.name }.fold(existing) { names, official ->
        val sameLanguage = names.filter { Language.fromCode(it.languageCode) == Language.fromCode(official.languageCode) }
        val hasProtectedName = sameLanguage.any { candidate ->
            candidate.metadata?.source == LocalizedNameSource.MANUAL ||
                candidate.metadata?.reviewStatus == LocalizedNameReviewStatus.REVIEWED
        }
        when {
            hasProtectedName -> names
            sameLanguage.any { it.name == official.name && it.metadata?.source == LocalizedNameSource.OFFICIAL } -> names
            else -> names.filterNot { candidate ->
                Language.fromCode(candidate.languageCode) == Language.fromCode(official.languageCode) &&
                    candidate.metadata?.source != LocalizedNameSource.OFFICIAL
            } + official
        }
    }

    private data class FetchResult(val entry: CrawlManifestEntry, val page: CrawledPage?, val html: String?)

    private fun fetch(churchId: String, url: String, previous: CrawlManifestEntry?, webCache: Path): FetchResult {
        val pending = CompletableFuture<FetchResult>()
        val existing = fetches.putIfAbsent(url, pending)
        val result = if (existing != null) {
            existing.join()
        } else {
            try {
                fetchDirect(churchId, url, previous, webCache).also(pending::complete)
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
                fetches.remove(url, pending)
                throw error
            }
        }
        val cachedContent = if (result.page == null) {
            cachedContent(
                url,
                result.entry.finalUrl,
                result.entry.cachePath,
                result.entry.contentHash,
                result.entry.fetchedAt,
                webCache,
            )
        } else {
            null
        }
        return result.copy(
            entry = result.entry.copy(churchId = churchId),
            page = cachedContent?.first ?: result.page,
            html = cachedContent?.second ?: result.html,
        )
    }

    private fun fetchDirect(churchId: String, url: String, previous: CrawlManifestEntry?, webCache: Path): FetchResult {
        previous?.takeIf(::isFresh)?.let { entry ->
            if (entry.cachePath.isNotBlank() && Files.isRegularFile(webCache.resolve(entry.cachePath))) {
                return FetchResult(entry.copy(status = 304), null, null)
            }
            if (entry.error != null) return FetchResult(entry, null, null)
        }
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
                val now = Instant.now().toString()
                return FetchResult(previous.copy(status = 304, fetchedAt = now), null, null)
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

    private fun cachedContent(
        url: String,
        finalUrl: String,
        cachePath: String,
        contentHash: String,
        fetchedAt: String,
        webCache: Path,
    ): Pair<CrawledPage, String>? {
        if (cachePath.isBlank()) return null
        val file = webCache.resolve(cachePath)
        if (!Files.isRegularFile(file)) return null
        val html = Files.readAllBytes(file).toString(Charsets.UTF_8)
        val document = Jsoup.parse(html, finalUrl.ifBlank { url })
        document.select("script,style,noscript,template").remove()
        return CrawledPage(
            url = url,
            finalUrl = finalUrl.ifBlank { url },
            title = document.title(),
            text = document.body()?.text().orEmpty(),
            fetchedAt = fetchedAt,
            contentHash = contentHash,
            status = 200,
            contentType = CrawledContentType.WEBSITE_PAGE,
        ) to html
    }

    private fun isFresh(entry: CrawlManifestEntry): Boolean {
        if (cacheFreshness.isZero) return false
        val fetchedAt = runCatching { Instant.parse(entry.fetchedAt) }.getOrNull() ?: return false
        val age = Duration.between(fetchedAt, Instant.now())
        return !age.isNegative && age < cacheFreshness
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

    private fun discoverLinks(home: String, pageUrl: String, html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val origin = URI(home)
        val priority = Regex(
            "(?:^|/)(?:ja?|jp|japanese|zh|cn?|chinese)(?:/|$)|about|belief|faith|access|contact|ministry|church|" +
                "教会|教會|私たち|信仰|アクセス|集会|聚會|礼拝|禮拜|日本語|中文",
            RegexOption.IGNORE_CASE,
        )
        return Jsoup.parse(html, pageUrl).select("a[href]").asSequence()
            .map { link -> link.absUrl("href").substringBefore('#') to link.text() }
            .filter { (url) -> url.startsWith("http") }
            .filter { (url) -> runCatching { URI(url).host.equals(origin.host, ignoreCase = true) }.getOrDefault(false) }
            .filterNot { (url) -> excludedWebAssetPattern.containsMatchIn(URI(url).path.orEmpty()) }
            .distinctBy { (url) -> url }
            .sortedWith(
                compareByDescending<Pair<String, String>> { (url, label) -> priority.containsMatchIn("$url $label") }
                    .thenBy { it.first },
            )
            .map(Pair<String, String>::first)
            .take(MAX_RECORDED_OUTGOING_LINKS)
            .toList()
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

    private companion object {
        const val MAX_RECORDED_OUTGOING_LINKS = 100
        val excludedWebAssetPattern = Regex(
            """\.(?:pdf|jpe?g|png|gif|svg|webp|css|js|zip|mp[34]|wav|mov)(?:$|\?)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
