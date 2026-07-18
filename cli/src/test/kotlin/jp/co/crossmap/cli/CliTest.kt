package jp.co.crossmap.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import jp.co.crossmap.ChurchIndex
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.ChurchSearchResponse
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.GeoName
import jp.co.crossmap.GeoNameType
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class CliTest {
    private val fixture by lazy(::buildRealChurchFixture)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exactRealChurchNameRanksFirstAndJsonRoundTrips() {
        val result = search("岡山バプテスト教会")
        assertEquals(0, result.statusCode)
        val response = json.decodeFromString<ChurchSearchResponse>(result.stdout)
        assertEquals("google:906297735827744432", response.hits.first().churchId)
        assertEquals("岡山バプテスト教会", response.hits.first().name)
    }

    @Test
    fun prefectureAndChurchTermsBecomeGeoAndTextParts() {
        val response = response("東京都 聖アンデレ")
        assertEquals("東京都 聖アンデレ", response.textQuery)
        assertEquals("東京都", response.resolvedLocations.single().name)
        assertEquals("google:2225537460932230335", response.hits.first().churchId)
    }

    @Test
    fun municipalityOnlyQueryFindsChurchInsideItsRadius() {
        val response = response("岡山市")
        assertEquals("岡山市", response.textQuery)
        assertEquals(listOf("google:906297735827744432"), response.hits.map { it.churchId })
    }

    @Test
    fun crawledWebsiteBodyIsSearchableAndReturnsSnippet() {
        val response = response("ボーイスカウト")
        assertEquals("google:2225537460932230335", response.hits.single().churchId)
        assertTrue(response.hits.single().matchedPages.single().snippet.contains("ボーイスカウト"))
    }

    @Test
    fun addressFragmentFindsTheRealChurch() {
        val response = response("芝公園")
        assertEquals("google:2225537460932230335", response.hits.first().churchId)
    }

    @Test
    fun deviceCoordinatesFilterWhenQueryHasNoGeoname() {
        val result = search(
            "教会",
            "--latitude", "35.6601808",
            "--longitude", "139.743601",
            "--radius-km", "3",
        )
        assertEquals(0, result.statusCode)
        val response = json.decodeFromString<ChurchSearchResponse>(result.stdout)
        assertEquals(GeoNameType.DEVICE, response.resolvedLocations.single().type)
        assertTrue(response.hits.isNotEmpty())
        assertTrue(response.hits.all { (it.distanceKm ?: Double.MAX_VALUE) <= 3.0 })
    }

    @Test
    fun queryGeonameOverridesProvidedDeviceCoordinates() {
        val result = search(
            "岡山市 バプテスト",
            "--latitude", "43.0854662",
            "--longitude", "141.3545852",
            "--radius-km", "10",
        )
        val response = json.decodeFromString<ChurchSearchResponse>(result.stdout)
        assertEquals("岡山市", response.resolvedLocations.single().name)
        assertEquals("google:906297735827744432", response.hits.first().churchId)
    }

    @Test
    fun paginationIsStableAndDoesNotRepeatTheFirstHit() {
        val first = response("日本聖公会", "--limit", "1")
        val second = response("日本聖公会", "--limit", "1", "--offset", "1")
        assertEquals(1, first.hits.size)
        assertEquals(1, second.hits.size)
        assertFalse(first.hits.single().churchId == second.hits.single().churchId)
        assertEquals(first.total, second.total)
    }

    @Test
    fun prefectureOnlyQueryReturnsOnlyChurchesInThatPrefecture() {
        val response = response("東京都")
        assertEquals(setOf("東京都港区", "東京都千代田区", "東京都小笠原村", "東京都府中市"), response.hits.map {
            it.address.substringAfter("〒").substringAfter(" ").let { address ->
                when {
                    address.startsWith("東京都港区") -> "東京都港区"
                    address.startsWith("東京都千代田区") -> "東京都千代田区"
                    address.startsWith("東京都小笠原村") -> "東京都小笠原村"
                    address.startsWith("東京都府中市") -> "東京都府中市"
                    else -> address
                }
            }
        }.toSet())
        assertEquals(4, response.total)
    }

    @Test
    fun cityAliasAndChurchTermAreCombined() {
        val response = response("横浜 聖公会")
        assertEquals("横浜", response.resolvedLocations.single().matchedText)
        assertEquals("google:12083726217471771398", response.hits.first().churchId)
    }

    @Test
    fun ambiguousCityNameSearchesEveryMatchingMunicipality() {
        val response = response("府中市")
        assertEquals(setOf("13206", "34208"), response.resolvedLocations.map { it.code }.toSet())
        assertEquals(
            setOf("google:11669795733969339645", "google:3344590218577063144"),
            response.hits.map { it.churchId }.toSet(),
        )
    }

    @Test
    fun textTermsDisambiguateChurchesAcrossSameNamedCities() {
        val response = response("府中市 バプテスト")
        assertEquals("google:11669795733969339645", response.hits.single().churchId)
    }

    @Test
    fun denominationMentionOnOfficialWebsiteIsSearchable() {
        val response = response("日本バプテスト連盟")
        assertEquals("google:11669795733969339645", response.hits.first().churchId)
        assertTrue(response.hits.any { it.churchId == "google:906297735827744432" })
    }

    @Test
    fun distinctivePartialNameFindsCatholicChurch() {
        val response = response("イグナチオ")
        assertEquals("google:12966940111826391654", response.hits.single().churchId)
    }

    @Test
    fun queryParserTreatsUserPunctuationAsTextRatherThanLuceneSyntax() {
        val response = response("岡山+バプテスト")
        assertEquals("google:906297735827744432", response.hits.first().churchId)
    }

    @Test
    fun prettyJsonRemainsCanonicalDecodableJson() {
        val result = rootCommand().test(command("札幌 聖ミカエル", jsonOutput = true) + "--pretty")
        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.lines().size > 2)
        assertEquals("google:8462120116697061819", json.decodeFromString<ChurchSearchResponse>(result.stdout).hits.single().churchId)
    }

    @Test
    fun offsetPastLastHitKeepsTotalAndReturnsEmptyPage() {
        val response = response("日本聖公会", "--offset", "99")
        assertTrue(response.total >= 4)
        assertTrue(response.hits.isEmpty())
    }

    @Test
    fun impossibleQueryUsesClearHumanNoResultOutput() {
        val result = rootCommand().test(command("存在しない教会名XYZ", jsonOutput = false))
        assertEquals(0, result.statusCode)
        assertEquals("No churches found.", result.stdout.trim())
    }

    @Test
    fun invalidPaginationAndIncompleteCoordinatesAreRejected() {
        val badLimit = rootCommand().test(command("岡山バプテスト", jsonOutput = false) + listOf("--limit", "0"))
        assertTrue(badLimit.statusCode != 0)
        assertTrue(badLimit.stderr.contains("must be between 1 and 100"))

        val missingLongitude = rootCommand().test(command("教会", jsonOutput = false) + listOf("--latitude", "35.0"))
        assertTrue(missingLongitude.statusCode != 0)
        assertTrue(missingLongitude.stderr.contains("must be supplied together"))
    }

    private fun response(query: String, vararg extra: String): ChurchSearchResponse =
        json.decodeFromString(search(query, *extra).stdout)

    private fun search(query: String, vararg extra: String) =
        rootCommand().test(command(query, jsonOutput = true) + extra)

    private fun command(query: String, jsonOutput: Boolean): List<String> = buildList {
        addAll(listOf("church", "search", query, "--index", fixture.index.toString(), "--geonames", fixture.geonames.toString()))
        if (jsonOutput) add("--json")
    }

    private fun buildRealChurchFixture(): Fixture {
        val root = Files.createTempDirectory("cm-real-churches")
        root.toFile().deleteOnExit()
        val churches = listOf(
            ChurchRecord(
                id = "google:2225537460932230335", googleCid = "2225537460932230335",
                name = "日本聖公会東京聖アンデレ教会", denominationId = "ANGLICAN_JP",
                englishName = "Tokyo St Andrew's Church",
                address = "〒105-0011 東京都港区芝公園３丁目６−１８", location = GeoPoint(35.6601808, 139.743601),
                websiteUrl = "http://www.st-andrew-tokyo.com/",
                pages = listOf(CrawledPage("http://www.st-andrew-tokyo.com/", title = "聖アンデレ教会", text = "礼拝スケジュール 子供と祝うユーカリスト ボーイスカウト・ガールスカウト")),
            ),
            ChurchRecord(
                id = "google:906297735827744432", googleCid = "906297735827744432",
                name = "岡山バプテスト教会", denominationId = "JBC",
                englishName = "Okayama Baptist Church",
                address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８", location = GeoPoint(34.6619806, 133.9231824),
                websiteUrl = "http://okayama-baptist.jp/",
                pages = listOf(CrawledPage("http://okayama-baptist.jp/", title = "岡山バプテスト教会", text = "日本バプテスト連盟 集会案内 週報 牧師紹介")),
            ),
            ChurchRecord(
                id = "google:8462120116697061819", name = "日本聖公会札幌聖ミカエル教会", denominationId = "ANGLICAN_JP",
                englishName = "Sapporo St Michael's Church",
                address = "〒065-0019 北海道札幌市東区北１９条東３丁目４−５", location = GeoPoint(43.0854662, 141.3545852),
                websiteUrl = "http://www.sapporo-michael.org/",
            ),
            ChurchRecord(
                id = "google:12083726217471771398", name = "横浜山手聖公会", denominationId = "ANGLICAN_JP",
                englishName = "Yokohama Yamate Anglican Church",
                address = "〒231-0862 神奈川県横浜市中区山手町２３５", location = GeoPoint(35.4380585, 139.6524249),
                websiteUrl = "https://yamate-anglican.jpn.org/",
            ),
            ChurchRecord(
                id = "google:16863838991523575183", name = "日本聖公会小笠原聖ジョージ教会", denominationId = "ANGLICAN_JP",
                englishName = "Ogasawara St George's Church",
                address = "〒100-2101 東京都小笠原村父島西町35,", location = GeoPoint(27.0933975, 142.1910068),
                websiteUrl = "http://www.nskk.org/tokyo/church/ogasawara/ogasawara.htm",
            ),
            ChurchRecord(
                id = "google:12637710057937127475", name = "日本聖公会大阪聖パウロ教会", denominationId = "ANGLICAN_JP",
                englishName = "Osaka St Paul's Church",
                address = "〒530-0013 大阪府大阪市北区茶屋町２−３０", location = GeoPoint(34.7061457, 135.4999131),
                websiteUrl = "http://www.nskk.org/osaka/church/paul/",
            ),
            ChurchRecord(
                id = "google:12966940111826391654", name = "カトリック麹町 聖イグナチオ教会", denominationId = "CATHOLIC_JP",
                englishName = "St Ignatius Church",
                address = "〒102-0083 東京都千代田区麹町６丁目５−１", location = GeoPoint(35.6851661, 139.7312516),
                websiteUrl = "https://www.ignatius.gr.jp/",
            ),
            ChurchRecord(
                id = "google:11669795733969339645", name = "日本バプテスト連盟 府中キリスト教会", denominationId = "JBC",
                englishName = "Fuchu Christ Church",
                address = "〒183-0054 東京都府中市幸町１丁目９−７", location = GeoPoint(35.678658, 139.480426),
                websiteUrl = "https://www.google.com/maps?cid=11669795733969339645",
            ),
            ChurchRecord(
                id = "google:3344590218577063144", name = "日本アライアンス教団府中キリスト教会", denominationId = "JAC",
                englishName = "Fuchu Christ Church",
                address = "〒726-0003 広島県府中市元町５４１", location = GeoPoint(34.575485, 133.236118),
                websiteUrl = "https://www.google.com/maps?cid=3344590218577063144",
            ),
        )
        val index = root.resolve("index")
        ChurchIndex.build(index.toString().toPath(), churches)
        val geonames = root.resolve("geonames.json")
        Files.writeString(
            geonames,
            Json.encodeToString(
                listOf(
                    GeoName("13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 1_000.0),
                    GeoName("13103", "港区", type = GeoNameType.WARD, prefectureCode = "13", center = GeoPoint(35.658, 139.751), coveringRadiusKm = 15.0),
                    GeoName("13421", "小笠原村", type = GeoNameType.MUNICIPALITY, prefectureCode = "13", center = GeoPoint(27.093, 142.191), coveringRadiusKm = 30.0),
                    GeoName("33100", "岡山市", aliases = listOf("岡山"), type = GeoNameType.MUNICIPALITY, prefectureCode = "33", center = GeoPoint(34.655, 133.919), coveringRadiusKm = 25.0),
                    GeoName("01100", "札幌市", aliases = listOf("札幌"), type = GeoNameType.MUNICIPALITY, prefectureCode = "01", center = GeoPoint(43.061, 141.354), coveringRadiusKm = 30.0),
                    GeoName("14100", "横浜市", aliases = listOf("横浜"), type = GeoNameType.MUNICIPALITY, prefectureCode = "14", center = GeoPoint(35.444, 139.638), coveringRadiusKm = 30.0),
                    GeoName("27100", "大阪市", aliases = listOf("大阪"), type = GeoNameType.MUNICIPALITY, prefectureCode = "27", center = GeoPoint(34.694, 135.502), coveringRadiusKm = 30.0),
                    GeoName("13206", "府中市", type = GeoNameType.MUNICIPALITY, prefectureCode = "13", center = GeoPoint(35.668, 139.478), coveringRadiusKm = 15.0),
                    GeoName("34208", "府中市", type = GeoNameType.MUNICIPALITY, prefectureCode = "34", center = GeoPoint(34.568, 133.236), coveringRadiusKm = 15.0),
                )
            ),
        )
        return Fixture(index, geonames)
    }

    private data class Fixture(val index: java.nio.file.Path, val geonames: java.nio.file.Path)
}
