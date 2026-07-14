package jp.co.crossmap

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
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

                val detail = client.get("/api/v1/churches/google%3A906297735827744432")
                assertEquals(HttpStatusCode.OK, detail.status)
                val church = Json.decodeFromString<ChurchDetailResponse>(detail.bodyAsText())
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
