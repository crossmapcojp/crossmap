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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class LightPandaSearchE2ETest {
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
        val pageUrls = assertNotNull(loadChurchPageUrls(resources, projectRoot.resolve("webclient")))
        val denominationNames = supportedLanguageCodes.associateWith { language ->
            Json.decodeFromString<Map<String, String>>(
                Files.readString(resources.resolve("catalog/denomination-$language-names.json")),
            )
        }
        val fusaChurch = Json.decodeFromString<List<ChurchRecord>>(
            Files.readString(resources.resolve("catalog/churches.json")),
        ).single { it.id == "google:8998728770320543438" }
        val engines = listOf("ja", "en", "ko", "pt", "id").associateWith { language ->
            val languageIndex = if (language == "ja") index else index.parent.resolve(language)
            assertTrue(Files.isDirectory(languageIndex), "Missing $language index: $languageIndex")
            ChurchSearchEngine(
                languageIndex.toString().toPath(),
                geonames,
                "lightpanda-e2e",
                pageUrls,
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
            val browser = LightPanda(
                timeout = Duration.ofSeconds(30),
                renderWait = Duration.ofSeconds(15),
            )
            val indexHtml = browser.fetchHtml("http://127.0.0.1:$port/")
            assertTrue(indexHtml.contains("id=\"search-form\""), indexHtml.take(1_000))
            assertTrue(indexHtml.contains("id=\"language\""), indexHtml.take(1_000))

            val queries = supportedLanguageCodes.associateWith { language ->
                when (language) {
                    "ja" -> fusaChurch.name
                    "en" -> fusaChurch.englishName
                    else -> assertNotNull(
                        fusaChurch.localizedNames.firstOrNull {
                            it.languageCode.substringBefore('-').lowercase() == language
                        }?.name,
                        "Fusa church is missing its $language localized name",
                    )
                }
            }
            val resultPages = queries.mapValues { (language, rawQuery) ->
                val query = URLEncoder.encode(rawQuery, Charsets.UTF_8.name())
                val html = browser.fetchHtml("http://127.0.0.1:$port/result.html?q=$query")
                assertTrue(html.contains("class=\"result\""), "$language: ${html.take(2_000)}")
                assertTrue(html.contains("Fusa Christ Church"), "$language: ${html.take(2_000)}")
                assertTrue(html.contains("id=\"language\""), "$language: ${html.take(2_000)}")
                assertFalse(html.contains("Field 'englishName' is required"), "$language: ${html.take(2_000)}")
                assertFalse(html.contains("/church.html?id="), "$language: ${html.take(2_000)}")
                html
            }
            supportedLanguageCodes.forEach { language ->
                val rawQuery = assertNotNull(denominationNames.getValue(language)["UCCJ"])
                val query = URLEncoder.encode(rawQuery, Charsets.UTF_8.name())
                val html = browser.fetchHtml("http://127.0.0.1:$port/result.html?q=$query")
                assertTrue(html.contains("class=\"result\""), "denomination $language: ${html.take(2_000)}")
                assertFalse(html.contains("Church index is not configured"), "denomination $language: ${html.take(2_000)}")
            }

            val detailPath = Regex("""href="(/church/[a-z0-9-]+\.html)"""")
                .find(resultPages.getValue("en"))?.groupValues?.get(1)
            assertNotNull(detailPath, resultPages.getValue("en").take(2_000))
            val detailHtml = browser.fetchHtml("http://127.0.0.1:$port$detailPath")
            assertTrue(detailHtml.contains("布佐キリスト教会"), detailHtml.take(2_000))
            assertTrue(detailHtml.contains("id=\"language\""), detailHtml.take(2_000))
            assertTrue(detailHtml.contains("id=\"localized-denomination-options\""), detailHtml.take(2_000))
            supportedLanguageCodes.forEach { language ->
                val denomination = assertNotNull(denominationNames.getValue(language)["JECA"])
                assertTrue(detailHtml.contains(denomination), "$language denomination missing: ${detailHtml.take(2_000)}")
            }
            assertFalse(detailHtml.contains("Field 'englishName' is required"), detailHtml.take(2_000))
        } finally {
            server.stop(1_000, 3_000)
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
