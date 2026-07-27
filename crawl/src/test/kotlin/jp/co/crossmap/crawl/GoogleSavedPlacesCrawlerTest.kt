package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class GoogleSavedPlacesCrawlerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readsRealJapaneseTakeoutRowsAndMergesTheSamePlaceAcrossLists() {
        val root = Files.createTempDirectory("crossmap-google-saved")
        try {
            val input = Files.createDirectories(root.resolve("Takeout/Saved"))
            val mapsUrl = "https://www.google.com/maps/place/%E5%90%8C%E7%9B%9F%E7%A6%8F%E9%9F%B3%E3%82%B0%E3%83%AC%E3%83%BC%E3%82%B9%E3%83%81%E3%83%A3%E3%83%9A%E3%83%AB%E6%AD%A6%E8%B1%8A/data=!4m2!3m1!1s0x600489b598cd455f:0xcae47689207f21a9"
            Files.writeString(
                input.resolve("教会.csv"),
                "タイトル,メモ,URL,コメント\n同盟福音グレースチャペル武豊,,${mapsUrl},\n",
            )
            Files.writeString(
                input.resolve("確認済み教会.csv"),
                "タイトル,メモ,URL,コメント\n同盟福音グレースチャペル武豊,公式サイト確認済み,${mapsUrl},同じ教会\n",
            )
            val output = root.resolve("resources/raw/google-saved-places/seeds.json")

            val report = GoogleSavedPlacesCrawler().readDirectory(input, output)
            val seeds = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(Files.readString(output))

            assertEquals(2, report.filesRead)
            assertEquals(2, report.rowsRead)
            assertEquals(1, report.seedsWritten)
            assertEquals(1, report.duplicatesMerged)
            assertTrue(report.errors.isEmpty())
            assertEquals("14619940621679272361", seeds.single().googleCid)
            assertEquals("google:14619940621679272361", seeds.single().id)
            assertEquals(listOf("教会", "確認済み教会").sorted(), seeds.single().sourceLists)
            assertEquals("公式サイト確認済み", seeds.single().note)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun supportsQuotedMultilineFieldsAndReportsBadGoogleUrlsWithoutStoppingOtherRows() {
        val root = Files.createTempDirectory("crossmap-google-saved-errors")
        try {
            val input = Files.createDirectories(root.resolve("Saved"))
            Files.writeString(
                input.resolve("カトリック教会.csv"),
                "\uFEFFタイトル,メモ,URL,コメント\r\n" +
                    "カトリック厚木教会,\"一行目\n二行目\",https://www.google.com/maps/place/x/data=!4m2!3m1!1s0x6018550076045277:0x1bc4a5d3151c690d,\r\n" +
                    "盛岡ドミニカン修道院,,https://example.org/not-google-maps,\r\n",
            )
            val output = root.resolve("seeds.json")

            val report = GoogleSavedPlacesCrawler().readDirectory(input, output)
            val seeds = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(Files.readString(output))

            assertEquals(2, report.rowsRead)
            assertEquals(1, report.seedsWritten)
            assertEquals(1, report.errors.size)
            assertEquals(3, report.errors.single().rowNumber)
            assertEquals("2000906460470208781", seeds.single().googleCid)
            assertEquals("一行目\n二行目", seeds.single().note)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun acceptsCanonicalCidQueryUrlsUsedAfterTheFirstResolution() {
        assertEquals(
            "5433858323697585828",
            GoogleSavedPlacesCrawler.googleCid("https://www.google.com/maps?cid=5433858323697585828"),
        )
    }

    @Test
    fun keepsSavedPlaceNamesRawForTheResolverOwnedLocalizationWorkflow() {
        val root = Files.createTempDirectory("crossmap-saved-name-parts")
        try {
            val header = "タイトル,メモ,URL,コメント\n"
            Files.writeString(
                root.resolve("教会.csv"),
                header + "Just Church（ジャスト・チャーチ）,,https://www.google.com/maps?cid=5433858323697585828,\n",
            )
            val output = root.resolve("seeds.json")

            val report = GoogleSavedPlacesCrawler().readDirectory(root, output)
            val seed = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(Files.readString(output)).single()

            assertEquals("Just Church（ジャスト・チャーチ）", seed.title)
            assertEquals(null, seed.japaneseName)
            assertEquals(null, seed.latinName)
            assertTrue(seed.localizedNames.isEmpty())
            assertEquals(listOf("en", "ja"), seed.titleLanguages)
            assertEquals(ChurchNamePattern.SINGLE_NAME, seed.namePattern)
            assertTrue(report.namePatternCounts.isEmpty())
            assertEquals(mapOf("en" to 1, "ja" to 1), report.languageCounts)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun defaultGmapListScopeAndExclusionsCanBeAppliedWithoutHardCodedPaths() {
        val root = Files.createTempDirectory("crossmap-google-saved-scope")
        try {
            val churchUrl = "https://www.google.com/maps/place/x/data=!4m2!3m1!1s0x600489b598cd455f:0xcae47689207f21a9"
            val catholicUrl = "https://www.google.com/maps/place/x/data=!4m2!3m1!1s0x6018550076045277:0x1bc4a5d3151c690d"
            val unrelatedUrl = "https://www.google.com/maps/place/x/data=!4m2!3m1!1s0x6018e7bdb9619b77:0x4b68f15b07cb4ea4"
            val header = "タイトル,メモ,URL,コメント\n"
            Files.writeString(root.resolve("教会.csv"), header + "同盟福音グレースチャペル武豊,,$churchUrl,\n")
            Files.writeString(root.resolve("カトリック教会.csv"), header + "カトリック厚木教会,,$catholicUrl,\n")
            Files.writeString(root.resolve("クリスチャン企業.csv"), header + "対象外,,$unrelatedUrl,\n")

            val report = GoogleSavedPlacesCrawler().readDirectory(
                inputDirectory = root,
                output = root.resolve("seeds.json"),
                includedLists = GoogleSavedPlacesCrawler.GMAP_DEFAULT_LISTS,
                excludedUrls = setOf(catholicUrl),
            )
            val seeds = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(Files.readString(root.resolve("seeds.json")))

            assertEquals(2, report.filesRead)
            assertEquals(2, report.rowsRead)
            assertEquals(listOf("同盟福音グレースチャペル武豊"), seeds.map { it.title })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
