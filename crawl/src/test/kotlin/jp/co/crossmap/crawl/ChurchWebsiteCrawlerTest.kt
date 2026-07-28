package jp.co.crossmap.crawl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChurchWebsiteCrawlerTest {
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
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(listOf(ChurchRecord("google:906297735827744432", "906297735827744432", "岡山バプテスト教会", "Okayama Baptist Church", address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８", location = GeoPoint(34.6619806, 133.9231824), websiteUrl = website))),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val first = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0).crawl(root, cacheRoot = root.resolve("cache"))
            assertEquals(2, first.fetched)
            val church = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).single()
            assertEquals(setOf("岡山バプテスト教会", "教会案内"), church.pages.map { it.title }.toSet())
            assertTrue(church.pages.any { it.text.contains("バプテスト") })
            assertEquals(setOf(0, 1), church.pages.map(CrawledPage::depth).toSet())
            assertTrue(church.pages.single { it.depth == 0 }.outgoingLinks.contains("$website${"about"}"))

            val second = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0, cacheFreshness = Duration.ZERO)
                .crawl(root, cacheRoot = root.resolve("cache"))
            assertEquals(2, second.unchanged)
            assertEquals(0, second.errors)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configurableDepthTwoFollowsGrandchildLinksAndRecordsEveryEdge() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\n") }
        server.createContext("/") { it.htmlWithEtag("<html><body><a href='/j/'>日本語</a></body></html>") }
        server.createContext("/j/") { it.htmlWithEtag("<html><body><a href='/j/about/'>教会案内</a></body></html>") }
        server.createContext("/j/about/") { it.htmlWithEtag("<html><body>東京教会</body></html>") }
        server.start()
        val root = Files.createTempDirectory("crossmap-depth-two")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "")
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord(
                            id = "google:depth-two",
                            name = "東京教会",
                            englishName = "Tokyo Church",
                            address = "東京都",
                            location = GeoPoint(35.0, 139.0),
                            websiteUrl = website,
                        ),
                    ),
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0, maxDepth = 2)
                .crawl(root, cacheRoot = root.resolve("cache"))
            val pages = json.decodeFromString<List<ChurchRecord>>(
                Files.readString(root.resolve("catalog/churches.json")),
            ).single().pages

            assertEquals(3, report.fetched)
            assertEquals(listOf(0, 1, 2), pages.map(CrawledPage::depth).sorted())
            assertEquals("${website}j/", pages.single { it.depth == 0 }.outgoingLinks.single())
            assertEquals("${website}j/about/", pages.single { it.depth == 1 }.outgoingLinks.single())
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun recordsOfficialNamesFromLinkedLocaleHomePagesForTokyoMulticulturalChurch() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\n") }
        server.createContext("/") {
            it.htmlWithEtag(
                """
                <html><head><meta property="og:site_name" content="TMC"></head><body>
                <main><h1>Tokyo Multicultural Church</h1></main>
                <a href="/j/">日本語</a><a href="/c/">中文</a>
                </body></html>
                """.trimIndent(),
            )
        }
        server.createContext("/j/") {
            it.htmlWithEtag("<html><body><main><h1>東京マルチカルチャル教会</h1></main></body></html>")
        }
        server.createContext("/c/") {
            it.htmlWithEtag("<html><body><main><h1>東京多元文化基督教會</h1></main></body></html>")
        }
        server.start()
        val root = Files.createTempDirectory("crossmap-tmc-official-names")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "")
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord(
                            id = "google:14933925210831204897",
                            googleCid = "14933925210831204897",
                            name = "東京ムルティクルテゥラル教会",
                            englishName = "Tokyo Multicultural Church (TMC)",
                            address = "東京都",
                            location = GeoPoint(35.0, 139.0),
                            websiteUrl = website,
                        ),
                    ),
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))
            val church = json.decodeFromString<List<ChurchRecord>>(
                Files.readString(root.resolve("catalog/churches.json")),
            ).single()
            val names = church.localizedNames.associate { it.languageCode to it.name }

            assertEquals(3, report.fetched)
            assertEquals("Tokyo Multicultural Church (TMC)", church.englishName)
            assertEquals("東京マルチカルチャル教会", church.name)
            assertEquals("Tokyo Multicultural Church (TMC)", names["en"])
            assertEquals("東京マルチカルチャル教会", names["ja"])
            assertEquals("東京多元文化基督教會", names["zh-Hans"])
            assertEquals(setOf("en", "ja", "zh-Hans"), church.pages.mapNotNull(CrawledPage::languageCode).toSet())
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun defaultCacheFreshnessReusesWebsitePagesForThirtyDays() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\n") }
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.htmlWithEtag("<html><head><title>長期キャッシュ教会</title></head><body>礼拝案内</body></html>")
        }
        server.start()
        val root = Files.createTempDirectory("crossmap-thirty-day-cache")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "")
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord(
                            id = "google:30",
                            googleCid = "30",
                            name = "長期キャッシュ教会",
                            englishName = "Long Cache Church",
                            address = "東京都",
                            location = GeoPoint(35.0, 139.0),
                            websiteUrl = website,
                        ),
                    ),
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")
            ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0).crawl(root, cacheRoot = root.resolve("cache"))
            val manifest = root.resolve("cache/web-pages/manifest.json")
            Files.writeString(
                manifest,
                Files.readString(manifest).replace(
                    Regex("\\\"fetchedAt\\\": \\\"[^\\\"]+\\\""),
                    "\\\"fetchedAt\\\": \\\"${Instant.now().minus(Duration.ofDays(29))}\\\"",
                ),
            )

            val cached = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))

            assertEquals(1, requests.get())
            assertEquals(1, cached.unchanged)
            assertEquals(0, cached.fetched)
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
            Files.createDirectories(root.resolve("cache/web-pages"))
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
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0).crawl(root, cacheRoot = root.resolve("cache"))
            assertEquals(1, report.fetched)
            assertEquals(3, attempts.get())
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun excludedListingDomainIsNeverRequestedAndIsReplacedByGoogleMaps() {
        val root = Files.createTempDirectory("crossmap-excluded-listing")
        try {
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "church-info.jp\n")
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord(
                            id = "google:10158070367548216990",
                            googleCid = "10158070367548216990",
                            name = "錦キリスト教会",
                            englishName = "Nishiki Christ Church",
                            address = "熊本県球磨郡錦町",
                            location = GeoPoint(32.20, 130.84),
                            websiteUrl = "http://www.church-info.jp/sp/search/detail.php?key=16230012",
                        ),
                    ),
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))
            val church = json.decodeFromString<List<ChurchRecord>>(
                Files.readString(root.resolve("catalog/churches.json")),
            ).single()

            assertEquals(0, report.fetched)
            assertEquals("https://www.google.com/maps?cid=10158070367548216990", church.websiteUrl)
            assertTrue(church.pages.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun socialPlatformsAreNotRequestedAndStaleSocialPagesAreRemoved() {
        val root = Files.createTempDirectory("crossmap-social-websites")
        try {
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "")
            val socialUrls = listOf(
                "https://www.facebook.com/TKBCJapaneseSection/",
                "https://instagram.com/tokyo_church",
                "https://twitter.com/tokyo_church",
                "https://x.com/tokyo_church",
                "https://youtube.com/channel/UC123",
                "https://youtu.be/abc123",
            )
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    socialUrls.mapIndexed { index, url ->
                        ChurchRecord(
                            id = "google:$index",
                            googleCid = index.toString(),
                            name = "ソーシャル教会$index",
                            englishName = "Social Church $index",
                            address = "東京都",
                            location = GeoPoint(35.0, 139.0),
                            websiteUrl = url,
                            pages = listOf(CrawledPage(url = url, title = "Login")),
                        )
                    },
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 2, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))
            val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json")))

            assertEquals(0, report.fetched)
            assertEquals(0, report.errors)
            assertTrue(churches.all { it.pages.isEmpty() })
        } finally {
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
            Files.createDirectories(root.resolve("cache/web-pages/pages"))
            Files.write(root.resolve("cache/web-pages/pages/$hash.html"), html)
            Files.writeString(root.resolve("cache/web-pages/url-cache-map.json"), json.encodeToString(mapOf(website.sha1() to hash)))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(listOf(ChurchRecord("google:2225537460932230335", name = "日本聖公会東京聖アンデレ教会", englishName = "Tokyo St Andrew's Church", address = "〒105-0011 東京都港区芝公園３丁目６−１８", location = GeoPoint(35.6601808, 139.743601), websiteUrl = website))),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 1, hostDelayMillis = 0).crawl(root, cacheRoot = root.resolve("cache"))

            assertEquals(1, report.fetched)
            assertEquals(0, requests.get())
            val church = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).single()
            assertTrue(church.pages.single().text.contains("ユーカリスト"))
            val manifest = json.decodeFromString<List<CrawlManifestEntry>>(Files.readString(root.resolve("cache/web-pages/manifest.json")))
            assertEquals("IMPORTED_CACHE", manifest.single().acquisition)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fetchesAUrlOnlyOnceWhenMultipleChurchesShareIt() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/robots.txt") { it.respond("User-agent: *\n") }
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.htmlWithEtag("<html><head><title>共同サイト</title></head><body>礼拝案内</body></html>")
        }
        server.start()
        val root = Files.createTempDirectory("crossmap-shared-url")
        try {
            val website = "http://127.0.0.1:${server.address.port}/"
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cache/web-pages"))
            Files.writeString(
                root.resolve("catalog/churches.json"),
                json.encodeToString(
                    listOf(
                        ChurchRecord("google:1", name = "第一教会", englishName = "First Church", address = "東京都", location = GeoPoint(35.0, 139.0), websiteUrl = website),
                        ChurchRecord("google:2", name = "第二教会", englishName = "Second Church", address = "東京都", location = GeoPoint(35.1, 139.1), websiteUrl = website),
                    ),
                ),
            )
            Files.writeString(root.resolve("cache/web-pages/manifest.json"), "[]")

            val report = ChurchWebsiteCrawler(maxConcurrency = 2, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))

            assertEquals(1, requests.get())
            assertEquals(2, report.fetched)
            val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json")))
            assertTrue(churches.all { it.pages.single().title == "共同サイト" })

            val cachedReport = ChurchWebsiteCrawler(maxConcurrency = 2, hostDelayMillis = 0)
                .crawl(root, cacheRoot = root.resolve("cache"))
            assertEquals(1, requests.get())
            assertEquals(2, cachedReport.unchanged)
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
