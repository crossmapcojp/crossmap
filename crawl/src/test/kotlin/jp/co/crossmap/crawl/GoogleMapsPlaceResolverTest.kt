package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GoogleMapsPlaceResolverTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun parsesTheSameGoogleEvidenceFieldsUsedByGmap() {
        val seed = GoogleSavedPlaceSeed(
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
    }

    @Test
    fun resolvesCachedPagesFiltersNonChurchCatholicPlacesAndWritesAuditReport() {
        val root = Files.createTempDirectory("crossmap-google-place-resolver")
        try {
            val raw = Files.createDirectories(root.resolve("raw/google-saved-places"))
            val seeds = listOf(
                seed("2000906460470208781", "カトリック厚木教会", "カトリック教会"),
                seed("5433858323697585828", "盛岡ドミニカン修道院", "カトリック教会"),
            )
            Files.writeString(raw.resolve("seeds.json"), json.encodeToString(seeds))
            val pages = mapOf(
                seeds[0].id to html("カトリック厚木教会", "〒243-0014 神奈川県厚木市旭町２丁目７−１１", 35.436, 139.365),
                seeds[1].id to html("盛岡ドミニカン修道院", "〒020-0102 岩手県盛岡市上田", 39.72, 141.13),
            )

            val report = GoogleMapsPlaceResolver(
                pageSource = GoogleMapsPageSource { seed -> GoogleMapsPage(pages.getValue(seed.id), cacheHit = true) },
                maxConcurrency = 2,
            ).resolve(root)
            val candidates = json.decodeFromString<List<GooglePlaceChurchCandidate>>(
                Files.readString(raw.resolve("google-place-candidates.json")),
            )

            assertEquals(2, report.seeds)
            assertEquals(1, report.candidates)
            assertEquals(2, report.cacheHits)
            assertEquals(1, report.catholicNonChurchesFiltered)
            assertTrue(report.errors.isEmpty())
            assertEquals("CATHOLIC_JP", candidates.single().denominationHint)
            assertTrue(Files.isRegularFile(raw.resolve("google-place-resolution-report.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun seed(cid: String, title: String, list: String) = GoogleSavedPlaceSeed(
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
}
