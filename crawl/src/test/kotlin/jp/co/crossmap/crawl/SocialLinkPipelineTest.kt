package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SocialLinkPipelineTest {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun cachedWebsiteHyperlinkPublishesProgrammaticSocialProfileWithoutLlm() = runBlocking {
        val root = Files.createTempDirectory("crossmap-social-link")
        val cache = root.resolve("cache")
        Files.createDirectories(root.resolve("catalog"))
        Files.createDirectories(root.resolve("evidence"))
        Files.createDirectories(cache.resolve("web-pages/pages"))
        val church = ChurchRecord(
            id = "google:906297735827744432",
            name = "岡山バプテスト教会",
            englishName = "Okayama Baptist Church",
            address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
            location = GeoPoint(34.6619806, 133.9231824),
            websiteUrl = "http://okayama-baptist.jp/",
        )
        val account = SocialAccountCandidate(
            id = "youtube:okayama-baptist",
            platform = SocialPlatform.YOUTUBE,
            url = "https://www.youtube.com/channel/UCCBpKmS8N-lP4FRdOWy1MRQ",
            accountName = "岡山バプテスト教会",
        )
        Files.writeString(root.resolve("catalog/churches.json"), json.encodeToString(listOf(church)))
        Files.writeString(root.resolve("evidence/social-accounts.json"), json.encodeToString(listOf(account)))
        Files.writeString(cache.resolve("web-pages/pages/page.html"), "<html><a href='https://www.youtube.com/channel/UCCBpKmS8N-lP4FRdOWy1MRQ/'>YouTube</a></html>")
        Files.writeString(
            cache.resolve("web-pages/manifest.json"),
            json.encodeToString(
                listOf(
                    CrawlManifestEntry(
                        churchId = church.id,
                        requestedUrl = church.websiteUrl,
                        finalUrl = church.websiteUrl,
                        cachePath = "pages/page.html",
                        fetchedAt = "2026-01-01T00:00:00Z",
                        status = 200,
                        contentHash = "page",
                    )
                )
            ),
        )
        var llmCalls = 0

        val report = SocialLinkPipeline(fakeLlm { llmCalls++; 0f }).run(root, applyChanges = true, cacheRoot = cache)

        assertEquals(1, report.directLinksAccepted)
        assertEquals(0, llmCalls)
        val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).single()
        assertEquals(account.url, updated.socialProfiles.single().url)
        assertEquals(DeterminationSource.PROGRAMMATIC, updated.determinations.single().source)
        assertTrue(Files.isRegularFile(cache.resolve("cleanup/social-decisions.json")))
    }

    private fun fakeLlm(score: suspend () -> Float) = object : LlmEntitySimilarityMatcher {
        override suspend fun determineSameAddressByLlm(address1: String, address2: String) = score()
        override suspend fun determineSameNameByLlm(name1: String, name2: String) = score()
        override suspend fun churchNameMatchesByLlm(churchName1: String, churchName2: String) = score()
        override suspend fun determineSameEntityByLlm(input: EntitySimilarityInput) = score()
    }
}
