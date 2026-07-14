package jp.co.crossmap.crawl

import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files

class CachingChurchEnglishNameTranslatorTest {
    @Test
    fun completedBatchesArePersistedAndReused() = runBlocking {
        val directory = Files.createTempDirectory("crossmap-english-name-cache-")
        val cache = directory.resolve("english-name-llm-cache.json")
        val churches = listOf(
            church("google:13045351237600372838", "心の友キリスト教会", "https://kokoronotomo-ch.org/"),
            church("google:9753525676873678048", "日本聖公会聖ルシヤ教会", "http://www.nskk.org/osaka/church/lucia/"),
            church("google:6646597370070891755", "東京バプテスト教会", "https://www.tokyobaptist.org/"),
        )
        var delegateCalls = 0
        val first = CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator { church ->
                delegateCalls++
                ChurchEnglishNameGuess("English ${church.id}", confidence = 0.91f, reasoning = "test")
            },
            model = "cyberagent/CAT-Translate-7b:Q4_K_M",
            cacheFile = cache,
            batchSize = 2,
        )

        val initial = first.translateAll(churches)

        assertEquals(3, initial.size)
        assertEquals(3, delegateCalls)
        assertEquals(3, first.stats.translated)
        assertEquals(2, first.stats.batches)
        val second = CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator { error("cache miss") },
            model = "cyberagent/CAT-Translate-7b:Q4_K_M",
            cacheFile = cache,
        )
        assertEquals(initial, second.translateAll(churches))
        assertEquals(3, second.stats.hits)
    }

    @Test
    fun changedInputOrModelInvalidatesCachedGuess() = runBlocking {
        val cache = Files.createTempDirectory("crossmap-english-name-cache-").resolve("cache.json")
        val original = church("google:1", "経堂キリスト集会", "https://kyodo-assembly.example/")
        CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator {
                ChurchEnglishNameGuess("Kyodo Christian Assembly", confidence = 0.90f, reasoning = "test")
            },
            model = "model-a",
            cacheFile = cache,
        ).translate(original)

        var calls = 0
        val changedName = original.copy(name = "経堂キリスト集会 本部")
        CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator {
                calls++
                ChurchEnglishNameGuess(
                    "Kyodo Christian Assembly Headquarters",
                    confidence = 0.90f,
                    reasoning = "test",
                )
            },
            model = "model-a",
            cacheFile = cache,
        ).translate(changedName)
        CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator {
                calls++
                ChurchEnglishNameGuess("Kyodo Christian Assembly", confidence = 0.90f, reasoning = "test")
            },
            model = "model-b",
            cacheFile = cache,
        ).translate(original)

        assertEquals(2, calls)
    }

    @Test
    fun successfulBatchRemainsCachedWhenLaterBatchFails() = runBlocking {
        val cache = Files.createTempDirectory("crossmap-english-name-cache-").resolve("cache.json")
        val churches = listOf(
            church("google:1", "東京バプテスト教会", "https://www.tokyobaptist.org/"),
            church("google:2", "岡山バプテスト教会", "https://okayama-baptist.example/"),
        )
        var calls = 0
        val failing = CachingChurchEnglishNameTranslator(
            delegate = ChurchEnglishNameTranslator { church ->
                calls++
                if (church.id == "google:2") error("simulated timeout")
                ChurchEnglishNameGuess("Tokyo Baptist Church", confidence = 0.90f, reasoning = "test")
            },
            model = "model-a",
            cacheFile = cache,
            batchSize = 1,
        )

        assertFailsWith<IllegalStateException> { failing.translateAll(churches) }
        assertEquals(1, failing.stats.translated)
        assertEquals(1, failing.stats.errors)
        assertEquals(1, failing.stats.timeouts)
        val persisted = Json.parseToJsonElement(Files.readString(cache)).toString()
        assert(persisted.contains("google:1"))
        assert(!persisted.contains("google:2"))
    }

    private fun church(id: String, name: String, websiteUrl: String) = ChurchEnglishNameInput(
        id = id,
        name = name,
        address = "東京都千代田区",
        location = GeoPoint(35.6812, 139.7671),
        websiteUrl = websiteUrl,
    )
}
