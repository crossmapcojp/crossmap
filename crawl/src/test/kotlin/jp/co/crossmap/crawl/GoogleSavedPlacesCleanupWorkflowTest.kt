package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GoogleSavedPlacesCleanupWorkflowTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun recordsTakiyamaBibleBaptistChurchWithoutReligiousCorporationMarker() = runBlocking {
        val root = Files.createTempDirectory("crossmap-google-name-cleanup")
        try {
            Files.createDirectories(root.resolve("cache/google-saved-places"))
            Files.createDirectories(root.resolve("catalog"))
            val raw = candidate(
                cid = "12372765701472218650",
                name = "宗教法人滝山聖書バプテスト教会",
                address = "〒203-0033 東京都東久留米市滝山７丁目３−１６",
                denomination = "JBBF",
                latinName = "Takiyama Bible Baptist Church",
            ).copy(localizedNames = listOf(LocalizedName("ja", "宗教法人／滝山聖書バプテスト教会")))
            Files.writeString(
                root.resolve("cache/google-saved-places/google-place-candidates.json"),
                json.encodeToString(listOf(raw)),
            )
            val resolver = ChurchEnglishNameResolver(
                translator = { error("The existing Latin name should be reused") },
            )
            GoogleSavedPlacesCleanupWorkflow(
                postCrawlCleanup = PostCrawlCleanup(
                    matcher = EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "LLM disabled") },
                    englishNameResolver = resolver,
                ),
                englishNameResolver = resolver,
            ).preparePendingCatalog(root, root.resolve("cache"))

            val recorded = json.decodeFromString<List<ChurchRecord>>(
                Files.readString(root.resolve("cache/cleanup/google-saved-places-pending.json")),
            ).single()
            assertEquals("滝山聖書バプテスト教会", recorded.name)
            assertEquals("滝山聖書バプテスト教会", recorded.localizedNames.single { it.languageCode == "ja" }.name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun promotesResolvedCandidatesOnlyAfterExistingCleanupAndMandatoryEnglishNames() = runBlocking {
        val root = Files.createTempDirectory("crossmap-google-cleanup-workflow")
        try {
            Files.createDirectories(root.resolve("cache/google-saved-places"))
            Files.createDirectories(root.resolve("catalog"))
            val candidates = listOf(
                candidate(
                    cid = "14619940621679272361",
                    name = "（宗教法人） 同盟福音グレースチャペル武豊",
                    address = "〒470-2303 愛知県知多郡武豊町１丁目４７番地",
                    denomination = null,
                    latinName = "Grace",
                ),
                candidate(
                    cid = "2000906460470208781",
                    name = "カトリック厚木教会",
                    address = "〒243-0014 神奈川県厚木市旭町２丁目７−１１",
                    denomination = null,
                    sourceList = "カトリック教会",
                ),
            )
            Files.writeString(
                root.resolve("cache/google-saved-places/google-place-candidates.json"),
                json.encodeToString(candidates),
            )
            val existing = listOf(
                church(candidates[0], "Grace Chapel Taketoyo", "JEA").copy(
                    determinations = listOf(
                        FieldDetermination(
                            field = "englishName",
                            value = "Grace Chapel Taketoyo",
                            source = DeterminationSource.LLM,
                            confidence = 0.91,
                            model = "cat-translate:7b-q4_k_m",
                        ),
                    ),
                ),
                church(candidates[1], "Atsugi Catholic Church", "CATHOLIC_JP"),
                ChurchRecord(
                    id = "official:tokyo-sophia",
                    name = "東京ソフィア長老教会",
                    englishName = "Tokyo Sophia International Presbyterian Church",
                    denominationId = "OLIVET_ASSEMBLY_JAPAN",
                    address = "東京都新宿区西早稲田",
                    location = GeoPoint(35.708, 139.709),
                    websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
                ),
            )
            Files.writeString(root.resolve("catalog/churches.json"), json.encodeToString(existing))

            val resolver = ChurchEnglishNameResolver(
                translator = { church -> error("Existing English name should be reused for ${church.id}") },
            )
            val workflow = GoogleSavedPlacesCleanupWorkflow(
                postCrawlCleanup = PostCrawlCleanup(
                    matcher = EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "LLM disabled") },
                    englishNameResolver = resolver,
                ),
                englishNameResolver = resolver,
                now = { "2026-07-14T09:00:00Z" },
            )

            val report = workflow.run(
                resourcesRoot = root,
                enableLlm = false,
                refreshWebsites = false,
                crawlDirectories = false,
                cacheRoot = root.resolve("cache"),
            )
            val promoted = json.decodeFromString<List<ChurchRecord>>(
                Files.readString(root.resolve("catalog/churches.json")),
            ).associateBy(ChurchRecord::id)

            assertTrue(report.promoted)
            assertEquals(2, report.existingEvidenceReused)
            assertEquals(1, report.nonGoogleRecordsRetained)
            assertEquals(3, report.finalChurches)
            assertEquals(2, report.englishNamesProgrammatic)
            assertEquals(1, report.englishNamesLlm)
            assertTrue(promoted.values.all { it.englishName.isNotBlank() })
            assertEquals("同盟福音グレースチャペル武豊", promoted.getValue(candidates[0].id).name)
            assertEquals(
                DeterminationSource.LLM,
                promoted.getValue(candidates[0].id).determinations.single { it.field == "englishName" }.source,
            )
            assertEquals("CATHOLIC_JP", promoted.getValue(candidates[1].id).denominationId)
            assertEquals(
                DeterminationSource.PROGRAMMATIC,
                promoted.getValue(candidates[1].id).determinations.single { it.field == "denominationId" }.source,
            )
            assertEquals(
                listOf("Google Saved Places list: カトリック教会"),
                promoted.getValue(candidates[1].id).determinations.single { it.field == "denominationId" }.evidence,
            )
            assertTrue(Files.isRegularFile(root.resolve("cache/cleanup/google-saved-places-pending.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun candidate(
        cid: String,
        name: String,
        address: String,
        denomination: String?,
        latinName: String? = null,
        sourceList: String = if (denomination == "CATHOLIC_JP") "カトリック教会" else "教会",
    ) =
        GooglePlaceChurchCandidate(
            id = "google:$cid",
            googleCid = cid,
            name = name,
            latinName = latinName,
            address = address,
            location = GeoPoint(35.0, 136.0),
            websiteUrl = "https://www.google.com/maps?cid=$cid",
            category = "キリスト教会",
            denominationHint = denomination,
            sourceLists = listOf(sourceList),
            resolvedAt = "2026-07-14T08:00:00Z",
        )

    private fun church(candidate: GooglePlaceChurchCandidate, englishName: String, denomination: String) = ChurchRecord(
        id = candidate.id,
        googleCid = candidate.googleCid,
        name = candidate.name,
        englishName = englishName,
        denominationId = denomination,
        category = candidate.category,
        address = candidate.address,
        location = candidate.location,
        websiteUrl = candidate.websiteUrl,
    )
}
