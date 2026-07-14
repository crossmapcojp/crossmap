package jp.co.crossmap

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class ApplicationTest {
    @Test
    fun resolvesPublishedLatestSnapshotWithoutCurrentSymlink() {
        val root = Files.createTempDirectory("crossmap-server-latest")
        try {
            val snapshot = root.resolve("indexes/churches/real-data-v1/index")
            Files.createDirectories(snapshot)
            Files.writeString(
                root.resolve("indexes/churches/latest.json"),
                """{"schemaVersion":1,"indexVersion":"real-data-v1","luceneVersion":"10.2.0-alpha14","createdAt":"2026-07-13T00:00:00Z","documentCount":9473}""",
            )
            assertEquals(snapshot, resolveServerIndex(root, null))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun healthReportsNotReadyWithoutIndex() = testApplication {
        application { module(searchEngine = null) }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("not_ready"))
    }

    @Test
    fun servesCompleteBrowserSearchPagesAndClientScript() {
        val projectRoot = Path.of(requireNotNull(System.getProperty("crossmap.project.root")))
        val resourcesRoot = Files.createTempDirectory("crossmap-server-static")
        try {
            testApplication {
                application {
                    module(
                        searchEngine = null,
                        resourcesRoot = resourcesRoot,
                        webRoot = projectRoot.resolve("webclient"),
                    )
                }

                val index = client.get("/")
                assertEquals(HttpStatusCode.OK, index.status)
                assertTrue(index.bodyAsText().contains("id=\"search-form\""))

                val results = client.get("/result.html")
                assertEquals(HttpStatusCode.OK, results.status)
                assertTrue(results.bodyAsText().contains("id=\"results\""))
                assertTrue(results.bodyAsText().contains("src=\"/app.js\""))

                val church = client.get("/church.html")
                assertEquals(HttpStatusCode.OK, church.status)
                assertTrue(church.bodyAsText().contains("id=\"church\""))

                val script = client.get("/app.js")
                assertEquals(HttpStatusCode.OK, script.status)
                assertTrue(script.bodyAsText().contains("/api/v1/churches/search"))
                assertTrue(script.bodyAsText().contains("/api/v1/churches/"))
            }
        } finally {
            resourcesRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun servesGeneratedEnglishNameChurchPage() {
        val webRoot = Files.createTempDirectory("crossmap-server-generated-church")
        try {
            val page = StaticSiteGenerator().generate(
                churches = listOf(
                    ChurchRecord(
                        id = "official:tokyo-sophia",
                        name = "東京ソフィア長老教会",
                        englishName = "Tokyo Sophia International Presbyterian Church",
                        denominationId = "XLSX_18816F940131",
                        address = "東京都新宿区西早稲田",
                        location = GeoPoint(35.708, 139.709),
                        websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
                    ),
                ),
                denominationEnglishNames = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
                outputDirectory = webRoot.resolve("church"),
            ).single()

            testApplication {
                application { module(searchEngine = null, resourcesRoot = webRoot, webRoot = webRoot) }
                val response = client.get("/church/${page.fileName}")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Tokyo Sophia International Presbyterian Church"))
                assertTrue(response.bodyAsText().contains("Olivet Assembly Japan"))
            }
        } finally {
            webRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun searchAndDetailReturnCanonicalJson() {
        val root = Files.createTempDirectory("crossmap-server")
        try {
            val index = root.resolve("index")
            ChurchIndex.build(
                index.toString().toPath(),
                listOf(
                    ChurchRecord(
                        id = "google:906297735827744432",
                        name = "岡山バプテスト教会",
                        englishName = "Okayama Baptist Church",
                        denominationId = "JBC",
                        address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
                        location = GeoPoint(34.6619806, 133.9231824),
                        websiteUrl = "http://okayama-baptist.jp/",
                        socialProfiles = listOf(SocialProfile(SocialPlatform.YOUTUBE, "https://www.youtube.com/channel/UCCBpKmS8N-lP4FRdOWy1MRQ")),
                    )
                ),
            )
            val engine = ChurchSearchEngine(index.toString().toPath(), emptyList(), "server-fixture")
            testApplication {
                application { module(engine, resourcesRoot = root, webRoot = root) }
                val search = client.get("/api/v1/churches/search?q=岡山バプテスト")
                assertEquals(HttpStatusCode.OK, search.status)
                val response = Json.decodeFromString<ChurchSearchResponse>(search.bodyAsText())
                assertEquals("google:906297735827744432", response.hits.single().churchId)
                assertEquals("Okayama Baptist Church", response.hits.single().englishName)

                val detail = client.get("/api/v1/churches/google%3A906297735827744432")
                assertEquals(HttpStatusCode.OK, detail.status)
                val church = Json.decodeFromString<ChurchDetailResponse>(detail.bodyAsText())
                assertEquals("Okayama Baptist Church", church.englishName)
                assertEquals(SocialPlatform.YOUTUBE, church.socialProfiles.single().platform)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidPaginationUsesStructuredBadRequest() = testApplication {
        application { module(searchEngine = null) }
        val response = client.get("/api/v1/churches/search?q=church&limit=0")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("index_unavailable"))
    }
}
