package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ComponentChurchEnglishNameTranslatorTest {
    private val uccj = Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))

    @Test
    fun translatesOnlyUnknownKumouchiSpanAndComposesKnownParts() = runBlocking {
        var requests = emptyList<ChurchNameComponentTranslationRequest>()
        val translator = ComponentCompletingChurchEnglishNameTranslator(
            analyzer = ChurchNameComponentAnalyzer(listOf(uccj), geonames = mapOf("神戸" to "Kobe")),
            componentTranslator = ChurchNameComponentTranslator { input ->
                requests = input
                input.associate { it.key to "Kumouchi" }
            },
            fullNameFallback = ChurchEnglishNameTranslator { error("Whole-name fallback must not run") },
            modelName = "component-fixture",
        )

        val guess = translator.translate(kobeKumouchi())

        assertEquals("Kobe Kumouchi Church", guess.englishName)
        assertEquals(1, requests.size)
        assertEquals("雲内", requests.single().japanese)
        assertEquals(ChurchNamePartRole.OTHER, requests.single().role)
        assertEquals("kumochi", requests.single().authoritativeUrlHint)
        assertEquals(1, translator.stats.componentLlmUniqueExecutions)
        assertEquals(0, translator.stats.fullNameLlmFallbacks)
    }

    @Test
    fun realKobeKumouchiReadingIsDeterministic() = runBlocking {
        val translator = ComponentCompletingChurchEnglishNameTranslator(
            analyzer = ChurchNameComponentAnalyzer(listOf(uccj)),
            componentTranslator = ChurchNameComponentTranslator { error("Known 雲内 reading must not invoke an LLM") },
            fullNameFallback = ChurchEnglishNameTranslator { error("Whole-name fallback must not run") },
            modelName = "component-fixture",
        )

        val guess = translator.translate(kobeKumouchi())

        assertEquals("Kobe Kumouchi Church", guess.englishName)
        assertEquals(0, translator.stats.componentLlmUniqueExecutions)
    }

    @Test
    fun realNozomiNameIsNotFragmentedBySingleCharacterLexiconEntries() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "real:nozomi",
            name = "のぞみ教会",
            denominationId = "INDEPENDENT_CHURCH",
            address = "東京都町田市",
            location = GeoPoint(35.546, 139.438),
            websiteUrl = "https://nozomi-church.example/",
        )
        val analysis = ChurchNameComponentAnalyzer(listOf(uccj)).analyze(church)

        assertEquals("Nozomi Church", analysis?.compose())
        assertEquals(listOf("のぞみ"), analysis?.components?.map { it.japanese })
    }

    @Test
    fun identicalTypedComponentsAreRequestedOnlyOncePerBatch() = runBlocking {
        var executionCount = 0
        val translator = ComponentCompletingChurchEnglishNameTranslator(
            analyzer = ChurchNameComponentAnalyzer(listOf(uccj), geonames = mapOf("神戸" to "Kobe")),
            componentTranslator = ChurchNameComponentTranslator { requests ->
                executionCount += requests.size
                requests.associate { it.key to "Kumouchi" }
            },
            fullNameFallback = ChurchEnglishNameTranslator { error("Whole-name fallback must not run") },
            modelName = "component-fixture",
        )
        val second = kobeKumouchi().copy(id = "real:kobe-kumouchi-2")

        val guesses = translator.translateAll(listOf(kobeKumouchi(), second))

        assertEquals(2, guesses.size)
        assertEquals(1, executionCount)
        assertEquals(2, translator.stats.componentLlmPartsRequested)
        assertEquals(1, translator.stats.componentLlmUniqueExecutions)
    }

    @Test
    fun persistentComponentCacheReusesTranslationWithoutDelegateExecution() = runBlocking {
        val cacheFile = Files.createTempDirectory("crossmap-component-cache").resolve("components.json")
        var calls = 0
        val request = ChurchNameComponentTranslationRequest(
            key = "OTHER:雲内:",
            japanese = "雲内",
            role = ChurchNamePartRole.OTHER,
            churchName = "日本基督教団神戸雲内教会",
            address = "兵庫県神戸市",
        )
        val first = CachingChurchNameComponentTranslator(
            delegate = ChurchNameComponentTranslator { requests ->
                calls++
                requests.associate { it.key to "Kumouchi" }
            },
            model = "fixture-v1",
            cacheFile = cacheFile,
        )
        assertEquals("Kumouchi", first.translateAll(listOf(request)).getValue(request.key))
        val second = CachingChurchNameComponentTranslator(
            delegate = ChurchNameComponentTranslator { error("Cached component must not execute delegate") },
            model = "fixture-v1",
            cacheFile = cacheFile,
        )

        assertEquals("Kumouchi", second.translateAll(listOf(request)).getValue(request.key))
        assertEquals(1, calls)
        assertEquals(1, second.stats.hits)
        assertEquals(0, second.stats.translated)
    }

    @Test
    fun unchangedJapaneseCatOutputFallsBackForRealKobeKumouchiComponent() = runBlocking {
        val request = ChurchNameComponentTranslationRequest(
            key = "OTHER:雲内:",
            japanese = "雲内",
            role = ChurchNamePartRole.OTHER,
            churchName = "日本基督教団神戸雲内教会",
            address = "〒657-0051 兵庫県神戸市灘区八幡町１丁目６−９",
        )
        val translator = FallbackChurchNameComponentTranslator(
            primary = ChurchNameComponentTranslator { mapOf(request.key to "雲内") },
            fallback = ChurchNameComponentTranslator { mapOf(request.key to "Kumouchi") },
        )

        assertEquals("Kumouchi", translator.translateAll(listOf(request)).getValue(request.key))
        assertEquals(1, translator.fallbackExecutions)
    }

    @Test
    fun explanatoryCatOutputFallsBackInsteadOfEnteringComponentCache() = runBlocking {
        val request = ChurchNameComponentTranslationRequest(
            key = "OTHER:雲内:kumochi",
            japanese = "雲内",
            role = ChurchNamePartRole.OTHER,
            churchName = "日本基督教団神戸雲内教会",
            address = "兵庫県神戸市灘区八幡町",
            authoritativeUrlHint = "kumochi",
        )
        val translator = FallbackChurchNameComponentTranslator(
            primary = ChurchNameComponentTranslator {
                mapOf(request.key to "Required spelling hint: kumochi is the standard reading")
            },
            fallback = ChurchNameComponentTranslator { mapOf(request.key to "Kumouchi") },
        )

        assertEquals("Kumouchi", translator.translateAll(listOf(request)).getValue(request.key))
        assertEquals(1, translator.fallbackExecutions)
    }

    @Test
    fun conciseAnswerIsExtractedFromModelExplanation() = runBlocking {
        val request = ChurchNameComponentTranslationRequest(
            key = "CONCEPTUAL_NAME:聖和:",
            japanese = "聖和",
            role = ChurchNamePartRole.CONCEPTUAL_NAME,
            churchName = "聖和教会",
            address = "東京都",
        )
        val translator = FallbackChurchNameComponentTranslator(
            primary = ChurchNameComponentTranslator {
                mapOf(request.key to "Note: phonetic rendering follows.\nSeiwa")
            },
            fallback = ChurchNameComponentTranslator { error("Fallback must not run") },
        )

        assertEquals("Seiwa", translator.translateAll(listOf(request)).getValue(request.key))
        assertEquals(0, translator.fallbackExecutions)
    }

    @Test
    fun invalidJapaneseCacheEntryIsDiscardedAndReplaced() = runBlocking {
        val cacheFile = Files.createTempDirectory("crossmap-invalid-component-cache").resolve("components.json")
        Files.writeString(cacheFile, """{"model":"fixture-v1","entries":{"OTHER:雲内:":"雲内"}}""")
        var calls = 0
        val request = ChurchNameComponentTranslationRequest(
            key = "OTHER:雲内:",
            japanese = "雲内",
            role = ChurchNamePartRole.OTHER,
            churchName = "日本基督教団神戸雲内教会",
            address = "兵庫県神戸市",
        )
        val cache = CachingChurchNameComponentTranslator(
            delegate = ChurchNameComponentTranslator { requests ->
                calls++
                requests.associate { it.key to "Kumouchi" }
            },
            model = "fixture-v1",
            cacheFile = cacheFile,
        )

        assertEquals("Kumouchi", cache.translateAll(listOf(request)).getValue(request.key))
        assertEquals(1, calls)
        assertEquals(1, cache.stats.invalidCacheEntries)
        assertEquals(1, cache.stats.translated)
    }

    private fun kobeKumouchi() = ChurchEnglishNameInput(
        id = "google:10003468413261460406",
        name = "日本基督教団 神戸雲内教会",
        denominationId = "UCCJ",
        address = "〒657-0051 兵庫県神戸市灘区八幡町１丁目６−９",
        location = GeoPoint(34.719125, 135.237793),
        websiteUrl = "http://blog.goo.ne.jp/kumochi/",
    )
}
