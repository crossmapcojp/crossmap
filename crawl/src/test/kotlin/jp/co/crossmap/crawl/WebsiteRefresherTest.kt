package jp.co.crossmap.crawl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsiteRefresherTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun discoversPagesAndUsesConditionalRequestsOnResume() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\nDisallow: /private\n") }
        server.createContext("/") { exchange ->
            exchange.htmlWithEtag("<html><head><title>岡山バプテスト教会</title></head><body>集会案内<a href='/about'>教会案内</a></body></html>")
        }
        server.createContext("/about") { exchange ->
            exchange.htmlWithEtag("<html><head><title>教会案内</title></head><body>日本バプテスト連盟に所属します</body></html>")
        }
        server.start()
        val root = Files.createTempDirectory("crossmap-refresh")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/church-web-pages"))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(listOf(ChurchRecord("google:906297735827744432", "906297735827744432", "岡山バプテスト教会", "Okayama Baptist Church", address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８", location = GeoPoint(34.6619806, 133.9231824), websiteUrl = website))),
            )
            Files.writeString(root.resolve("cache/church-web-pages/manifest.json"), "[]")

            val first = WebsiteRefresher(maxConcurrency = 1, hostDelayMillis = 0).refresh(root, cacheRoot = root.resolve("cache"))
            assertEquals(2, first.fetched)
            val church = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).single()
            assertEquals(setOf("岡山バプテスト教会", "教会案内"), church.pages.map { it.title }.toSet())
            assertTrue(church.pages.any { it.text.contains("バプテスト") })

            val second = WebsiteRefresher(maxConcurrency = 1, hostDelayMillis = 0).refresh(root, cacheRoot = root.resolve("cache"))
            assertEquals(2, second.unchanged)
            assertEquals(0, second.errors)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun retriesTransientServerFailures() {
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\n") }
        server.createContext("/") { exchange ->
            if (attempts.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else exchange.htmlWithEtag("<html><body>礼拝</body></html>")
        }
        server.start()
        val root = Files.createTempDirectory("crossmap-retry")
        try {
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/church-web-pages"))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord(
                            "google:906297735827744432",
                            name = "岡山バプテスト教会",
                            englishName = "Okayama Baptist Church",
                            address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
                            location = GeoPoint(34.6619806, 133.9231824),
                            websiteUrl = "http://127.0.0.1:${server.address.port}/",
                        )
                    )
                ),
            )
            Files.writeString(root.resolve("cache/church-web-pages/manifest.json"), "[]")

            val report = WebsiteRefresher(maxConcurrency = 1, hostDelayMillis = 0).refresh(root, cacheRoot = root.resolve("cache"))
            assertEquals(1, report.fetched)
            assertEquals(3, attempts.get())
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun reusesUrlMappedHtmlCacheWithoutAnHttpRequest() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> requests.incrementAndGet(); exchange.respond("unexpected") }
        server.start()
        val root = Files.createTempDirectory("crossmap-url-cache")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            val html = "<html><head><title>聖アンデレ教会</title></head><body>礼拝スケジュール 子供と祝うユーカリスト</body></html>".toByteArray()
            val hash = html.sha256()
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/church-web-pages/pages"))
            Files.write(root.resolve("cache/church-web-pages/pages/$hash.html"), html)
            Files.writeString(root.resolve("cache/church-web-pages/url-cache-map.json"), json.encodeToString(mapOf(website.sha1() to hash)))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(listOf(ChurchRecord("google:2225537460932230335", name = "日本聖公会東京聖アンデレ教会", englishName = "Tokyo St Andrew's Church", address = "〒105-0011 東京都港区芝公園３丁目６−１８", location = GeoPoint(35.6601808, 139.743601), websiteUrl = website))),
            )
            Files.writeString(root.resolve("cache/church-web-pages/manifest.json"), "[]")

            val report = WebsiteRefresher(maxConcurrency = 1, hostDelayMillis = 0).refresh(root, cacheRoot = root.resolve("cache"))

            assertEquals(1, report.fetched)
            assertEquals(0, requests.get())
            val church = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).single()
            assertTrue(church.pages.single().text.contains("ユーカリスト"))
            val manifest = json.decodeFromString<List<CrawlManifestEntry>>(Files.readString(root.resolve("cache/church-web-pages/manifest.json")))
            assertEquals("IMPORTED_CACHE", manifest.single().acquisition)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.htmlWithEtag(body: String) {
        if (requestHeaders.getFirst("If-None-Match") == "\"fixture-v1\"") {
            sendResponseHeaders(304, -1)
            close()
            return
        }
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        responseHeaders.add("ETag", "\"fixture-v1\"")
        respond(body)
    }
}
