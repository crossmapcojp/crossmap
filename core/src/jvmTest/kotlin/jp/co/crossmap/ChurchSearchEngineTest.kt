package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class ChurchSearchEngineTest {
    @Test
    fun localizedPastorNamesFindTheChurchInEveryLanguageIndex() {
        val root = Files.createTempDirectory("crossmap-minister-index")
        try {
            val church = ChurchRecord(
                id = "fixture:minister-search",
                name = "東京希望教会",
                englishName = "Tokyo Hope Church",
                localizedNames = listOf(
                    LocalizedName("ja", "東京希望教会"),
                    LocalizedName("en", "Tokyo Hope Church"),
                    LocalizedName("ko", "도쿄 희망 교회"),
                    LocalizedName("pt", "Igreja Esperança de Tóquio"),
                    LocalizedName("id", "Gereja Harapan Tokyo"),
                ),
                address = "東京都新宿区",
                location = GeoPoint(35.69, 139.70),
                websiteUrl = "https://example.com/hope",
                ministers = listOf(
                    ChurchMinister(
                        name = "佐藤 太郎",
                        localizedNames = listOf(
                            LocalizedName("ja", "佐藤 太郎"),
                            LocalizedName("ja", "さとう たろう"),
                            LocalizedName("en", "Tarou Satou"),
                            LocalizedName("ko", "사토 다로"),
                            LocalizedName("pt", "Tarou Satou"),
                            LocalizedName("id", "Tarou Satou"),
                        ),
                        roleId = "pastor",
                        roleName = "牧師",
                        localizedRoleNames = emptyList(),
                    ),
                ),
            )
            val queries = mapOf("ja" to "佐藤 太郎", "en" to "Tarou Satou", "ko" to "사토 다로", "pt" to "Tarou Satou", "id" to "Tarou Satou")
            queries.forEach { (language, query) ->
                val index = root.resolve("index-$language")
                ChurchIndex.build(index.toString().toPath(), listOf(church), languageCode = language)
                val engine = ChurchSearchEngine(index.toString().toPath(), emptyList(), languageCode = language)
                try {
                    val result = engine.search(ChurchSearchRequest(query))
                    assertEquals(1, result.total, "$language query must find the church by pastor name")
                    assertEquals(church.id, result.hits.single().churchId)
                } finally {
                    engine.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rendersSearchStepDurationsAndPercentages() {
        val output = renderSearchTiming(
            linkedMapOf(
                "geoname.resolve" to 20.milliseconds,
                "lucene.collect" to 70.milliseconds,
            ),
            100.milliseconds,
        )

        assertContains(output, "geoname.resolve=20ms (20.0%)")
        assertContains(output, "lucene.collect=70ms (70.0%)")
        assertContains(output, "other=10ms (10.0%)")
        assertContains(output, "total=100ms (100.0%)")
    }

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
                    localizedNames = listOf(
                        LocalizedName("ja", "日本聖公会東京聖アンデレ教会"),
                        LocalizedName("en", "Tokyo Saint Andrew Church"),
                        LocalizedName("ko", "도쿄 세인트 앤드류 교회"),
                        LocalizedName("pt", "Igreja de Santo André de Tóquio"),
                        LocalizedName("id", "Gereja Santo Andreas Tokyo"),
                    ),
                    localizedDenominationNames = listOf(
                        LocalizedName("ja", "日本聖公会"),
                        LocalizedName("en", "Anglican Church in Japan"),
                        LocalizedName("ko", "일본성공회"),
                        LocalizedName("pt", "Igreja Anglicana no Japão"),
                        LocalizedName("id", "Gereja Anglikan di Jepang"),
                    ),
                    titleLanguages = listOf("ja", "en"),
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
                ChurchRecord(
                    id = "google:6646597370070891755",
                    name = "東京バプテスト教会",
                    englishName = "Tokyo Baptist Church",
                    address = "〒150-0035 東京都渋谷区鉢山町９−２",
                    location = GeoPoint(35.6506, 139.6967),
                    websiteUrl = "https://tokyobaptist.org/",
                ),
                ChurchRecord(
                    id = "google:13422733672291385493",
                    name = "小禄バプテスト教会",
                    englishName = "Oroku Baptist Church",
                    address = "〒901-0145 沖縄県那覇市高良２丁目４−１６",
                    location = GeoPoint(26.1858563, 127.6646236),
                    websiteUrl = "https://orokubap.localinfo.jp/",
                ),
            )
            val index = root.resolve("index")
            val geonames = listOf(
                GeoName("13", "東京都", listOf("東京"), GeoNameType.PREFECTURE, "13", GeoPoint(35.68, 139.69), 100.0),
                GeoName("13103", "港区", emptyList(), GeoNameType.WARD, "13", GeoPoint(35.658, 139.751), 15.0),
                GeoName("01", "北海道", emptyList(), GeoNameType.PREFECTURE, "01", GeoPoint(43.1, 141.3), 500.0),
            )
            ChurchIndex.build(index.toString().toPath(), churches, geonames = geonames)
            val engine = ChurchSearchEngine(index.toString().toPath(), geonames, "fixture-v1")
            fun localizedEngine(language: String): ChurchSearchEngine {
                val localizedIndex = root.resolve("index-$language")
                val translatedGeoNames = mapOf(
                    "google:2225537460932230335" to when (language) {
                        "ja" -> listOf("港区")
                        "en" -> listOf("Minato City")
                        "ko" -> listOf("미나토구")
                        "pt" -> listOf("Distrito de Minato")
                        "id" -> listOf("Distrik Minato")
                        else -> emptyList()
                    },
                )
                ChurchIndex.build(localizedIndex.toString().toPath(), churches, language, translatedGeoNames, geonames)
                return ChurchSearchEngine(
                    localizedIndex.toString().toPath(),
                    geonames,
                    "fixture-v1",
                    languageCode = language,
                )
            }

            val name = engine.search(ChurchSearchRequest("東京 教会"))
            assertEquals(2, name.total)
            assertEquals("東京 教会", name.textQuery)
            assertTrue(name.hits.any { it.churchId == "google:2225537460932230335" })

            val body = engine.search(ChurchSearchRequest("ボーイスカウト"))
            assertEquals("google:2225537460932230335", body.hits.single().churchId)
            assertTrue(body.hits.single().matchedPages.single().snippet.contains("ボーイスカウト"))

            val koreanAlias = localizedEngine("ko").search(ChurchSearchRequest("도쿄 세인트 앤드류 교회"))
            assertEquals("google:2225537460932230335", koreanAlias.hits.single().churchId)
            assertTrue(koreanAlias.hits.single().localizedNames.any { it.languageCode == "ko" })
            val koreanDenomination = localizedEngine("ko").search(ChurchSearchRequest("일본성공회"))
            assertEquals("google:2225537460932230335", koreanDenomination.hits.single().churchId)
            val koreanAddressGeoName = localizedEngine("ko").search(ChurchSearchRequest("미나토구"))
            assertEquals("google:2225537460932230335", koreanAddressGeoName.hits.single().churchId)

            val englishInflection = localizedEngine("en").search(ChurchSearchRequest("Tokyo churches"))
            assertTrue(englishInflection.hits.any { it.churchId == "google:2225537460932230335" })
            assertEquals(
                "google:2225537460932230335",
                localizedEngine("en").search(ChurchSearchRequest("Anglican Church in Japan")).hits.single().churchId,
            )
            assertEquals(
                "google:2225537460932230335",
                localizedEngine("en").search(ChurchSearchRequest("Minato City")).hits.single().churchId,
            )
            val sourceLanguageFiltered = localizedEngine("en").search(
                ChurchSearchRequest("Tokyo churches", titleLanguages = listOf("en"))
            )
            assertEquals(listOf("google:2225537460932230335"), sourceLanguageFiltered.hits.map { it.churchId })

            val portugueseInflection = localizedEngine("pt").search(ChurchSearchRequest("igrejas santo andré"))
            assertEquals("google:2225537460932230335", portugueseInflection.hits.single().churchId)
            assertEquals(
                "google:2225537460932230335",
                localizedEngine("pt").search(ChurchSearchRequest("Igreja Anglicana no Japão")).hits.single().churchId,
            )

            val indonesianName = localizedEngine("id").search(ChurchSearchRequest("Gereja Andreas Tokyo"))
            assertEquals("google:2225537460932230335", indonesianName.hits.single().churchId)
            assertEquals(
                "google:2225537460932230335",
                localizedEngine("id").search(ChurchSearchRequest("Gereja Anglikan di Jepang")).hits.single().churchId,
            )

            val qualifiedJapaneseName = engine.search(ChurchSearchRequest("東京バプテスト教会"))
            assertEquals("google:6646597370070891755", qualifiedJapaneseName.hits.single().churchId)

            val nearby = engine.search(ChurchSearchRequest("教会", userLocation = GeoPoint(35.6601808, 139.743601)))
            assertEquals(
                setOf("google:2225537460932230335", "google:6646597370070891755"),
                nearby.hits.map { it.churchId }.toSet(),
            )
            assertEquals(GeoNameType.DEVICE, nearby.resolvedLocations.single().type)

            val detail = assertNotNull(engine.church("google:2225537460932230335"))
            assertEquals(SocialPlatform.YOUTUBE, detail.socialProfiles.single().platform)
            assertEquals("도쿄 세인트 앤드류 교회", detail.localizedNames.single { it.languageCode == "ko" }.name)
            assertEquals("일본성공회", detail.localizedDenominationNames.single { it.languageCode == "ko" }.name)

            val encoded = Json.encodeToString(name)
            assertTrue(encoded.contains("fixture-v1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ambiguousMunicipalityUsesNearestUserLocationAndDoesNotCreateAUnion() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName("13206", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "13", GeoPoint(35.6, 139.4), 20.0),
                GeoName("34208", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "34", GeoPoint(34.5, 133.2), 20.0),
            )
        )
        val unresolved = resolver.resolve("府中 教会")
        val nearTokyo = resolver.resolve("府中 教会", userLocation = GeoPoint(35.68, 139.69))
        val nearHiroshima = resolver.resolve("府中 教会", userLocation = GeoPoint(34.4, 132.45))

        assertTrue(unresolved.locations.isEmpty())
        assertEquals("13206", nearTokyo.locations.single().code)
        assertEquals("34208", nearHiroshima.locations.single().code)
    }

    @Test
    fun ambiguousChuoWardUsesBrowserOrAppLocationToChooseTokyoOrFukuoka() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName("13102", "中央区", type = GeoNameType.WARD, prefectureCode = "13", center = GeoPoint(35.670, 139.772), coveringRadiusKm = 8.0),
                GeoName("40133", "中央区", type = GeoNameType.WARD, prefectureCode = "40", center = GeoPoint(33.589, 130.392), coveringRadiusKm = 8.0),
            ),
        )

        assertTrue(resolver.resolve("中央区").locations.isEmpty())
        assertEquals(
            "13102",
            resolver.resolve("中央区", userLocation = GeoPoint(35.681, 139.767)).locations.single().code,
        )
        assertEquals(
            "40133",
            resolver.resolve("中央区", userLocation = GeoPoint(33.590, 130.401)).locations.single().code,
        )
    }

    @Test
    fun geonameOnlyChuoWardQueryFiltersToTheOneWardSelectedByUserLocation() {
        val root = Files.createTempDirectory("crossmap-chuo-ward")
        try {
            val churches = listOf(
                ChurchRecord(
                    "fixture:tokyo-chuo", name = "日本橋教会", englishName = "Nihonbashi Church",
                    address = "〒103-0027 東京都中央区日本橋１丁目", location = GeoPoint(35.682, 139.774),
                    websiteUrl = "https://example.com/nihonbashi-church",
                ),
                ChurchRecord(
                    "fixture:fukuoka-chuo", name = "福岡城南教会", englishName = "Fukuoka Jonan Church",
                    address = "〒810-0044 福岡県福岡市中央区六本松１丁目", location = GeoPoint(33.578, 130.378),
                    websiteUrl = "https://example.com/fukuoka-jonan-church",
                ),
            )
            val geonames = listOf(
                GeoName("13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 80.0),
                GeoName("40", "福岡県", type = GeoNameType.PREFECTURE, prefectureCode = "40", center = GeoPoint(33.60, 130.42), coveringRadiusKm = 80.0),
                GeoName("40130", "福岡市", type = GeoNameType.MUNICIPALITY, prefectureCode = "40", center = GeoPoint(33.59, 130.40), coveringRadiusKm = 30.0),
                GeoName("13102", "中央区", type = GeoNameType.WARD, prefectureCode = "13", center = GeoPoint(35.670, 139.772), coveringRadiusKm = 8.0),
                GeoName("40133", "中央区", type = GeoNameType.WARD, prefectureCode = "40", center = GeoPoint(33.589, 130.392), coveringRadiusKm = 8.0),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), churches, geonames = geonames)
            val engine = ChurchSearchEngine(index.toString().toPath(), geonames)

            val tokyo = engine.search(ChurchSearchRequest("中央区", userLocation = GeoPoint(35.681, 139.767)))
            val fukuoka = engine.search(ChurchSearchRequest("中央区", userLocation = GeoPoint(33.590, 130.401)))

            assertEquals(listOf("fixture:tokyo-chuo"), tokyo.hits.map { it.churchId })
            assertEquals(listOf("fixture:fukuoka-chuo"), fukuoka.hits.map { it.churchId })
            assertContains(engine.explainQuery(ChurchSearchRequest("中央区", userLocation = GeoPoint(33.590, 130.401))), "MATCH_ALL_GEONAME_ONLY")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bareCapitalNamePrefersCityButExplicitPrefectureSuffixWins() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName(
                    "40", "福岡県", aliases = listOf("福岡"), type = GeoNameType.PREFECTURE,
                    prefectureCode = "40", center = GeoPoint(33.60, 130.42), coveringRadiusKm = 80.0,
                    translations = mapOf("en" to "Fukuoka"),
                ),
                GeoName(
                    "401307", "福岡市", aliases = listOf("福岡"), type = GeoNameType.MUNICIPALITY,
                    prefectureCode = "40", center = GeoPoint(33.59, 130.40), coveringRadiusKm = 30.0,
                    translations = mapOf("en" to "Fukuoka"),
                ),
            )
        )

        assertEquals("401307", resolver.resolve("福岡 教会").locations.single().code)
        assertEquals("40", resolver.resolve("福岡県 教会").locations.single().code)
        assertEquals("40", resolver.resolve("Fukuoka-ken church", language = "en").locations.single().code)
    }

    @Test
    fun uniquePrefectureAndUniqueCityEachResolveToOneEntity() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName("14", "神奈川県", listOf("神奈川"), GeoNameType.PREFECTURE, "14", GeoPoint(35.45, 139.64), 80.0),
                GeoName("141003", "横浜市", listOf("横浜"), GeoNameType.MUNICIPALITY, "14", GeoPoint(35.44, 139.64), 30.0),
            )
        )

        assertEquals("14", resolver.resolve("神奈川 教会").locations.single().code)
        assertEquals("141003", resolver.resolve("横浜 教会").locations.single().code)
    }

    @Test
    fun intendedGeonameFunctionUsesNearestCenterWithStableCodeTieBreak() {
        val candidates = listOf(
            GeoName("34208", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "34", GeoPoint(34.5, 133.2), 20.0),
            GeoName("13206", "府中市", listOf("府中"), GeoNameType.MUNICIPALITY, "13", GeoPoint(35.6, 139.4), 20.0),
        )

        assertEquals(
            "13206",
            detectIntendedGeonameFromUserLocation(candidates, GeoPoint(35.68, 139.69))?.code,
        )
    }

    @Test
    fun prefectureAliasWinsOverAmbiguousSingleCharacterMunicipalityAlias() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName("13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0),
                GeoName("473031", "東村", listOf("東"), GeoNameType.MUNICIPALITY, "47", GeoPoint(26.63, 128.16), 50.0),
            )
        )

        val resolved = resolver.resolve("東京バプテスト教会")

        assertEquals("バプテスト教会", resolved.textQuery)
        assertEquals(listOf("13"), resolved.locations.map { it.code })
    }

    @Test
    fun englishQueryDetectsGeonameViaTranslations() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName(
                    "13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13",
                    center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0,
                    translations = mapOf("en" to "Tokyo", "ko" to "도쿄"),
                ),
                GeoName(
                    "14", "神奈川県", type = GeoNameType.PREFECTURE, prefectureCode = "14",
                    center = GeoPoint(35.45, 139.64), coveringRadiusKm = 80.0,
                    translations = mapOf("en" to "Kanagawa"),
                ),
            )
        )

        val resolved = resolver.resolve("Tokyo Baptist Church", language = "en")

        assertEquals("baptist church", resolved.textQuery)
        assertEquals(listOf("13"), resolved.locations.map { it.code })
        assertEquals("東京都", resolved.locations.single().name)
    }

    @Test
    fun japaneseQueryStillResolvesViaNameWithoutTranslations() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName(
                    "13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13",
                    center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0,
                    translations = mapOf("en" to "Tokyo"),
                ),
            )
        )

        val resolved = resolver.resolve("東京バプテスト教会")

        assertEquals("バプテスト教会", resolved.textQuery)
        assertEquals(listOf("13"), resolved.locations.map { it.code })
    }

    @Test
    fun nonJapaneseLanguageDoesNotMatchJapaneseTranslations() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName(
                    "13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13",
                    center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0,
                    translations = mapOf("en" to "Tokyo"),
                ),
            )
        )

        val resolved = resolver.resolve("Tokyo Baptist Church", language = "ko")

        assertEquals("tokyo baptist church", resolved.textQuery)
        assertTrue(resolved.locations.isEmpty())
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
    fun prefectureAndMunicipalityQueriesUseTheirExactAddressEntityCodes() {
        val root = Files.createTempDirectory("crossmap-prefecture")
        try {
            val churches = listOf(
                ChurchRecord("google:2225537460932230335", name = "日本聖公会東京聖アンデレ教会", englishName = "Tokyo St Andrew's Church", address = "〒105-0011 東京都港区芝公園３丁目６−１８", location = GeoPoint(35.6601808, 139.743601), websiteUrl = "http://www.st-andrew-tokyo.com/"),
                ChurchRecord("google:16863838991523575183", name = "日本聖公会小笠原聖ジョージ教会", englishName = "Ogasawara St George's Church", address = "〒100-2101 東京都小笠原村父島西町35,", location = GeoPoint(27.0933975, 142.1910068), websiteUrl = "http://www.nskk.org/tokyo/church/ogasawara/ogasawara.htm"),
                ChurchRecord("google:12637710057937127475", name = "日本聖公会大阪聖パウロ教会", englishName = "Osaka St Paul's Church", address = "〒530-0013 大阪府大阪市北区茶屋町２−３０", location = GeoPoint(34.7061457, 135.4999131), websiteUrl = "http://www.nskk.org/osaka/church/paul/"),
            )
            val geonames = listOf(
                GeoName("13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 1_000.0),
                GeoName("13104", "新宿区", type = GeoNameType.WARD, prefectureCode = "13", center = GeoPoint(35.69, 139.70), coveringRadiusKm = 15.0),
                GeoName(
                    "134210",
                    "小笠原村",
                    type = GeoNameType.MUNICIPALITY,
                    prefectureCode = "13",
                    center = GeoPoint(27.09, 142.19),
                    coveringRadiusKm = 30.0,
                    includeInPrefectureSearch = false,
                ),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), churches, geonames = geonames)

            val engine = ChurchSearchEngine(index.toString().toPath(), geonames)
            val tokyo = engine.search(ChurchSearchRequest("東京都"))
            val ogasawara = engine.search(ChurchSearchRequest("小笠原村"))

            assertContains(engine.explainQuery(ChurchSearchRequest("東京都")), "tier.3.textMode=MATCH_ALL_GEONAME_ONLY")
            assertEquals(
                setOf("google:2225537460932230335", "google:16863838991523575183"),
                tokyo.hits.map { it.churchId }.toSet(),
            )
            assertEquals(listOf("google:16863838991523575183"), ogasawara.hits.map { it.churchId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun englishQueryDetectsGeonameThroughFullSearchPipeline() {
        val root = Files.createTempDirectory("crossmap-en-geoname")
        try {
            val churches = listOf(
                ChurchRecord(
                    id = "google:2225537460932230335",
                    name = "東京バプテスト教会",
                    englishName = "Tokyo Baptist Church",
                    address = "〒105-0011 東京都港区芝公園３丁目６−１８",
                    location = GeoPoint(35.6601808, 139.743601),
                    websiteUrl = "http://www.tokyobaptist.org/",
                ),
                ChurchRecord(
                    id = "google:12637710057937127475",
                    name = "大阪バプテスト教会",
                    englishName = "Osaka Baptist Church",
                    address = "〒530-0013 大阪府大阪市北区茶屋町２−３０",
                    location = GeoPoint(34.7061457, 135.4999131),
                    websiteUrl = "http://www.osakabaptist.org/",
                ),
            )
            val index = root.resolve("index")
            ChurchIndex.build(
                index.toString().toPath(), churches,
                languageCode = "en",
                translatedGeoNames = mapOf(
                    "google:2225537460932230335" to listOf("Tokyo"),
                    "google:12637710057937127475" to listOf("Osaka"),
                ),
            )
            val geonames = listOf(
                GeoName(
                    "13", "東京都", type = GeoNameType.PREFECTURE, prefectureCode = "13",
                    center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0,
                    translations = mapOf("en" to "Tokyo"),
                ),
                GeoName(
                    "27", "大阪府", type = GeoNameType.PREFECTURE, prefectureCode = "27",
                    center = GeoPoint(34.69, 135.52), coveringRadiusKm = 80.0,
                    translations = mapOf("en" to "Osaka"),
                ),
            )

            val result = ChurchSearchEngine(index.toString().toPath(), geonames, languageCode = "en")
                .search(ChurchSearchRequest("Tokyo Baptist Church"))

            assertEquals("Tokyo Baptist Church", result.textQuery)
            assertEquals(1, result.resolvedLocations.size)
            assertEquals("13", result.resolvedLocations.single().code)
            assertEquals("東京都", result.resolvedLocations.single().name)
            assertEquals("google:2225537460932230335", result.hits.single().churchId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun exactWholeNameThenAllNameTokensThenFullQueryGeoResultsAreMergedInOrder() {
        val root = Files.createTempDirectory("crossmap-staged-name-search")
        try {
            val churches = listOf(
                ChurchRecord(
                    id = "google:6646597370070891755",
                    name = "東京バプテスト教会",
                    englishName = "Tokyo Baptist Church",
                    address = "〒150-0035 東京都渋谷区鉢山町９−２",
                    location = GeoPoint(35.6506, 139.6967),
                    websiteUrl = "https://tokyobaptist.org/",
                ),
                ChurchRecord(
                    id = "fixture:tokyo-first-baptist",
                    name = "東京第一バプテスト教会",
                    englishName = "Tokyo First Baptist Church",
                    address = "東京都世田谷区",
                    location = GeoPoint(35.6465, 139.6532),
                    websiteUrl = "https://example.com/tokyo-first-baptist",
                ),
                ChurchRecord(
                    id = "fixture:shibuya-baptist",
                    name = "渋谷バプテスト教会",
                    englishName = "Shibuya Baptist Church",
                    address = "東京都渋谷区",
                    location = GeoPoint(35.6580, 139.7016),
                    websiteUrl = "https://example.com/shibuya-baptist",
                ),
                ChurchRecord(
                    id = "fixture:osaka-baptist",
                    name = "大阪バプテスト教会",
                    englishName = "Osaka Baptist Church",
                    address = "大阪府大阪市",
                    location = GeoPoint(34.6937, 135.5023),
                    websiteUrl = "https://example.com/osaka-baptist",
                ),
            )
            val geonames = listOf(
                GeoName(
                    "13", "東京都", aliases = listOf("東京"), type = GeoNameType.PREFECTURE,
                    prefectureCode = "13", center = GeoPoint(35.68, 139.69), coveringRadiusKm = 100.0,
                    translations = mapOf("en" to "Tokyo"),
                ),
            )
            fun engine(language: String): ChurchSearchEngine {
                val index = root.resolve("index-$language")
                ChurchIndex.build(
                    index.toString().toPath(),
                    churches,
                    language,
                    translatedGeoNames = mapOf(
                        "google:6646597370070891755" to listOf(if (language == "ja") "東京" else "Tokyo"),
                        "fixture:tokyo-first-baptist" to listOf(if (language == "ja") "東京" else "Tokyo"),
                        "fixture:shibuya-baptist" to listOf(if (language == "ja") "東京" else "Tokyo"),
                        "fixture:osaka-baptist" to listOf(if (language == "ja") "大阪" else "Osaka"),
                    ),
                    geonames = geonames,
                )
                return ChurchSearchEngine(index.toString().toPath(), geonames, languageCode = language)
            }

            val englishEngine = engine("en")
            val englishPlan = englishEngine.explainQuery(ChurchSearchRequest("Tokyo Baptist Church"))
            assertContains(englishPlan, "input.language=en analyzer=EnglishAnalyzer")
            assertContains(englishPlan, "analysis.tokens=[tokyo, baptist, church] operator=AND")
            assertContains(englishPlan, "analysis.locations=[tokyo -> 東京都(PREFECTURE, code=13")
            assertContains(englishPlan, "tier.1.type=EXACT_NAME_OR_READING boost=1000000.0")
            assertContains(englishPlan, "term=tokyo baptist church geoFilter=false")
            assertContains(englishPlan, "tier.2.type=ALL_NAME_TOKENS boost=1000.0 enabled=true")
            assertContains(englishPlan, "analysis.geonameSelection=unique-prefecture")
            assertContains(englishPlan, "tier.3.geoFilter=true filter=NAMED_ADDRESS_CODE field=address_geoname_code code=13 radiusFilter=false")
            assertContains(englishPlan, "merge=SHOULD(tier.1,tier.2,tier.3) minimumShouldMatch=1 deduplicate=true")

            val english = englishEngine.search(ChurchSearchRequest("Tokyo Baptist Church"))
            assertEquals(
                listOf(
                    "google:6646597370070891755",
                    "fixture:tokyo-first-baptist",
                    "fixture:shibuya-baptist",
                ),
                english.hits.map { it.churchId },
            )
            assertEquals("Tokyo Baptist Church", english.textQuery)
            assertEquals(3, english.total)

            val japaneseEngine = engine("ja")
            val japanesePlan = japaneseEngine.explainQuery(ChurchSearchRequest("東京バプテスト教会"))
            assertContains(japanesePlan, "input.language=ja analyzer=JapaneseAnalyzer")
            assertContains(japanesePlan, "analysis.tokens=[東京, バプテスト, 教会] operator=AND")
            assertContains(japanesePlan, "analysis.locations=[東京 -> 東京都(PREFECTURE, code=13")
            assertContains(japanesePlan, "tier.3.geoFilter=true filter=NAMED_ADDRESS_CODE field=address_geoname_code code=13 radiusFilter=false")

            val japanese = japaneseEngine.search(ChurchSearchRequest("東京バプテスト教会"))
            assertEquals(
                listOf(
                    "google:6646597370070891755",
                    "fixture:tokyo-first-baptist",
                    "fixture:shibuya-baptist",
                ),
                japanese.hits.map { it.churchId },
            )
            assertEquals("東京バプテスト教会", japanese.textQuery)

            val genericPlan = englishEngine.explainQuery(
                ChurchSearchRequest("Church", userLocation = GeoPoint(35.6506, 139.6967)),
            )
            assertContains(genericPlan, "tier.2.type=ALL_NAME_TOKENS boost=1000.0 enabled=false")
            assertContains(genericPlan, "reason=generic-or-geoname-only-query")
            assertContains(genericPlan, "analysis.locations=[device -> Current location(DEVICE")

            val secondPage = engine("en").search(
                ChurchSearchRequest("Tokyo Baptist Church", offset = 2, limit = 1),
            )
            assertEquals(3, secondPage.total)
            assertEquals(listOf("fixture:shibuya-baptist"), secondPage.hits.map { it.churchId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun romanizedYokohamaChoWithHyphenOrAttachedSuffixIsExplicit() {
        val resolver = GeoNameResolver(
            listOf(
                GeoName(
                    code = "02406",
                    name = "横浜町",
                    type = GeoNameType.MUNICIPALITY,
                    prefectureCode = "02",
                    center = GeoPoint(41.083, 141.247),
                    coveringRadiusKm = 20.0,
                    translations = mapOf("en" to "Yokohama"),
                ),
            ),
        )

        listOf("Yokohama-cho Baptist Church", "Yokohamacho Baptist Church").forEach { query ->
            val resolved = resolver.resolve(query, language = "en")
            assertEquals("02406", resolved.locations.single().code, query)
            assertTrue(resolved.explicitAdministrativeName, query)
        }
    }

    @Test
    fun explicitYokohamaTownNeverFallsBackToYokohamaCityNameMatches() {
        val root = Files.createTempDirectory("crossmap-yokohama-town-index")
        try {
            val yokohamaTown = GeoName(
                code = "02406",
                name = "横浜町",
                aliases = listOf("横浜"),
                type = GeoNameType.MUNICIPALITY,
                prefectureCode = "02",
                center = GeoPoint(41.083, 141.247),
                coveringRadiusKm = 20.0,
                translations = mapOf("en" to "Yokohama"),
            )
            val yokohamaCity = GeoName(
                code = "14100",
                name = "横浜市",
                aliases = listOf("横浜"),
                type = GeoNameType.MUNICIPALITY,
                prefectureCode = "14",
                center = GeoPoint(35.444, 139.638),
                coveringRadiusKm = 35.0,
                translations = mapOf("en" to "Yokohama"),
            )
            val geonames = listOf(yokohamaTown, yokohamaCity)
            val church = ChurchRecord(
                id = "fixture:yokohama-baptist",
                name = "横浜バプテスト教会",
                englishName = "Yokohama Baptist Church",
                address = "神奈川県横浜市中区",
                location = GeoPoint(35.444, 139.638),
                websiteUrl = "https://example.com/yokohama-baptist",
            )
            val index = root.resolve("index")
            ChurchIndex.build(
                index.toString().toPath(),
                churches = listOf(church),
                languageCode = "ja",
                geonames = geonames,
                normalizedAddresses = mapOf(
                    church.id to JapaneseAddress(
                        original = church.address,
                        normalized = church.address,
                        prefecture = "神奈川県",
                        prefectureCode = "14",
                        municipality = "横浜市",
                        municipalityCode = "14100",
                    ),
                ),
            )
            val resolved = GeoNameResolver(geonames).resolve("横浜町 バプテスト")
            assertEquals("02406", resolved.locations.single().code)
            assertEquals("バプテスト", resolved.textQuery)
            assertTrue(resolved.explicitAdministrativeName)

            val engine = ChurchSearchEngine(index.toString().toPath(), geonames, languageCode = "ja")
            listOf(
                "横浜町",
                "横浜町 教会",
                "横浜町 バプテスト",
                "Yokohama-cho",
                "Yokohama-cho Baptist Church",
                "Yokohamacho",
                "Yokohamacho Baptist Church",
            ).forEach { query ->
                val result = engine.search(ChurchSearchRequest(query))
                assertEquals(0, result.total, "Explicit Aomori Yokohama Town query must not match Yokohama City: $query")
                assertTrue(result.hits.isEmpty(), "Expected no Yokohama Town church for: $query")
            }
            val plan = engine.explainQuery(ChurchSearchRequest("横浜町 バプテスト"))
            assertContains(plan, "analysis.explicitAdministrativeName=true")
            assertContains(plan, "tier.1.type=EXACT_NAME_OR_READING boost=1000000.0")
            assertContains(plan, "term=横浜町バプテスト geoFilter=true")
            assertContains(plan, "tier.2.type=ALL_NAME_TOKENS boost=1000.0 enabled=true")
            engine.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun denominationSearchNearIzuUsesDeviceRadiusAndOrdersNearbyChurchesByDistance() {
        val root = Files.createTempDirectory("crossmap-izu-device-search")
        try {
            fun uccj(id: String, name: String, englishName: String, address: String, latitude: Double, longitude: Double) =
                ChurchRecord(
                    id = id,
                    name = name,
                    englishName = englishName,
                    localizedDenominationNames = listOf(
                        LocalizedName("ja", "日本基督教団"),
                        LocalizedName("ja", "日本キリスト教団"),
                    ),
                    denominationId = "UCCJ",
                    address = address,
                    location = GeoPoint(latitude, longitude),
                    websiteUrl = "https://example.invalid/$id",
                )

            val churches = listOf(
                uccj("shuzenji", "日本キリスト教団修善寺教会", "UCCJ Shuzenji Church", "静岡県伊豆市柏久保", 34.9779135, 138.9509258),
                uccj("inatori", "日本基督教団稲取教会", "UCCJ Inatori Church", "静岡県賀茂郡河津町見高", 34.7557916, 139.0175061),
                uccj("izu-kogen", "日本キリスト教団伊豆高原教会", "UCCJ Izu Kogen Church", "静岡県伊東市八幡野", 34.8754874, 139.1118797),
                uccj("izu-nagaoka", "日本キリスト教団伊豆長岡教会", "UCCJ Izu Nagaoka Church", "静岡県伊豆の国市長岡", 35.0324776, 138.9344271),
                uccj("usami", "日本キリスト教団宇佐美教会", "UCCJ Usami Church", "静岡県伊東市宇佐美", 35.008616, 139.08603),
                uccj("numazu", "日本キリスト教団沼津教会", "UCCJ Numazu Church", "静岡県沼津市末広町", 35.0969787, 138.8548702),
                uccj("mishima", "日本キリスト教団 三島教会", "UCCJ Mishima Church", "静岡県三島市中田町", 35.1156951, 138.9180552),
                uccj("atami", "熱海教会", "Atami Church", "静岡県熱海市上宿町", 35.0980624, 139.0688652),
                uccj("kobe", "日本基督教団神戸イエス団教会", "UCCJ Kobe Jesus Dan Church", "兵庫県神戸市中央区", 34.697641, 135.203888),
            )
            val geonames = listOf(
                GeoName(
                    code = "222224",
                    name = "伊豆市",
                    aliases = listOf("伊豆"),
                    type = GeoNameType.MUNICIPALITY,
                    prefectureCode = "22",
                    center = GeoPoint(34.976591, 138.946715),
                    coveringRadiusKm = 30.0,
                    translations = mapOf("en" to "Izu City"),
                ),
            )
            val index = root.resolve("index")
            ChurchIndex.build(index.toString().toPath(), churches, geonames = geonames)
            val engine = ChurchSearchEngine(index.toString().toPath(), geonames, languageCode = "ja")

            val result = engine.search(
                ChurchSearchRequest(
                    query = "日本基督教団",
                    limit = 20,
                    userLocation = GeoPoint(34.87544654121299, 138.92825706221615),
                )
            )

            assertEquals("伊豆市", result.resolvedLocations.single().name)
            assertEquals(GeoNameType.DEVICE, result.resolvedLocations.single().type)
            assertEquals("shuzenji", result.hits.first().churchId)
            assertEquals(
                listOf("shuzenji", "inatori", "izu-kogen", "izu-nagaoka", "usami", "numazu", "mishima", "atami"),
                result.hits.map(ChurchSearchHit::churchId),
            )
            assertTrue(result.hits.zipWithNext().all { (first, second) ->
                requireNotNull(first.distanceKm) <= requireNotNull(second.distanceKm)
            })
            assertTrue(result.hits.none { it.churchId == "kobe" })
            val devicePlan = engine.explainQuery(
                ChurchSearchRequest("日本基督教団", userLocation = GeoPoint(34.875, 138.928))
            )
            assertContains(devicePlan, "filter=DEVICE_LAT_LON_DISTANCE field=location")
            assertContains(devicePlan, "radiusKm=50.0")
            val namedPlan = engine.explainQuery(ChurchSearchRequest("伊豆市"))
            assertContains(namedPlan, "filter=NAMED_ADDRESS_CODE field=address_geoname_code code=222224 radiusFilter=false")
            assertTrue(namedPlan.contains("DEVICE_LAT_LON_DISTANCE").not())
            engine.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
