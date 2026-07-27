package jp.co.crossmap.crawl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpFetcherTest {

    @Test
    fun fetchReturnsHtmlFromLocalServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.respond("<html><body>Hello</body></html>")
        }
        server.start()
        try {
            val fetcher = HttpFetcher()
            val page = fetcher.fetch("http://127.0.0.1:${server.address.port}/")
            assertEquals(200, page.statusCode)
            assertTrue(page.html.contains("Hello"))
            assertEquals("http", page.via)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchCachesAndReturnsCachedContentOnSecondCall() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            exchange.respond("<html><body>Cached</body></html>")
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-cache")
            val fetcher = HttpFetcher(cacheDir = cacheDir)
            val url = "http://127.0.0.1:${server.address.port}/"

            val first = fetcher.fetch(url)
            assertEquals(1, hitCount.get())
            assertEquals("http", first.via)

            val second = fetcher.fetch(url)
            assertEquals(1, hitCount.get())
            assertEquals("cache", second.via)
            assertTrue(second.html.contains("Cached"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cacheMissWhenForceRefresh() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            exchange.respond("<html><body>Refreshed</body></html>")
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-refresh")
            val fetcher = HttpFetcher(cacheDir = cacheDir)
            val url = "http://127.0.0.1:${server.address.port}/"

            fetcher.fetch(url)
            assertEquals(1, hitCount.get())

            fetcher.fetch(url, forceRefresh = true)
            assertEquals(2, hitCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun staleCacheTriggersConditionalGetAndReturns304() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (ifNoneMatch == "\"v1\"") {
                exchange.sendResponseHeaders(304, -1)
            } else {
                exchange.respondWithEtag("<html><body>Content</body></html>", "\"v1\"")
            }
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-conditional")
            val fetcher = HttpFetcher(cacheDir = cacheDir)
            val url = "http://127.0.0.1:${server.address.port}/"

            val first = fetcher.fetch(url)
            assertEquals(1, hitCount.get())
            assertEquals("http", first.via)

            val staleFetcher = HttpFetcher(
                cacheDir = cacheDir,
                cacheExpDate = Instant.now().plus(Duration.ofDays(1)),
            )
            val second = staleFetcher.fetch(url)
            assertEquals(2, hitCount.get())
            assertEquals("cache", second.via)
            assertTrue(second.html.contains("Content"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun staleCacheTriggersReFetchWhenServerReturns200() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            exchange.respondWithEtag("<html><body>Updated</body></html>", "\"v2\"")
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-refetch")
            val fetcher = HttpFetcher(cacheDir = cacheDir)
            val url = "http://127.0.0.1:${server.address.port}/"

            fetcher.fetch(url)
            assertEquals(1, hitCount.get())

            val staleFetcher = HttpFetcher(
                cacheDir = cacheDir,
                cacheExpDate = Instant.now().plus(Duration.ofDays(1)),
            )
            val second = staleFetcher.fetch(url)
            assertEquals(2, hitCount.get())
            assertTrue(second.html.contains("Updated"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun staleCacheFallsBackToLightPandaOnNetworkFailure() {
        val cacheDir = Files.createTempDirectory("http-fetcher-fallback")
        val fetcher = HttpFetcher(cacheDir = cacheDir)
        val url = "http://127.0.0.1:1/nonexistent"

        val first = fetcher.fetch(url)
        assertTrue(first.via == "lightpanda" || first.html.isNotEmpty())
    }

    @Test
    fun noCacheDirSkipsCaching() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            exchange.respond("<html><body>NoCache</body></html>")
        }
        server.start()
        try {
            val fetcher = HttpFetcher(cacheDir = null)
            val url = "http://127.0.0.1:${server.address.port}/"

            fetcher.fetch(url)
            fetcher.fetch(url)
            assertEquals(2, hitCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun loadConfigReadsCacheExpDateFromLocalProperties() {
        val root = Files.createTempDirectory("http-fetcher-config")
        try {
            Files.writeString(root.resolve("local.properties"), "crossmap.httpFetcherCacheExpDate=2026-08-01\n")
            val fetcher = HttpFetcher.loadConfig(root)
            assertTrue(fetcher != null)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadConfigReturnsNullWhenPropertyMissing() {
        val root = Files.createTempDirectory("http-fetcher-no-config")
        try {
            Files.writeString(root.resolve("local.properties"), "other.prop=value\n")
            val fetcher = HttpFetcher.loadConfig(root)
            assertTrue(fetcher == null)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun decodeHtmlHandlesShiftJisContent() {
        val html = """<p>日本教会テスト</p>"""
        val bytes = html.toByteArray(java.nio.charset.Charset.forName("windows-31j"))
        val decoded = HttpFetcher.decodeHtml(bytes, "text/html; charset=shift_jis")
        assertTrue(decoded.contains("日本教会テスト"))
    }

    @Test
    fun decodeHtmlFallsBackToUtf8() {
        val html = "<p>Hello UTF-8</p>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val decoded = HttpFetcher.decodeHtml(bytes, "text/html")
        assertTrue(decoded.contains("Hello UTF-8"))
    }

    @Test
    fun freshCacheIsNotReFetched() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            exchange.respond("<html><body>Fresh</body></html>")
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-fresh")
            val fetcher = HttpFetcher(
                cacheDir = cacheDir,
                cacheExpDate = Instant.now().minus(Duration.ofDays(10)),
            )
            val url = "http://127.0.0.1:${server.address.port}/"

            fetcher.fetch(url)
            assertEquals(1, hitCount.get())

            fetcher.fetch(url)
            assertEquals(1, hitCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cloudflareBlockedPageIsDetectedAndLogged() {
        val cloudflareBody = """
            <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
            </head><body><div class="main-content" id="challenge-body-text">
            Checking your browser before accessing tokyosophia.org.
            </div></body></html>
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = cloudflareBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.bufferedWriter().use { it.write(cloudflareBody) }
            exchange.close()
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-cf")
            val logFile = cacheDir.resolve("cloudflare-blocked.log")
            val fetcher = HttpFetcher(cacheDir = cacheDir, cloudflareBlockedLog = logFile)
            val url = "http://127.0.0.1:${server.address.port}/"

            val page = fetcher.fetch(url)
            assertEquals(403, page.statusCode)
            assertTrue(page.html.contains("Just a moment"))
            assertTrue(page.html.contains("Checking your browser"))

            assertTrue(Files.isRegularFile(logFile))
            val log = Files.readString(logFile)
            assertTrue(log.contains(url))
            assertTrue(log.contains("cache-key:"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun manualCacheUsedBeforeNetworkFetch() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hitCount = AtomicInteger()
        server.createContext("/") { exchange ->
            hitCount.incrementAndGet()
            val bytes = "REAL PAGE".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.bufferedWriter().use { it.write("REAL PAGE") }
            exchange.close()
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-manual")
            val manualDir = cacheDir.resolve("manual")
            Files.createDirectories(manualDir)
            val url = "http://127.0.0.1:${server.address.port}/"
            val cacheKey = url.sha256()
            Files.writeString(manualDir.resolve("$cacheKey.html"), "<html><body>MANUAL CONTENT</body></html>")

            val fetcher = HttpFetcher(cacheDir = cacheDir, manualCacheDir = manualDir)
            val page = fetcher.fetch(url)
            assertEquals(0, hitCount.get())
            assertTrue(page.html.contains("MANUAL CONTENT"))
            assertEquals("manual", page.via)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cloudflareDetectionWith503AndChallengePlatform() {
        val cloudflareBody = """
            <!DOCTYPE html><html><head><title>Attention Required</title></head>
            <body><div id="challenge-platform"></div>
            <p>Verify you are human</p></body></html>
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = cloudflareBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.bufferedWriter().use { it.write(cloudflareBody) }
            exchange.close()
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-cf503")
            val logFile = cacheDir.resolve("cloudflare-blocked.log")
            val fetcher = HttpFetcher(cacheDir = cacheDir, cloudflareBlockedLog = logFile)
            val url = "http://127.0.0.1:${server.address.port}/"

            val page = fetcher.fetch(url)
            assertEquals(503, page.statusCode)
            assertTrue(page.html.contains("Attention Required"))
            assertTrue(page.html.contains("challenge-platform"))

            assertTrue(Files.isRegularFile(logFile))
            val log = Files.readString(logFile)
            assertTrue(log.contains(url))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun normal403IsNotDetectedAsCloudflare() {
        val normalBody = "<html><body><h1>Forbidden</h1><p>You don't have permission.</p></body></html>"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = normalBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.bufferedWriter().use { it.write(normalBody) }
            exchange.close()
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-normal403")
            val logFile = cacheDir.resolve("cloudflare-blocked.log")
            val fetcher = HttpFetcher(cacheDir = cacheDir, cloudflareBlockedLog = logFile)
            val url = "http://127.0.0.1:${server.address.port}/"

            val page = fetcher.fetch(url)
            assertTrue(page.via == "lightpanda" || page.via == "playwright")

            assertFalse(Files.isRegularFile(logFile))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cacheMetadataStoresEtagAndLastModified() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.respondWithEtag("<html><body>Meta</body></html>", "\"abc123\"")
        }
        server.start()
        try {
            val cacheDir = Files.createTempDirectory("http-fetcher-meta")
            val fetcher = HttpFetcher(cacheDir = cacheDir)
            val url = "http://127.0.0.1:${server.address.port}/"

            val page = fetcher.fetch(url)
            assertEquals("\"abc123\"", page.etag)
            assertTrue(page.contentHash.isNotEmpty())
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.bufferedWriter().use { it.write(html) }
        close()
    }

    private fun HttpExchange.respondWithEtag(html: String, etag: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
        responseHeaders.set("ETag", etag)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.bufferedWriter().use { it.write(html) }
        close()
    }
}
