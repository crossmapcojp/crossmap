package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.CrawledPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DataCleanupTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun appliesProgrammaticThenLlmThenHumanPrecedenceAndKeepsUncertainForReview() = runBlocking {
        val root = Files.createTempDirectory("crossmap-cleanup")
        val cache = root.resolve("cache")
        try {
            Files.createDirectories(root.resolve("catalog"))
            Files.createDirectories(root.resolve("cleanup"))
            Files.createDirectories(cache.resolve("cleanup"))
            val churches = listOf(
                church("google:2225537460932230335", "日本聖公会東京聖アンデレ教会"),
                church("google:10291805004018342477", "フルゴスペル八王子教会"),
                church("google:10009540859480007974", "栗山地の塩キリスト教会"),
                church("google:12083726217471771398", "横浜山手聖公会"),
                church("google:906297735827744432", "岡山バプテスト教会", "JBC"),
            )
            Files.writeString(root.resolve("catalog/churches.json"), json.encodeToString(churches))
            Files.writeString(
                cache.resolve("cleanup/denomination-candidates.json"),
                json.encodeToString(
                    listOf(
                        DenominationCandidate("ANGLICAN_JP", "日本聖公会東京聖アンデレ教会", source = "日本聖公会公式教会一覧"),
                        DenominationCandidate("FGJA", "純福音東京教会 순복음동경교회", source = "純福音日本総会公式教会一覧"),
                    )
                ),
            )
            Files.writeString(root.resolve("cleanup/denomination-rules.json"), "[]")
            Files.writeString(
                root.resolve("cleanup/human-overrides.json"),
                json.encodeToString(listOf(HumanOverride("google:12083726217471771398", value = "ANGLICAN_JP", note = "日本聖公会公式教会一覧で確認"))),
            )
            var calls = 0
            val matcher = EntityMatcher { input ->
                calls++
                if (input.churchId == "google:10291805004018342477") EntityMatchDecision("FGJA", 0.91, reasoning = "公式サイトが純福音教会を示す")
                else EntityMatchDecision("JBC", 0.72, reasoning = "教会名の類似性が弱い")
            }

            val report = PostCrawlCleanup(matcher, confidenceThreshold = 0.80).run(root, cacheRoot = cache)
            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(root.resolve("catalog/churches.json"))).associateBy { it.id }

            assertEquals(1, report.programmaticAccepted)
            assertEquals(1, report.llmAccepted)
            assertEquals(1, report.uncertain)
            assertEquals(1, report.humanOverrides)
            assertEquals(2, calls)
            assertEquals("ANGLICAN_JP", updated.getValue("google:2225537460932230335").denominationId)
            assertEquals(DeterminationSource.PROGRAMMATIC, updated.getValue("google:2225537460932230335").determinations.single().source)
            assertEquals("FGJA", updated.getValue("google:10291805004018342477").denominationId)
            assertEquals(DeterminationSource.LLM, updated.getValue("google:10291805004018342477").determinations.single().source)
            assertEquals(NOT_DETERMINED, updated.getValue("google:10009540859480007974").denominationId)
            assertEquals(DeterminationSource.HUMAN, updated.getValue("google:12083726217471771398").determinations.single().source)
            assertTrue(Files.readString(cache.resolve("cleanup/decisions.json")).contains("教会名の類似性が弱い"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deterministicPipelineChecksNameThenCrawledPagesAndRejectsAmbiguousTerms() {
        val rules = listOf(
            DenominationRule("UCCJ", "日本基督教団", churchNameComponents = listOf("日本キリスト教団"), source = "UCCJ rule"),
            DenominationRule("JBC", "日本バプテスト連盟", churchNameComponents = listOf("バプテスト連盟"), source = "JBC rule"),
        )
        val matcher = ProgrammaticDenominationMatcher()
        val byName = church("google:10003118417314172796", "日本基督教団 八頭教会")
        val byPage = church("google:906297735827744432", "岡山バプテスト教会").copy(
            pages = listOf(CrawledPage("http://okayama-baptist.jp/", title = "岡山バプテスト教会", text = "岡山バプテスト教会は日本バプテスト連盟に所属します。"))
        )
        val ambiguous = church("google:10009540859480007974", "栗山地の塩キリスト教会").copy(
            pages = listOf(CrawledPage("http://church.ne.jp/kuriyamasalt/", text = "日本基督教団と日本バプテスト連盟の歴史を紹介します。"))
        )

        assertEquals("UCCJ", matcher.match(byName, rules, emptyList())?.denominationId)
        assertEquals("JBC", matcher.match(byPage, rules, emptyList())?.denominationId)
        assertEquals(null, matcher.match(ambiguous, rules, emptyList()))
    }

    @Suppress("DEPRECATION")
    @Test
    fun attachmentEnglishNameFunctionsDelegateToDeterministicAndLlmResolver() {
        val resolver = ChurchEnglishNameResolver(
            translator = { church ->
                assertEquals("google:10291805004018342477", church.id)
                ChurchEnglishNameGuess(
                    englishName = "Hachioji Full Gospel Church",
                    parts = listOf(
                        TranslatedChurchNamePart("八王子", ChurchNamePartRole.GEONAME, "Hachioji"),
                        TranslatedChurchNamePart("フルゴスペル", ChurchNamePartRole.TRADITION, "Full Gospel"),
                        TranslatedChurchNamePart("教会", ChurchNamePartRole.CONGREGATION, "Church"),
                    ),
                    confidence = 0.92f,
                    reasoning = "real Japanese church-name parts",
                    model = "test-japanese-model",
                )
            },
        )
        val cleanup = PostCrawlCleanup(
            matcher = EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "not used by English-name test") },
            englishNameResolver = resolver,
        )
        val deterministic = church("google:906297735827744432", "岡山バプテスト教会").copy(
            pages = listOf(
                CrawledPage(
                    "http://okayama-baptist.jp/",
                    title = "Okayama Hope Church | 岡山バプテスト教会",
                    text = "岡山県岡山市にある教会です。",
                ),
            ),
        )
        val llm = church("google:10291805004018342477", "フルゴスペル八王子教会")

        assertEquals("Okayama Baptist Church", cleanup.findOutChurchEnglishName(deterministic))
        assertEquals("Hachioji Full Gospel Church", cleanup.findSplitAndTranslateChurchNameToEnglishByLlm(llm))
        assertEquals("Hachioji Full Gospel Church", cleanup.findSplitAndTranslateChurchNameToEnglishbyLlmz(llm))
    }

    private fun church(id: String, name: String, denomination: String = NOT_DETERMINED): ChurchRecord {
        val real = mapOf(
            "google:2225537460932230335" to Triple("〒105-0011 東京都港区芝公園３丁目６−１８", GeoPoint(35.6601808, 139.743601), "http://www.st-andrew-tokyo.com/"),
            "google:10291805004018342477" to Triple("〒192-0055 東京都八王子市八木町５−７", GeoPoint(35.6610298, 139.3217773), "http://www.fgtc.jp/church/101/"),
            "google:10009540859480007974" to Triple("〒069-1521 北海道夕張郡栗山町錦３丁目１−１１", GeoPoint(43.0588134, 141.7746045), "http://church.ne.jp/kuriyamasalt/"),
            "google:12083726217471771398" to Triple("〒231-0862 神奈川県横浜市中区山手町２３５", GeoPoint(35.4380585, 139.6524249), "https://yamate-anglican.jpn.org/"),
            "google:906297735827744432" to Triple("〒700-0825 岡山県岡山市北区田町１丁目７−２８", GeoPoint(34.6619806, 133.9231824), "http://okayama-baptist.jp/"),
            "google:10003118417314172796" to Triple("〒680-0463 鳥取県八頭郡八頭町宮谷２２２", GeoPoint(35.415172, 134.2543793), "https://www.google.com/maps?cid=10003118417314172796"),
        ).getValue(id)
        return ChurchRecord(
        id = id,
        name = name,
        englishName = when (name) {
            "岡山バプテスト教会" -> "Okayama Baptist Church"
            "日本聖公会東京聖アンデレ教会" -> "Tokyo St Andrew's Church"
            else -> "Tokyo Sophia International Presbyterian Church"
        },
        denominationId = denomination,
        address = real.first,
        location = real.second,
        websiteUrl = real.third,
    )
    }
}
