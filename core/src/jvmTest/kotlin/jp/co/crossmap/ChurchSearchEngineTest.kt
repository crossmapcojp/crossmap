package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class ChurchSearchEngineTest {
    @Test
    fun searchesNameWebsiteTextAndLocationAndLoadsDetail() {
        val root = Files.createTempDirectory("crossmap-index")
        try {
            val churches = listOf(
                ChurchRecord(
                    id = "google:2225537460932230335",
                    googleCid = "2225537460932230335",
                    name = "日本聖公会東京聖アンデレ教会",
                    englishName = "Tokyo St Andrew's Church",
                    denominationId = "ANGLICAN_JP",
                    address = "〒105-0011 東京都港区芝公園３丁目６−１８",
                    location = GeoPoint(35.6601808, 139.743601),
                    websiteUrl = "http://www.st-andrew-tokyo.com/",
                    pages = listOf(CrawledPage("http://www.st-andrew-tokyo.com/", title = "聖アンデレ教会", text = "礼拝のご案内 礼拝スケジュール 子供と祝うユーカリスト ボーイスカウト・ガールスカウト")),
                    socialProfiles = listOf(SocialProfile(SocialPlatform.YOUTUBE, "https://www.youtube.com/channel/UC3h1K9shoxL9ejQofV3FunA")),
                ),
                ChurchRecord(
                    id = "google:8462120116697061819",
                    googleCid = "8462120116697061819",
                    name = "日本聖公会札幌聖ミカエル教会",
                    englishName = "Sapporo St Michael's Church",
                    denominationId = "ANGLICAN_JP",
                    address = "〒065-0019 北海道札幌市東区北１９条東３丁目４−５",
                    location = GeoPoint(43.0854662, 141.3545852),
                    websiteUrl = "http://www.sapporo-michael.org/",
                ),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), churches)
            val geonames = listOf(
                GeoName("13", "東京都", emptyList(), GeoNameType.PREFECTURE, "13", GeoPoint(35.68, 139.69), 100.0),
                GeoName("13103", "港区", listOf("東京"), GeoNameType.WARD, "13", GeoPoint(35.658, 139.751), 15.0),
                GeoName("01", "北海道", emptyList(), GeoNameType.PREFECTURE, "01", GeoPoint(43.1, 141.3), 500.0),
            )
            val engine = ChurchSearchEngine(index.toString().toPath(), geonames, "fixture-v1")

            val name = engine.search(ChurchSearchRequest("東京 教会"))
            assertEquals(1, name.total)
            assertEquals("教会", name.textQuery)
            assertEquals("google:2225537460932230335", name.hits.single().churchId)

            val body = engine.search(ChurchSearchRequest("ボーイスカウト"))
            assertEquals("google:2225537460932230335", body.hits.single().churchId)
            assertTrue(body.hits.single().matchedPages.single().snippet.contains("ボーイスカウト"))

            val nearby = engine.search(ChurchSearchRequest("教会", userLocation = GeoPoint(35.6601808, 139.743601)))
            assertEquals(listOf("google:2225537460932230335"), nearby.hits.map { it.churchId })
            assertEquals(GeoNameType.DEVICE, nearby.resolvedLocations.single().type)

            val detail = assertNotNull(engine.church("google:2225537460932230335"))
            assertEquals(SocialPlatform.YOUTUBE, detail.socialProfiles.single().platform)

            val encoded = Json.encodeToString(name)
            assertTrue(encoded.contains("fixture-v1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateMunicipalityAliasesResolveToAUnion() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName("13206", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "13", GeoPoint(35.6, 139.4), 20.0),
                GeoName("34208", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "34", GeoPoint(34.5, 133.2), 20.0),
            )
        )
        val resolved = resolver.resolve("府中 教会")
        assertEquals(setOf("13206", "34208"), resolved.locations.map { it.code }.toSet())
    }

    @Test
    fun storesLargeCrawledPagesWithoutIndexingTheSerializedRecordAsOneTerm() {
        val root = Files.createTempDirectory("crossmap-large-record")
        try {
            val church = ChurchRecord(
                id = "google:906297735827744432",
                name = "岡山バプテスト教会",
                englishName = "Okayama Baptist Church",
                denominationId = "JBC",
                address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
                location = GeoPoint(34.6619806, 133.9231824),
                websiteUrl = "http://okayama-baptist.jp/",
                pages = listOf(CrawledPage("http://okayama-baptist.jp/", text = "岡山バプテスト教会 集会案内 週報 牧師紹介 礼拝 ".repeat(20_000))),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), listOf(church))
            val result = ChurchSearchEngine(index.toString().toPath(), emptyList()).search(ChurchSearchRequest("礼拝"))
            assertEquals("google:906297735827744432", result.hits.single().churchId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun prefectureSearchUsesMunicipalityAreasInsteadOfOneHugeCircle() {
        val root = Files.createTempDirectory("crossmap-prefecture")
        try {
            val churches = listOf(
                ChurchRecord("google:2225537460932230335", name = "日本聖公会東京聖アンデレ教会", englishName = "Tokyo St Andrew's Church", address = "〒105-0011 東京都港区芝公園３丁目６−１８", location = GeoPoint(35.6601808, 139.743601), websiteUrl = "http://www.st-andrew-tokyo.com/"),
                ChurchRecord("google:16863838991523575183", name = "日本聖公会小笠原聖ジョージ教会", englishName = "Ogasawara St George's Church", address = "〒100-2101 東京都小笠原村父島西町35,", location = GeoPoint(27.0933975, 142.1910068), websiteUrl = "http://www.nskk.org/tokyo/church/ogasawara/ogasawara.htm"),
                ChurchRecord("google:12637710057937127475", name = "日本聖公会大阪聖パウロ教会", englishName = "Osaka St Paul's Church", address = "〒530-0013 大阪府大阪市北区茶屋町２−３０", location = GeoPoint(34.7061457, 135.4999131), websiteUrl = "http://www.nskk.org/osaka/church/paul/"),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), churches)
            val geonames = listOf(
                GeoName("13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 1_000.0),
                GeoName("13104", "新宿区", type = GeoNameType.WARD, prefectureCode = "13", center = GeoPoint(35.69, 139.70), coveringRadiusKm = 15.0),
                GeoName("13421", "小笠原村", type = GeoNameType.MUNICIPALITY, prefectureCode = "13", center = GeoPoint(27.09, 142.19), coveringRadiusKm = 30.0),
            )

            val result = ChurchSearchEngine(index.toString().toPath(), geonames).search(ChurchSearchRequest("東京都"))
            assertEquals(setOf("google:2225537460932230335", "google:16863838991523575183"), result.hits.map { it.churchId }.toSet())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
