package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SocialAccountLinkerTest {
    @Test
    fun directChurchPageLinkIsCertainAndSkipsLlm() = runBlocking {
        var llmCalls = 0
        val llm = fakeLlm { _, _ -> llmCalls++; 0f }
        val linker = SocialAccountLinker(llm)
        val account = SocialAccountCandidate("youtube:st-andrew", SocialPlatform.YOUTUBE, "https://www.youtube.com/channel/UC3h1K9shoxL9ejQofV3FunA", "聖アンデレ教会")

        val result = linker.link(church("日本聖公会東京聖アンデレ教会"), account, mapOf("http://www.st-andrew-tokyo.com/" to listOf("https://www.youtube.com/channel/UC3h1K9shoxL9ejQofV3FunA/")))

        assertTrue(result.matched)
        assertEquals(1f, result.score)
        assertEquals(DeterminationSource.PROGRAMMATIC, result.source)
        assertEquals(0, llmCalls)
    }

    @Test
    fun ambiguousNameUsesLlmAndLowScoreStaysUnmatched() = runBlocking {
        var llmCalls = 0
        val llm = fakeLlm { _, _ -> llmCalls++; 0.42f }
        val linker = SocialAccountLinker(llm)
        val account = SocialAccountCandidate("x:st-andrew", SocialPlatform.X, "https://x.com/st_andrews_tokyo", "東京聖アンドレ教会公式")

        val result = linker.link(church("日本聖公会東京聖アンデレ教会"), account, emptyMap())

        assertFalse(result.matched)
        assertEquals(DeterminationSource.LLM, result.source)
        assertEquals(1, llmCalls)
    }

    @Test
    fun containingGooglePlaceAndSocialNamesAreCertainAndSkipLlm() = runBlocking {
        var llmCalls = 0
        val llm = fakeLlm { _, _ -> llmCalls++; 0f }
        val linker = SocialAccountLinker(llm)
        val account = SocialAccountCandidate("instagram:st-andrew", SocialPlatform.INSTAGRAM, "https://instagram.com/st_andrews_tokyo", "東京聖アンデレ教会")

        val result = linker.link(church("日本聖公会東京聖アンデレ教会"), account, emptyMap())

        assertTrue(result.matched)
        assertEquals(1f, result.score)
        assertEquals(DeterminationSource.PROGRAMMATIC, result.source)
        assertEquals(0, llmCalls)
    }

    private fun church(name: String) = ChurchRecord(
        id = "google:2225537460932230335", name = name, englishName = "Tokyo St Andrew's Church",
        address = "〒105-0011 東京都港区芝公園３丁目６−１８",
        location = GeoPoint(35.6601808, 139.743601), websiteUrl = "http://www.st-andrew-tokyo.com/",
    )

    private fun fakeLlm(score: suspend (String, String) -> Float) = object : LlmEntitySimilarityMatcher {
        override suspend fun determineSameAddressByLlm(address1: String, address2: String) = score(address1, address2)
        override suspend fun determineSameNameByLlm(name1: String, name2: String) = score(name1, name2)
        override suspend fun churchNameMatchesByLlm(churchName1: String, churchName2: String) = score(churchName1, churchName2)
        override suspend fun determineSameEntityByLlm(input: EntitySimilarityInput) = score(input.leftName, input.rightName)
    }
}
