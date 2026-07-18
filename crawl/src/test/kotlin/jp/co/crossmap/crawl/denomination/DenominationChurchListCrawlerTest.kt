package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.crawl.NOT_DETERMINED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DenominationChurchListCrawlerTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun uccjParserReadsRealChurchRowsAndSkipsDioceseHeadings() {
        val html = """
            <table class="kyokai">
              <tr><th>教会名</th><th>教区</th><th>〒</th><th>所在地</th><th>TEL</th><th>FAX</th></tr>
              <tr><td class="name">【東海教区】</td><td class="kyouku"></td><td class="postno"></td><td class="address"></td><td class="tel"></td><td class="fax"></td></tr>
              <tr><td class="name">修善寺教会</td><td class="kyouku">東海</td><td class="postno">410-2407</td><td class="address">伊豆市柏久保455-1</td><td class="tel">0558-72-2300</td><td class="fax">0558-72-2300</td></tr>
              <tr><td class="name">沼津教会</td><td class="kyouku">東海</td><td class="postno">410-0888</td><td class="address">沼津市末広町95</td><td class="tel">055-963-5657</td><td class="fax">055-963-5657</td></tr>
            </table>
        """.trimIndent()

        val churches = UCCJDenominationChurchListCrawler().parse(html)

        assertEquals(listOf("修善寺教会", "沼津教会"), churches.map(OfficialDenominationChurch::name))
        assertEquals("〒410-2407 伊豆市柏久保455-1", churches.first().address)
        assertEquals("東海", churches.first().jurisdiction)
        assertTrue(churches.all { it.membershipStatus == OfficialChurchMembershipStatus.LISTED })
    }

    @Test
    fun jbcParserReadsRealTableShapeAndMarksPendingApplicantIneligible() {
        val html = """
            <table class="church-table">
              <tr><td class="kyoku" colspan="5">中国四国バプテスト連合</td></tr>
              <tr><td></td><td>教会名称</td><td>郵便番号</td><td>教会住所</td><td>教会電話番号</td></tr>
              <tr><td class="td-center">201</td><td class="c-name">岡山バプテスト教会</td><td>〒700-0825</td><td>岡山県岡山市北区田町1丁目7-28</td><td>TEL 086-222-7684</td></tr>
              <tr><td class="td-center">202</td><td class="c-name">太田ビジョンキリスト教会 ※第72回定期総会において連盟加盟申請中</td><td>〒373-0821</td><td>群馬県太田市下浜田町1085-50</td><td></td></tr>
            </table>
        """.trimIndent()

        val churches = JBCDenominationChurchListCrawler().parse(html)

        assertEquals("岡山バプテスト教会", churches.first().name)
        assertEquals("〒700-0825 岡山県岡山市北区田町1丁目7-28", churches.first().address)
        assertEquals("中国四国バプテスト連合", churches.first().jurisdiction)
        assertEquals("太田ビジョンキリスト教会", churches.last().name)
        assertEquals(OfficialChurchMembershipStatus.PENDING, churches.last().membershipStatus)
        assertFalse(churches.last().eligibleForDenominationEvidence)
    }

    @Test
    fun runnerWritesTypedPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-uccj-list")
        try {
            val crawler = UCCJDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        "<table class='kyokai'><tr><td class='name'>修善寺教会</td><td class='kyouku'>東海</td><td class='postno'>410-2407</td><td class='address'>伊豆市柏久保455-1</td><td class='tel'></td><td class='fax'></td></tr></table>",
                        "2026-07-18T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/uccj-churches.json")),
            )
            assertEquals("UCCJ", written.denominationId)
            assertEquals("修善寺教会", written.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun authoritativeListsCorrectProgrammaticLabelsButPreserveHumanReview() {
        val root = Files.createTempDirectory("crossmap-denomination-reconcile")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val catalog = listOf(
                church("google:906297735827744432", "岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町１丁目７−２８", "JBC"),
                church("google:10003118417314172796", "日本基督教団 八頭教会", "〒680-0463 鳥取県八頭郡八頭町宮谷２２２", NOT_DETERMINED),
                church("google:3006657650131411393", "イエス愛の教会", "〒417-0073 静岡県富士市浅間本町１", "JBC", "https://www.facebook.com/fujijesuslove/"),
                church("google:10287390816407389399", "沼津キリストの教会", "〒410-0853 静岡県沼津市常盤町２丁目３", "JBC", "https://www.facebook.com/profile.php?id=100064332113625"),
                church("google:human", "人手確認済みバプテスト教会", "〒100-0001 東京都千代田区千代田１", "JBC").copy(
                    determinations = listOf(FieldDetermination("denominationId", "JBC", DeterminationSource.HUMAN, 1.0)),
                ),
            )
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(catalog))
            val lists = listOf(
                officialList("JBC", listOf(OfficialDenominationChurch("岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町1丁目7-28"))),
                officialList("UCCJ", listOf(OfficialDenominationChurch("八頭教会", "〒680-0463 鳥取県八頭郡八頭町宮谷222"))),
            )

            val report = OfficialDenominationChurchListReconciler(json = json).reconcile(catalogFile, lists)
            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile)).associateBy(ChurchRecord::id)

            assertEquals("JBC", updated.getValue("google:906297735827744432").denominationId)
            assertEquals("UCCJ", updated.getValue("google:10003118417314172796").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:3006657650131411393").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:10287390816407389399").denominationId)
            assertEquals("JBC", updated.getValue("google:human").denominationId)
            assertEquals(1, report.assigned)
            assertEquals(2, report.removedUnsupportedLabels)
            assertEquals(1, report.humanOverridesPreserved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun oneOfficialRowCanAuthorizeOnlyOneCanonicalChurchRecord() {
        val root = Files.createTempDirectory("crossmap-denomination-one-to-one")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val exact = church("google:1", "岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町１丁目７−２８", "JBC")
            val staleDuplicate = church("google:2", "岡山バプテスト教会別館", "〒700-0826 岡山県岡山市北区磨屋町", "JBC")
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(listOf(exact, staleDuplicate)))

            OfficialDenominationChurchListReconciler(json = json).reconcile(
                catalogFile,
                listOf(officialList("JBC", listOf(OfficialDenominationChurch("岡山バプテスト教会", exact.address)))),
            )
            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile)).associateBy(ChurchRecord::id)

            assertEquals("JBC", updated.getValue("google:1").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:2").denominationId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun publishedCatalogDoesNotLabelTheTwoRealFalsePositivesAsJbc() {
        val resources = sequenceOf(java.nio.file.Path.of("resources"), java.nio.file.Path.of("../resources"))
            .first { Files.isRegularFile(it.resolve("catalog/churches.json")) }
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(resources.resolve("catalog/churches.json")))
        val byWebsite = churches.associateBy(ChurchRecord::websiteUrl)

        assertNotEquals("JBC", byWebsite.getValue("https://www.facebook.com/fujijesuslove/").denominationId)
        assertNotEquals("JBC", byWebsite.getValue("https://www.facebook.com/profile.php?id=100064332113625").denominationId)
    }

    private fun officialList(id: String, churches: List<OfficialDenominationChurch>) = OfficialDenominationChurchList(
        denominationId = id,
        denominationName = if (id == "JBC") "日本バプテスト連盟" else "日本基督教団",
        sourceUrl = if (id == "JBC") "https://bapren.jp/church/" else "https://uccj.org/diocese",
        fetchedAt = "2026-07-18T00:00:00Z",
        churches = churches,
    )

    private fun church(
        id: String,
        name: String,
        address: String,
        denominationId: String,
        websiteUrl: String = "https://www.google.com/maps?cid=${id.substringAfter(':')}",
    ) = ChurchRecord(
        id = id,
        googleCid = id.substringAfter(':').takeIf { it.all(Char::isDigit) },
        name = name,
        englishName = "Test Church ${id.substringAfter(':')}",
        denominationId = denominationId,
        address = address,
        location = GeoPoint(35.0, 135.0),
        websiteUrl = websiteUrl,
    )
}
