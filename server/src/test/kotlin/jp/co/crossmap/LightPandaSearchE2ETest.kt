package jp.co.crossmap

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class LightPandaSearchE2ETest {
    @Test
    fun runningServerLoadsSnapshotPublishedAfterStartup() {
        if (System.getenv("CROSSMAP_LIGHTPANDA_E2E") != "1") return
        val projectRoot = Path.of(requireNotNull(System.getProperty("crossmap.project.root")))
        val resources = projectRoot.resolve("resources")
        val publishedIndexes = projectRoot.resolve("cache/search-indexes/churches")
        val latest = publishedIndexes.resolve("latest.json")
        val manifest = Json.decodeFromString<IndexManifest>(Files.readString(latest))
        val temporaryCache = Files.createTempDirectory("crossmap-reloading-index")
        val temporaryIndexes = temporaryCache.resolve("search-indexes/churches")
        Files.createDirectories(temporaryIndexes)
        val versionLink = temporaryIndexes.resolve(manifest.indexVersion)
        Files.createSymbolicLink(versionLink, publishedIndexes.resolve(manifest.indexVersion).toAbsolutePath())
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(
                searchEngine = null,
                resourcesRoot = resources,
                cacheRoot = temporaryCache,
                webRoot = projectRoot.resolve("webclient"),
                reloadSearchEngines = true,
            )
        }
        server.start(wait = false)
        try {
            awaitHealthy(port)
            val unavailable = URI("http://127.0.0.1:$port/api/v1/churches/search?q=%E3%83%90%E3%83%97%E3%83%86%E3%82%B9%E3%83%88")
                .toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(503, unavailable.responseCode)
            } finally {
                unavailable.disconnect()
            }

            Files.copy(latest, temporaryIndexes.resolve("latest.json"))
            val recovered = apiSearch(
                port,
                "バプテスト",
                readTimeoutMillis = 15_000,
            ).response
            assertTrue(recovered.total > 0)
            assertTrue(recovered.hits.isNotEmpty())
        } finally {
            server.stop(1_000, 3_000)
            Files.deleteIfExists(versionLink)
            temporaryCache.toFile().deleteRecursively()
        }
    }

    @Test
    fun browserSearchUsesTheDetectedAnalyzerForAllSupportedLanguages() {
        if (System.getenv("CROSSMAP_LIGHTPANDA_E2E") != "1") return
        val projectRoot = Path.of(requireNotNull(System.getProperty("crossmap.project.root")))
        val resources = projectRoot.resolve("resources")
        val cache = projectRoot.resolve("cache")
        val index = assertNotNull(resolveServerIndex(resources, null, cache))
        val geonames = Json.decodeFromString<List<GeoName>>(
            Files.readString(resources.resolve("geonames/japan.json")),
        )
        val pageUrlsByLanguage = Language.entries.associate { language ->
            language.code to assertNotNull(loadChurchPageUrls(resources, projectRoot.resolve("webclient"), language.code))
        }
        val denominationNames = supportedLanguageCodes.associateWith { language ->
            Json.decodeFromString<Map<String, String>>(
                Files.readString(resources.resolve("catalog/denomination-$language-names.json")),
            )
        }
        val fusaChurch = Json.decodeFromString<List<ChurchRecord>>(
            Files.readString(resources.resolve("catalog/churches.json")),
        ).single { it.id == "google:8998728770320543438" }
        val engines = supportedLanguageCodes.associateWith { language ->
            val languageIndex = if (language == "ja") index else index.parent.resolve(language)
            assertTrue(Files.isDirectory(languageIndex), "Missing $language index: $languageIndex")
            ChurchSearchEngine(
                languageIndex.toString().toPath(),
                geonames,
                "lightpanda-e2e",
                pageUrlsByLanguage.getValue(language),
                language,
            )
        }
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(
                searchEngine = engines.getValue("ja"),
                searchEngines = engines,
                resourcesRoot = resources,
                cacheRoot = cache,
                webRoot = projectRoot.resolve("webclient"),
            )
        }
        server.start(wait = false)
        try {
            awaitHealthy(port)
            apiSearch(
                port,
                "東京バプテスト教会",
                readTimeoutMillis = 15_000,
            ) // Warm the complete analyzer/resolver/query path without applying the warm-search SLO.
            val tokyoSamples = List(3) { apiSearch(port, "東京バプテスト教会") }
            val tokyoResponse = tokyoSamples.last().response
            println(
                "Warm 東京バプテスト教会 API durations: " +
                    tokyoSamples.joinToString { it.elapsed.toString() },
            )
            assertTrue(
                tokyoSamples.all { it.elapsed < Duration.ofSeconds(1) },
                "Warm 東京バプテスト教会 searches must stay below one second: " +
                    tokyoSamples.joinToString { it.elapsed.toString() },
            )
            assertEquals("13", tokyoResponse.resolvedLocations.single().code)
            assertEquals("google:6646597370070891755", tokyoResponse.hits.first().churchId)

            val tokyoCentral = apiSearch(port, "中央区", 35.681, 139.767).response
            val fukuokaCentral = apiSearch(port, "中央区", 33.590, 130.401).response
            assertEquals("131024", tokyoCentral.resolvedLocations.single().code)
            assertEquals("401331", fukuokaCentral.resolvedLocations.single().code)
            assertTrue(tokyoCentral.hits.all { it.address.contains("東京都中央区") })
            assertTrue(fukuokaCentral.hits.all { it.address.contains("福岡市中央区") })

            val paginationUrl = "http://127.0.0.1:$port/ja/result.html?q=" +
                URLEncoder.encode("東京バプテスト教会", Charsets.UTF_8.name())
            LightPandaCdpSession.open(paginationUrl).use { session ->
                session.waitUntil { session.evaluate("Boolean(document.querySelector('#next'))") == "true" }
                val firstPageChurch = assertNotNull(
                    session.evaluate("document.querySelector('.result a')?.textContent || ''"),
                )
                session.evaluate("document.querySelector('#next').click(); 'clicked'")
                session.waitUntil {
                    session.evaluate("new URLSearchParams(location.search).get('offset')") == "20" &&
                        session.evaluate("document.querySelector('.result a')?.textContent || ''") != firstPageChurch
                }
                session.evaluate("history.back(); 'back'")
                session.waitUntil {
                    session.evaluate("new URLSearchParams(location.search).get('offset') || '0'") == "0" &&
                        session.evaluate("document.querySelector('.result a')?.textContent || ''") == firstPageChurch
                }
            }

            val izuDenominationUrl = "http://127.0.0.1:$port/ja/result.html?q=" +
                URLEncoder.encode("日本基督教団", Charsets.UTF_8.name())
            LightPandaCdpSession.open(
                izuDenominationUrl,
                GeoPoint(34.87544654121299, 138.92825706221615),
            ).use { session ->
                session.waitUntil {
                    session.evaluate("document.querySelector('#result-heading')?.textContent || ''")
                        ?.contains("伊豆市付近") == true &&
                        session.evaluate("document.querySelectorAll('.result').length") != "0"
                }
                assertEquals(
                    "伊豆市付近の「日本基督教団」の検索結果",
                    session.evaluate("document.querySelector('#result-heading')?.textContent || ''"),
                )
                val visibleNames = Json.decodeFromString<List<String>>(
                    assertNotNull(
                        session.evaluate(
                            "JSON.stringify([...document.querySelectorAll('.result a')].map(link => link.textContent))",
                        )
                    )
                )
                assertTrue(visibleNames.first().contains("修善寺"), visibleNames.toString())
                listOf("三島", "伊豆長岡", "沼津", "伊豆高原", "宇佐美", "熱海").forEach { expected ->
                    assertTrue(visibleNames.any { it.contains(expected) }, "$expected: $visibleNames")
                }
            }

            val izuBaptist = apiSearch(
                port,
                "Baptist",
                latitude = 34.87544654121299,
                longitude = 138.92825706221615,
            ).response
            assertEquals("Senbon Hama Bible Baptist Church", izuBaptist.hits.first().englishName)
            assertTrue(izuBaptist.hits.any { it.englishName == "Shimizu Bible Baptist Church" })
            assertTrue(izuBaptist.hits.none { it.englishName == "JBBF" })
            val izuBaptistUrl = "http://127.0.0.1:$port/en/result.html?q=" +
                URLEncoder.encode("Baptist", Charsets.UTF_8.name())
            LightPandaCdpSession.open(
                izuBaptistUrl,
                GeoPoint(34.87544654121299, 138.92825706221615),
            ).use { session ->
                session.waitUntil {
                    session.evaluate("document.querySelectorAll('.result').length") != "0"
                }
                assertEquals("Baptist", session.evaluate("document.querySelector('#query').value"))
                assertEquals("0", session.evaluate("document.querySelectorAll('#language-chooser').length"))
                val visibleNames = Json.decodeFromString<List<String>>(
                    assertNotNull(
                        session.evaluate(
                            "JSON.stringify([...document.querySelectorAll('.result a')].map(link => link.textContent))",
                        ),
                    ),
                )
                assertEquals("Senbon Hama Bible Baptist Church", visibleNames.first())
                assertTrue(visibleNames.contains("Shimizu Bible Baptist Church"), visibleNames.toString())
                assertFalse(visibleNames.contains("JBBF"), visibleNames.toString())
            }

            val browser = LightPanda(
                timeout = Duration.ofSeconds(60),
                renderWait = Duration.ofSeconds(30),
            )
            val defaultIndexHtml = browser.fetchHtml("http://127.0.0.1:$port/")
            assertTrue(defaultIndexHtml.contains("<html lang=\"en\""), defaultIndexHtml.take(1_000))
            assertTrue(defaultIndexHtml.contains("id=\"search-form\""), defaultIndexHtml.take(1_000))

            val indexHtml = browser.fetchHtml("http://127.0.0.1:$port/ja/")
            assertTrue(indexHtml.contains("id=\"search-form\""), indexHtml.take(1_000))
            assertFalse(indexHtml.contains("id=\"language-chooser\""), indexHtml.take(1_000))

            val queries = supportedLanguageCodes.associateWith { language ->
                when (language) {
                    "ja" -> fusaChurch.name
                    "en" -> fusaChurch.englishName
                    else -> assertNotNull(
                        fusaChurch.localizedNames.firstOrNull {
                            Language.fromCode(it.languageCode)?.code == Language.fromCode(language)?.code
                        }?.name,
                        "Fusa church is missing its $language localized name",
                    )
                }
            }
            val resultPages = queries.mapValues { (language, rawQuery) ->
                val query = URLEncoder.encode(rawQuery, Charsets.UTF_8.name())
                val html = browser.fetchHtml("http://127.0.0.1:$port/$language/result.html?q=$query")
                assertTrue(html.contains("class=\"result\""), "$language: ${html.take(2_000)}")
                assertTrue(html.contains(rawQuery), "$language: ${html.take(2_000)}")
                assertTrue(html.contains("id=\"result-search-form\""), "$language: ${html.take(2_000)}")
                assertFalse(html.contains("id=\"language-chooser\""), "$language: ${html.take(2_000)}")
                assertFalse(html.contains("Field 'englishName' is required"), "$language: ${html.take(2_000)}")
                assertFalse(html.contains("/church.html?id="), "$language: ${html.take(2_000)}")
                html
            }
            val englishQueryOnJapaneseUi = browser.fetchHtml(
                "http://127.0.0.1:$port/ja/result.html?q=" +
                    URLEncoder.encode(fusaChurch.englishName, Charsets.UTF_8.name()),
            )
            assertTrue(englishQueryOnJapaneseUi.contains(fusaChurch.name), englishQueryOnJapaneseUi.take(2_000))
            val japaneseQueryOnEnglishUi = browser.fetchHtml(
                "http://127.0.0.1:$port/en/result.html?q=" +
                    URLEncoder.encode(fusaChurch.name, Charsets.UTF_8.name()),
            )
            assertTrue(japaneseQueryOnEnglishUi.contains(fusaChurch.englishName), japaneseQueryOnEnglishUi.take(2_000))
            supportedLanguageCodes.forEach { language ->
                val rawQuery = assertNotNull(denominationNames.getValue(language)["UCCJ"])
                val query = URLEncoder.encode(rawQuery, Charsets.UTF_8.name())
                val html = browser.fetchHtml("http://127.0.0.1:$port/$language/result.html?q=$query")
                assertTrue(html.contains("class=\"result\""), "denomination $language: ${html.take(2_000)}")
                assertFalse(html.contains("Church index is not configured"), "denomination $language: ${html.take(2_000)}")
            }
            val independentResult = browser.fetchHtml(
                "http://127.0.0.1:$port/en/result.html?q=" +
                    URLEncoder.encode("Machida Baptist Church", Charsets.UTF_8.name()),
            )
            assertTrue(independentResult.contains("Machida Baptist Church"), independentResult.take(2_000))
            assertFalse(independentResult.contains("INDEPENDENT_CHURCH"), independentResult.take(2_000))
            assertFalse(independentResult.contains("NOT_DETERMINED"), independentResult.take(2_000))
            val listingDomainResult = browser.fetchHtml(
                "http://127.0.0.1:$port/ja/result.html?q=" +
                    URLEncoder.encode("錦キリスト教会", Charsets.UTF_8.name()),
            )
            val listingDomainDetailPath = assertNotNull(pageUrlsByLanguage.getValue("ja")["google:10158070367548216990"])
            assertTrue(listingDomainResult.contains(listingDomainDetailPath), listingDomainResult.take(2_000))
            val listingDomainDetail = browser.fetchHtml("http://127.0.0.1:$port$listingDomainDetailPath")
            assertFalse(listingDomainDetail.contains("church-info.jp"), listingDomainDetail.take(2_000))
            assertTrue(
                listingDomainDetail.contains("https://www.google.com/maps?cid=10158070367548216990"),
                listingDomainDetail.take(2_000),
            )

            val detailPath = Regex("""href="(/en/[a-z0-9-]+\.html)"""")
                .find(resultPages.getValue("en"))?.groupValues?.get(1)
            assertNotNull(detailPath, resultPages.getValue("en").take(2_000))
            val detailHtml = browser.fetchHtml("http://127.0.0.1:$port$detailPath")
            assertTrue(detailHtml.contains("Fusa Christ Church"), detailHtml.take(2_000))
            assertFalse(detailHtml.contains("id=\"language-chooser\""), detailHtml.take(2_000))
            supportedLanguageCodes.forEach { language ->
                val localizedPath = assertNotNull(pageUrlsByLanguage.getValue(language)[fusaChurch.id])
                val localizedDetail = browser.fetchHtml("http://127.0.0.1:$port$localizedPath")
                val denomination = assertNotNull(denominationNames.getValue(language)["JECA"])
                assertTrue(localizedDetail.contains(denomination), "$language denomination missing: ${localizedDetail.take(2_000)}")
                assertTrue(localizedPath.substringAfterLast('/') == detailPath.substringAfterLast('/'))
            }
            assertFalse(detailHtml.contains("Field 'englishName' is required"), detailHtml.take(2_000))
        } finally {
            server.stop(1_000, 3_000)
        }
    }

    private data class TimedSearch(val response: ChurchSearchResponse, val elapsed: Duration)

    private fun apiSearch(
        port: Int,
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
        readTimeoutMillis: Int = 10_000,
    ): TimedSearch {
        val parameters = buildString {
            append("q=")
            append(URLEncoder.encode(query, Charsets.UTF_8.name()))
            if (latitude != null && longitude != null) {
                append("&lat=$latitude&lon=$longitude")
            }
        }
        val connection = URI("http://127.0.0.1:$port/api/v1/churches/search?$parameters")
            .toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = readTimeoutMillis
        val started = System.nanoTime()
        return try {
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            TimedSearch(
                response = Json.decodeFromString(body),
                elapsed = Duration.ofNanos(System.nanoTime() - started),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitHealthy(port: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val connection = URI("http://127.0.0.1:$port/api/v1/health").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500
                if (connection.responseCode == 200) return
            } catch (error: Throwable) {
                lastFailure = error
            }
            Thread.sleep(100)
        }
        error("Server did not become healthy: ${lastFailure?.message}")
    }
}
