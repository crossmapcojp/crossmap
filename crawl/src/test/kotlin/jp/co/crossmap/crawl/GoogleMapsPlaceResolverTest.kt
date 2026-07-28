package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.ChurchWebsitePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class GoogleMapsPlaceResolverTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun parsesTheSameGoogleEvidenceFieldsUsedByGmap() {
        val seed = GoogleSavedPlaceCrawl(
            id = "google:14619940621679272361",
            googleCid = "14619940621679272361",
            title = "同盟福音グレースチャペル武豊",
            googleMapsUrl = "https://www.google.com/maps?cid=14619940621679272361",
            sourceLists = listOf("教会"),
        )
        val html = """
            <html><head>
              <meta content="同盟福音グレースチャペル武豊 · 〒470-2303 愛知県知多郡武豊町１丁目 ４７番地" property="og:title">
              <meta content="キリスト教会" property="og:description">
            </head><body>
              https://www.google.com/maps/preview/place/%E3%80%92470-2303+%E6%84%9B%E7%9F%A5%E7%9C%8C%E7%9F%A5%E5%A4%9A%E9%83%A1%E6%AD%A6%E8%B1%8A%E7%94%BA%EF%BC%91%E4%B8%81%E7%9B%AE+%EF%BC%94%EF%BC%97%E7%95%AA%E5%9C%B0+%E5%90%8C%E7%9B%9F%E7%A6%8F%E9%9F%B3%E3%82%B0%E3%83%AC%E3%83%BC%E3%82%B9%E3%83%81%E3%83%A3%E3%83%9A%E3%83%AB%E6%AD%A6%E8%B1%8A/@34.847057,136.9014922,3274a
              /url?q\\u003dhttps://gracechapel.example.jp/\\u0026opi\\u003d79508299
            </body></html>
        """.trimIndent()

        val candidate = GoogleMapsPlaceParser().parse(seed, html, now = "2026-07-14T00:00:00Z")

        assertEquals("同盟福音グレースチャペル武豊", candidate.name)
        assertEquals("〒470-2303 愛知県知多郡武豊町１丁目 ４７番地", candidate.address)
        assertEquals(GeoPoint(34.847057, 136.9014922), candidate.location)
        assertEquals("https://gracechapel.example.jp/", candidate.websiteUrl)
        assertEquals("キリスト教会", candidate.category)
        assertEquals(null, candidate.denominationHint)
    }

    @Test
    fun parsesFetchedModernGoogleMapsHtmlFixture() {
        val resourcePath = Path.of("src/test/resources/googlesavedplaces/9513821837673497869.html")
        val html = if (Files.exists(resourcePath)) {
            Files.readString(resourcePath)
        } else {
            javaClass.getResourceAsStream("/googlesavedplaces/9513821837673497869.html")?.bufferedReader()?.readText()
                ?: error("Test fixture 9513821837673497869.html not found")
        }

        val seed = GoogleSavedPlaceCrawl(
            id = "google:9513821837673497869",
            googleCid = "9513821837673497869",
            title = "清瀬福音自由教会",
            googleMapsUrl = "https://www.google.com/maps?cid=9513821837673497869",
            sourceLists = listOf("教会"),
        )

        val candidate = GoogleMapsPlaceParser().parse(seed, html, now = "2026-07-27T00:00:00Z")

        assertEquals("清瀬福音自由教会", candidate.name)
        assertEquals("〒352-0032 埼玉県新座市新堀２丁目１１−１０", candidate.address)
        assertEquals(GeoPoint(35.7698524, 139.527224), candidate.location)
        assertEquals("http://kiyose-efc.com/", candidate.websiteUrl)
        assertEquals("プロテスタント教会", candidate.category)
    }

    @Test
    fun parsesFetchedGoogleMapsHtmlFixture6971423385760493187() {
        val resourcePath = Path.of("src/test/resources/googlesavedplaces/6971423385760493187.html")
        val html = if (Files.exists(resourcePath)) {
            Files.readString(resourcePath)
        } else {
            javaClass.getResourceAsStream("/googlesavedplaces/6971423385760493187.html")?.bufferedReader()?.readText()
                ?: error("Test fixture 6971423385760493187.html not found")
        }

        val seed = GoogleSavedPlaceCrawl(
            id = "google:6971423385760493187",
            googleCid = "6971423385760493187",
            title = "ASSEMBLEIA DE DEUS BELÉM ANJO-SHI",
            googleMapsUrl = "https://www.google.com/maps/place/ASSEMBLEIA+DE+DEUS+BEL%C3%89M+ANJO-SHI/data=!4m2!3m1!1s0x60049792f5d9ea4f:0x60bf78a2171aee83",
            sourceLists = listOf("教会"),
        )

        val resources = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("resources")
        val dictionaries = ChurchNameEnglishDictionary.load(resources)
        val localizer = MultilingualChurchNameLocalizer(
            dictionaries = dictionaries,
            congregationTerms = CongregationTermDictionary.load(resources),
            denominations = emptyList(),
            geonames = mapOf("安城" to "Anjo"),
        )

        val candidate = GoogleMapsPlaceParser(localizer).parse(seed, html, now = "2026-07-27T00:00:00Z")

        assertEquals("安城アッセンブレイア・デ・デウスベレン", candidate.name)
        assertEquals("〒446-0024 愛知県安城市河野町下リ道", candidate.address)
        assertEquals(GeoPoint(34.9304274, 137.1143805), candidate.location)
        assertEquals("https://www.adbelemjapao.com/anjo", candidate.websiteUrl)
        assertEquals("キリスト教会", candidate.category)
    }

    @Test
    fun removesTrailingChurchNamesFromGooglePlaceAddresses() {
        val cases = listOf(
            Triple(
                "インマヌエル 熊本キリスト教会",
                "〒862-0922 熊本県熊本市東区三郎２丁目２６−３ イムマヌエル綜合伝道団熊本キリスト教会",
                "〒862-0922 熊本県熊本市東区三郎２丁目２６−３",
            ),
            Triple(
                "希望ヶ丘キリスト教会",
                "〒861-8003 熊本県熊本市北区楠８丁目１７−２２ 希望ヶ丘キリスト教会",
                "〒861-8003 熊本県熊本市北区楠８丁目１７−２２",
            ),
            Triple(
                "救世軍西成小隊",
                "〒557-0014 大阪府大阪市西成区天下茶屋１丁目１６−８ 救世軍 西成小隊",
                "〒557-0014 大阪府大阪市西成区天下茶屋１丁目１６−８",
            ),
            Triple(
                "名古屋リバイバル・チャーチ",
                "〒451-0042 愛知県名古屋市西区那古野２丁目２０−１９ 名古屋リバイバルチャーチ",
                "〒451-0042 愛知県名古屋市西区那古野２丁目２０−１９",
            ),
            Triple(
                "東京陽光基督教會",
                "〒116-0011 東京都荒川区西尾久７丁目５７ 東京陽光 基督教會",
                "〒116-0011 東京都荒川区西尾久７丁目５７",
            ),
            Triple(
                "日本基督教団江東伝導所",
                "〒124-0003 東京都葛飾区お花茶屋１丁目４−８ 日本基督教団江東伝導所",
                "〒124-0003 東京都葛飾区お花茶屋１丁目４−８",
            ),
            Triple(
                "京都シャロームチャーチ",
                "〒615-8191 京都府京都市西京区川島有栖川町７−１ 阪急桂駅から徒歩２分 サムソンビル ４F",
                "〒615-8191 京都府京都市西京区川島有栖川町７−１ サムソンビル ４F",
            ),
            Triple(
                "弓町本郷教会",
                "〒113-0033 東京都文京区本郷２丁目３５−１４ 日本キリスト教団弓町本郷教会",
                "〒113-0033 東京都文京区本郷２丁目３５−１４",
            ),
            Triple(
                "麻布霞町教会",
                "〒106-0031 東京都港区西麻布４丁目１１−１４ 霞町教会",
                "〒106-0031 東京都港区西麻布４丁目１１−１４",
            ),
            Triple(
                "日本基督教団野方町教会",
                "〒165-0027 東京都中野区野方６丁目２６−９ 日本キリスト教団野方町教会",
                "〒165-0027 東京都中野区野方６丁目２６−９",
            ),
            Triple(
                "日本基督教団 桜新町教会",
                "〒158-0081 東京都世田谷区深沢８丁目９−１６ 桜新町教会",
                "〒158-0081 東京都世田谷区深沢８丁目９−１６",
            ),
            Triple(
                "日本基督教団郡山教会",
                "〒963-8005 福島県郡山市清水台２丁目６−４ 日本基督教団郡山教会",
                "〒963-8005 福島県郡山市清水台２丁目６−４",
            ),
            Triple(
                "セブンスデー・アドベンチスト郡山教会",
                "〒963-8851 福島県郡山市開成３丁目２４−２４ セブンスデーアドベンチスト郡山教会",
                "〒963-8851 福島県郡山市開成３丁目２４−２４",
            ),
            Triple(
                "郡山ルーテルキリスト教会",
                "〒963-8861 福島県郡山市鶴見坦３丁目３−５ 日本ルーテル教団郡山ルーテルキリスト教会",
                "〒963-8861 福島県郡山市鶴見坦３丁目３−５",
            ),
            Triple(
                "小金井聖公会",
                "〒184-0003 東京都小金井市緑町４丁目１３−４ 日本聖公会東京教区小金井聖公会",
                "〒184-0003 東京都小金井市緑町４丁目１３−４",
            ),
            Triple(
                "インマヌエル郡山キリスト教会",
                "〒963-8013 福島県郡山市神明町１４−１０ インマヌエル郡山教会",
                "〒963-8013 福島県郡山市神明町１４−１０",
            ),
        )

        cases.forEachIndexed { index, (name, dirtyAddress, expectedAddress) ->
            val candidate = GoogleMapsPlaceParser().parse(
                seed("dirty-address-$index", name, "教会"),
                html(name, dirtyAddress, 32.8 + index, 130.7 + index),
                now = "2026-07-23T00:00:00Z",
            )

            assertEquals(
                expectedAddress,
                candidate.address,
                "$name: ${jp.co.crossmap.JapaneseAddressNormalizer.normalize(dirtyAddress)}",
            )
        }
    }

    @Test
    fun rejectsAddressOnlyGooglePlaceTitlesAsChurchNames() {
        assertFalse(GooglePlaceChurchCandidatePolicy.isUsableChurchName("〒169-0073 東京都新宿区百人町1丁目22−1"))
        assertFalse(GooglePlaceChurchCandidatePolicy.isUsableChurchName("〒115-0045 東京都北区赤羽3丁目8−10"))
        assertTrue(GooglePlaceChurchCandidatePolicy.isUsableChurchName("日本基督教団江東伝導所"))
    }

    @Test
    fun correctsDendoushoTypoInGooglePlaceChurchNames() {
        assertEquals("日本基督教団江東伝道所", GooglePlaceChurchNameNormalizer.normalize("日本基督教団江東伝導所"))
        assertEquals("江東伝道所", GooglePlaceChurchNameNormalizer.normalize("江東伝道所"))
    }

    @Test
    fun thirdPartyChurchListingWebsiteFallsBackToTheGooglePlacePageDuringParsing() {
        val seed = seed("10158070367548216990", "錦キリスト教会", "教会")
        val page = html("錦キリスト教会", "熊本県球磨郡錦町", 32.20, 130.84)
            .replace(
                "</body>",
                "/url?q\\u003dhttp://www.church-info.jp/sp/search/detail.php?key=16230012\\u0026opi\\u003d1</body>",
            )

        val candidate = GoogleMapsPlaceParser(
            websitePolicy = ChurchWebsitePolicy(setOf("church-info.jp")),
        ).parse(seed, page, now = "2026-07-18T00:00:00Z")

        assertEquals(
            "https://www.google.com/maps?cid=10158070367548216990",
            candidate.websiteUrl,
        )
    }

    @Test
    fun preservesRicherSeedAliasesWhenGoogleTitleOnlyContainsLatinName() {
        val seed = GoogleSavedPlaceCrawl(
            id = "google:8998728770320543438",
            googleCid = "8998728770320543438",
            title = "Just Church（ジャスト・チャーチ）",
            japaneseName = "ジャスト・チャーチ",
            latinName = "Just Church",
            googleMapsUrl = "https://www.google.com/maps?cid=8998728770320543438",
            sourceLists = listOf("教会"),
        )

        val candidate = GoogleMapsPlaceParser().parse(
            seed,
            html("Just Church", "千葉県我孫子市布佐", 35.0, 140.0),
            now = "2026-07-16T00:00:00Z",
        )

        assertEquals("ジャスト・チャーチ", candidate.name)
        assertEquals("Just Church", candidate.latinName)
    }

    @Test
    fun resolverParserOwnsMultilingualLocalizationFromRawGoogleTitle() {
        val resources = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("resources")
        val dictionaries = ChurchNameEnglishDictionary.load(resources)
        val localizer = MultilingualChurchNameLocalizer(
            dictionaries = dictionaries,
            congregationTerms = CongregationTermDictionary.load(resources),
            denominations = emptyList(),
            geonames = mapOf("浜松" to "Hamamatsu", "安城" to "Anjo"),
        )
        val title = "ADVM Assembleia de Deus Visão Missionaria Hamamatsu"
        val seed = GoogleSavedPlaceCrawl(
            id = "google:raw-portuguese",
            googleCid = "raw-portuguese",
            title = title,
            googleMapsUrl = "https://www.google.com/maps?cid=raw-portuguese",
            sourceLists = listOf("教会"),
        )

        val candidate = GoogleMapsPlaceParser(localizer).parse(
            seed,
            html(title, "静岡県浜松市", 34.71, 137.73),
            now = "2026-07-16T00:00:00Z",
        )

            assertEquals("浜松ADVMアッセンブレイア・デ・デウスヴィザォン・ミッショナリア", candidate.name)
        assertEquals(title, candidate.localizedNames.single { it.languageCode == "pt" }.name)
        assertTrue(candidate.nameComponents.isNotEmpty())
        assertEquals(ChurchNamePattern.LATIN_NAME_COMPOSED_TO_JAPANESE, candidate.namePattern)

        val portugueseTitle = "ASSEMBLEIA DE DEUS BELÉM ANJO-SHI"
        val localizedPageCandidate = GoogleMapsPlaceParser(localizer).parse(
            GoogleSavedPlaceCrawl(
                id = "google:localized-page",
                googleCid = "localized-page",
                title = portugueseTitle,
                titleLanguages = listOf("pt"),
                googleMapsUrl = "https://www.google.com/maps?cid=localized-page",
                sourceLists = listOf("教会"),
            ),
            html("アッセンブレイアデデウスべレムANJO-SHI", "愛知県安城市", 34.95, 137.08),
            now = "2026-07-16T00:00:00Z",
        )

            assertEquals("安城アッセンブレイア・デ・デウスベレン", localizedPageCandidate.name)
        assertEquals(portugueseTitle, localizedPageCandidate.localizedNames.single { it.languageCode == "pt" }.name)
        assertTrue(localizedPageCandidate.nameComponents.all { it.sourceLanguage == "pt" })
    }

    @Test
    fun resolvesCachedPagesFiltersNonChurchCatholicPlacesAndWritesAuditReport() {
        val root = Files.createTempDirectory("crossmap-google-place-resolver")
        try {
            val raw = Files.createDirectories(root.resolve("cache/google-saved-places"))
            val seeds = listOf(
                seed("2000906460470208781", "カトリック厚木教会", "カトリック教会").copy(titleLanguages = listOf("ja")),
                seed("5433858323697585828", "盛岡ドミニカン修道院", "カトリック教会"),
            )
            Files.writeString(raw.resolve("seeds.json"), json.encodeToString(seeds))
            val pages = mapOf(
                seeds[0].id to html("カトリック厚木教会", "〒243-0014 神奈川県厚木市旭町２丁目７−１１", 35.436, 139.365),
                seeds[1].id to html("盛岡ドミニカン修道院", "〒020-0102 岩手県盛岡市上田", 39.72, 141.13),
            )

            val report = GoogleSavedPlacesCrawler(json = json).resolve(
                resourcesRoot = root,
                cacheRoot = root.resolve("cache"),
                pageSource = GoogleMapsPageSource { seed -> GoogleMapsPage(pages.getValue(seed.id), cacheHit = true) },
            )
            val candidates = json.decodeFromString<List<GooglePlaceChurchCandidate>>(
                Files.readString(raw.resolve("google-place-candidates.json")),
            )
            val enrichedSeeds = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(
                Files.readString(raw.resolve("seeds.json")),
            )

            assertEquals(2, report.seeds)
            assertEquals(1, report.candidates)
            assertEquals(2, report.cacheHits)
            assertEquals(1, report.catholicNonChurchesFiltered)
            assertTrue(report.errors.isEmpty())
            assertEquals("CATHOLIC_JP", candidates.single().denominationHint)
            assertEquals("カトリック厚木教会", enrichedSeeds.first().japaneseName)
            assertEquals(listOf("ja"), enrichedSeeds.first().titleLanguages)
            assertTrue(enrichedSeeds.first().localizedNames.any { it.languageCode == "ja" })
            assertEquals(null, enrichedSeeds.last().japaneseName, "Filtered non-church seeds remain raw for audit")
            assertTrue(Files.isRegularFile(raw.resolve("google-place-resolution-report.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun excludesConfiguredGooglePlaceBeforeLoadingItsPage() {
        val root = Files.createTempDirectory("crossmap-google-place-exclusion")
        try {
            val raw = Files.createDirectories(root.resolve("cache/google-saved-places"))
            Files.createDirectories(root.resolve("catalog"))
            Files.writeString(
                root.resolve("catalog/excludedGooglePlaces.txt"),
                "1885579487859421798 | Catholic cemetery; not a church\n",
            )
            Files.writeString(
                raw.resolve("seeds.json"),
                json.encodeToString(listOf(seed("1885579487859421798", "カトリック教会", "カトリック教会"))),
            )

            val report = GoogleSavedPlacesCrawler(json = json).resolve(
                resourcesRoot = root,
                cacheRoot = root.resolve("cache"),
                pageSource = GoogleMapsPageSource { error("An excluded place must not be loaded") },
            )
            val candidates = json.decodeFromString<List<GooglePlaceChurchCandidate>>(
                Files.readString(raw.resolve("google-place-candidates.json")),
            )

            assertEquals(1, report.seeds)
            assertEquals(0, report.candidates)
            assertEquals(1, report.catholicNonChurchesFiltered)
            assertTrue(report.errors.isEmpty())
            assertTrue(candidates.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun throwsExceptionAndHaltsProcessOnFetchOrParseError() {
        val root = Files.createTempDirectory("crossmap-google-place-error-halt")
        try {
            val raw = Files.createDirectories(root.resolve("cache/google-saved-places"))
            val seeds = listOf(
                seed("9999999999999999999", "Failed Place", "教会"),
            )
            Files.writeString(raw.resolve("seeds.json"), json.encodeToString(seeds))

            val exception = kotlin.test.assertFailsWith<IllegalStateException> {
                GoogleSavedPlacesCrawler(json = json).resolve(
                    resourcesRoot = root,
                    cacheRoot = root.resolve("cache"),
                    pageSource = GoogleMapsPageSource { seed -> GoogleMapsPage("<html>Invalid HTML without place info</html>", cacheHit = false) },
                )
            }
            assertTrue(exception.message!!.contains("Failed to parse Google Maps page"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun seed(cid: String, title: String, list: String) = GoogleSavedPlaceCrawl(
        id = "google:$cid",
        googleCid = cid,
        title = title,
        googleMapsUrl = "https://www.google.com/maps?cid=$cid",
        sourceLists = listOf(list),
    )

    private fun html(name: String, address: String, lat: Double, lng: Double) = """
        <html><head><meta content="$name · $address" property="og:title"><meta content="カトリック教会" property="og:description"></head>
        <body>https://www.google.com/maps/preview/place/${java.net.URLEncoder.encode("$address $name", Charsets.UTF_8)}/@$lat,$lng,100a</body></html>
    """.trimIndent()

    @Test
    fun parsesFreshlyFetchedGoogleMapsHtml6971423385760493187() {
        val cacheFile = Path.of("cache/web-pages/9e7555a470ea6aaec648423405223a6e0d5d8e429c7417849612650616b27df5.html")
        if (!Files.exists(cacheFile)) return
        val html = Files.readString(cacheFile)

        val seed = GoogleSavedPlaceCrawl(
            id = "google:6971423385760493187",
            googleCid = "6971423385760493187",
            title = "ASSEMBLEIA DE DEUS BELÉM ANJO-SHI",
            googleMapsUrl = "https://www.google.com/maps/place/Igreja+Evang%C3%A9lica+Assembleia+de+Deus+miss%C3%A3o+Bel%C3%A9m+De+Anjo_JP/data=!4m2!3m1!1s0x600497cb4b702d03:0xc7628bf08de04343",
            sourceLists = listOf("教会"),
        )

        val resources = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("resources")
        val dictionaries = ChurchNameEnglishDictionary.load(resources)
        val localizer = MultilingualChurchNameLocalizer(
            dictionaries = dictionaries,
            congregationTerms = CongregationTermDictionary.load(resources),
            denominations = emptyList(),
            geonames = mapOf("安城" to "Anjo"),
        )

        val candidate = GoogleMapsPlaceParser(localizer).parse(seed, html, now = "2026-07-28T00:00:00Z")

        assertTrue(candidate.name.isNotBlank(), "name should not be blank")
        assertTrue(candidate.address.isNotBlank(), "address should not be blank")
        assertTrue(candidate.location.latitude != 0.0, "latitude should be non-zero")
        assertTrue(candidate.location.longitude != 0.0, "longitude should be non-zero")
    }
}
