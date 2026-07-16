package jp.co.crossmap

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLEncoder
import java.security.MessageDigest
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
            val cache = root.resolve("cache")
            val snapshot = cache.resolve("search-indexes/churches/real-data-v1/index/ja")
            Files.createDirectories(snapshot)
            val catalog = root.resolve("catalog/churches.json")
            Files.createDirectories(catalog.parent)
            Files.writeString(catalog, "[]")
            val sourceSha256 = MessageDigest.getInstance("SHA-256").digest("[]".toByteArray())
                .joinToString("") { "%02x".format(it) }
            Files.writeString(
                cache.resolve("search-indexes/churches/latest.json"),
                """{"schemaVersion":${ChurchIndex.SCHEMA_VERSION},"indexVersion":"real-data-v1","luceneVersion":"10.2.0-alpha14","createdAt":"2026-07-13T00:00:00Z","documentCount":9473,"sourceSha256":"$sourceSha256"}""",
            )
            assertEquals(snapshot, resolveServerIndex(root, null, cache))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsLatestManifestWhenCanonicalCatalogHasChanged() {
        val root = Files.createTempDirectory("crossmap-server-stale-catalog")
        try {
            val cache = root.resolve("cache")
            Files.createDirectories(cache.resolve("search-indexes/churches/current/index/ja"))
            val catalog = root.resolve("catalog/churches.json")
            Files.createDirectories(catalog.parent)
            Files.writeString(catalog, "changed")
            Files.writeString(
                cache.resolve("search-indexes/churches/latest.json"),
                """{"schemaVersion":${ChurchIndex.SCHEMA_VERSION},"indexVersion":"current","luceneVersion":"10.2.0-alpha14","createdAt":"2026-07-13T00:00:00Z","documentCount":9473,"sourceSha256":"not-current"}""",
            )

            assertEquals(null, resolveServerIndex(root, null, cache))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsSnapshotBuiltBeforeMandatoryEnglishNameSchema() {
        val root = Files.createTempDirectory("crossmap-server-legacy-index")
        try {
            val cache = root.resolve("cache")
            Files.createDirectories(cache.resolve("search-indexes/churches/legacy/index/ja"))
            Files.writeString(
                cache.resolve("search-indexes/churches/latest.json"),
                """{"schemaVersion":1,"indexVersion":"legacy","luceneVersion":"10.2.0-alpha14","createdAt":"2026-07-13T00:00:00Z","documentCount":9473}""",
            )

            assertEquals(null, resolveServerIndex(root, null, cache))
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
                denominationNamesByLanguage = mapOf(
                    "en" to mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
                    "ko" to mapOf("XLSX_18816F940131" to "올리벳 어셈블리 재팬"),
                ),
            ).single()

            testApplication {
                application { module(searchEngine = null, resourcesRoot = webRoot, webRoot = webRoot) }
                val response = client.get("/church/${page.fileName}")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Tokyo Sophia International Presbyterian Church"))
                assertTrue(response.bodyAsText().contains("Olivet Assembly Japan"))
                assertTrue(response.bodyAsText().contains("올리벳 어셈블리 재팬"))
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
                        localizedDenominationNames = listOf(
                            LocalizedName("ja", "日本バプテスト連盟"),
                            LocalizedName("en", "Japan Baptist Convention"),
                            LocalizedName("ko", "일본 침례교 연맹"),
                            LocalizedName("pt", "Convenção Batista do Japão"),
                            LocalizedName("id", "Konvensi Baptis Jepang"),
                        ),
                        address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
                        location = GeoPoint(34.6619806, 133.9231824),
                        websiteUrl = "http://okayama-baptist.jp/",
                        socialProfiles = listOf(SocialProfile(SocialPlatform.YOUTUBE, "https://www.youtube.com/channel/UCCBpKmS8N-lP4FRdOWy1MRQ")),
                    )
                ),
            )
            val detailUrl = "/church/jbc-okayama-baptist-church.html"
            val engine = ChurchSearchEngine(
                index.toString().toPath(),
                emptyList(),
                "server-fixture",
                mapOf("google:906297735827744432" to detailUrl),
            )
            testApplication {
                application { module(engine, resourcesRoot = root, webRoot = root) }
                val search = client.get("/api/v1/churches/search?q=岡山バプテスト")
                assertEquals(HttpStatusCode.OK, search.status)
                val response = Json.decodeFromString<ChurchSearchResponse>(search.bodyAsText())
                assertEquals("google:906297735827744432", response.hits.single().churchId)
                assertEquals("Okayama Baptist Church", response.hits.single().englishName)
                assertEquals(detailUrl, response.hits.single().detailUrl)

                val detail = client.get("/api/v1/churches/google%3A906297735827744432")
                assertEquals(HttpStatusCode.OK, detail.status)
                val church = Json.decodeFromString<ChurchDetailResponse>(detail.bodyAsText())
                assertEquals("Okayama Baptist Church", church.englishName)
                assertEquals(
                    "일본 침례교 연맹",
                    church.localizedDenominationNames.single { it.languageCode == "ko" }.name,
                )
                assertEquals(SocialPlatform.YOUTUBE, church.socialProfiles.single().platform)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun multilingualApiSearchesTranslatedDenominationsAndAddressGeonames() {
        val root = Files.createTempDirectory("crossmap-server-multilingual")
        try {
            val church = ChurchRecord(
                id = "google:2225537460932230335",
                name = "日本聖公会東京聖アンデレ教会",
                englishName = "Tokyo Saint Andrew Church",
                localizedNames = listOf(
                    LocalizedName("ja", "日本聖公会東京聖アンデレ教会"),
                    LocalizedName("en", "Tokyo Saint Andrew Church"),
                    LocalizedName("ko", "도쿄 세인트 앤드류 교회"),
                    LocalizedName("pt", "Igreja de Santo André de Tóquio"),
                    LocalizedName("id", "Gereja Santo Andreas Tokyo"),
                ),
                denominationId = "ANGLICAN_JP",
                localizedDenominationNames = listOf(
                    LocalizedName("ja", "日本聖公会"),
                    LocalizedName("en", "Anglican Church in Japan"),
                    LocalizedName("ko", "일본성공회"),
                    LocalizedName("pt", "Igreja Anglicana no Japão"),
                    LocalizedName("id", "Gereja Anglikan di Jepang"),
                ),
                address = "〒105-0011 東京都港区芝公園３丁目６−１８",
                location = GeoPoint(35.6601808, 139.743601),
                websiteUrl = "http://www.st-andrew-tokyo.com/",
            )
            val geonames = mapOf(
                "ja" to "港区",
                "en" to "Minato City",
                "ko" to "미나토구",
                "pt" to "Distrito de Minato",
                "id" to "Distrik Minato",
            )
            val denominationQueries = mapOf(
                "ja" to "日本聖公会",
                "en" to "Anglican Church in Japan",
                "ko" to "일본성공회",
                "pt" to "Igreja Anglicana no Japão",
                "id" to "Gereja Anglikan di Jepang",
            )
            val engines = supportedLanguageCodes.associateWith { language ->
                val index = root.resolve("index-$language")
                ChurchIndex.build(
                    index.toString().toPath(),
                    listOf(church),
                    language,
                    mapOf(church.id to listOf(geonames.getValue(language))),
                )
                ChurchSearchEngine(index.toString().toPath(), emptyList(), "multilingual-fixture", languageCode = language)
            }

            testApplication {
                application { module(engines.getValue("ja"), engines, resourcesRoot = root, webRoot = root) }
                (denominationQueries.keys).forEach { language ->
                    listOf(denominationQueries.getValue(language), geonames.getValue(language)).forEach { query ->
                        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                        val response = client.get("/api/v1/churches/search?q=$encoded")
                        assertEquals(HttpStatusCode.OK, response.status, "$language: $query")
                        val result = Json.decodeFromString<ChurchSearchResponse>(response.bodyAsText())
                        assertEquals(1, result.hits.size, "$language: $query")
                        assertEquals(church.id, result.hits.single().churchId, "$language: $query")
                        assertEquals(5, result.hits.single().localizedDenominationNames.size)
                    }
                }
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
