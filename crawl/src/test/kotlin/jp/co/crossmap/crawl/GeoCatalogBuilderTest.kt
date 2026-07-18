package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoNameType
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoCatalogBuilderTest {
    @Test
    fun tokyoPrefectureGeometryExcludesRemoteIslandsAndNormalizesMunicipalityCodes() {
        val root = Files.createTempDirectory("crossmap-geocatalog")
        try {
            val municipalitySource = root.resolve("Japan.kt")
            Files.writeString(
                municipalitySource,
                """
                    13030 to "当別町",
                    131016 to "千代田区",
                    134210 to "小笠原村",
                """.trimIndent(),
            )
            val mainland = GeoPoint(35.681236, 139.767125)
            val churches = listOf(
                ChurchRecord(
                    id = "google:tokyo",
                    name = "東京教会",
                    englishName = "Tokyo Church",
                    address = "東京都千代田区丸の内",
                    location = mainland,
                    websiteUrl = "https://tokyo.example/",
                ),
                ChurchRecord(
                    id = "google:ogasawara",
                    name = "小笠原教会",
                    englishName = "Ogasawara Church",
                    address = "東京都小笠原村父島",
                    location = GeoPoint(27.0933975, 142.1910068),
                    websiteUrl = "https://ogasawara.example/",
                ),
            )

            val result = GeoCatalogBuilder().build(churches, municipalitySource, root.resolve("japan.json"))
            val tokyo = result.single { it.code == "13" && it.type == GeoNameType.PREFECTURE }
            val ogasawara = result.single { it.name == "小笠原村" }
            val tobetsu = result.single { it.name == "当別町" }

            assertEquals(mainland, tokyo.center)
            assertTrue(tokyo.coveringRadiusKm <= 15.0)
            assertFalse(ogasawara.includeInPrefectureSearch)
            assertEquals("013030", tobetsu.code)
            assertEquals("01", tobetsu.prefectureCode)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun jmaCatalogAddsDuplicateDesignatedCityWardsWithParentQualifiedAliases() {
        val root = Files.createTempDirectory("crossmap-jma-geocatalog")
        try {
            val jmaSource = root.resolve("jma-city.json")
            Files.writeString(
                jmaSource,
                """
                {
                  "1310200": {
                    "japanese": "東京中央区",
                    "english": "Chuo City, Tokyo",
                    "korean": "도쿄 주오 구",
                    "portuguese": "Tóquio, bairro de Chuo",
                    "indonesian": "Distrik Kota Chuo, Tokyo"
                  },
                  "4013300": {
                    "japanese": "福岡中央区",
                    "english": "Chuo Ward, Fukuoka",
                    "korean": "후쿠오카 주오구",
                    "portuguese": "Fukuoka, distrito de Chuo",
                    "indonesian": "Distrik Kota Chuo, Fukuoka"
                  }
                }
                """.trimIndent(),
            )
            val tokyo = GeoPoint(35.672, 139.780)
            val fukuoka = GeoPoint(33.589, 130.392)
            val churches = listOf(
                ChurchRecord(
                    id = "google:tokyo-chuo",
                    name = "日本橋教会",
                    englishName = "Nihonbashi Church",
                    address = "東京都中央区日本橋１丁目",
                    location = tokyo,
                    websiteUrl = "https://nihonbashi.example/",
                ),
                ChurchRecord(
                    id = "google:fukuoka-chuo",
                    name = "福岡城南教会",
                    englishName = "Fukuoka Jonan Church",
                    address = "福岡県福岡市中央区六本松１丁目",
                    location = fukuoka,
                    websiteUrl = "https://fukuoka.example/",
                ),
            )

            val result = GeoCatalogBuilder().build(churches, jmaSource, root.resolve("japan.json"))
            val centralWards = result.filter { it.name == "中央区" }
            val fukuokaWard = centralWards.single { it.prefectureCode == "40" }

            assertEquals(setOf("131024", "401331"), centralWards.map { it.code }.toSet())
            assertEquals(fukuoka, fukuokaWard.center)
            assertTrue("福岡市中央区" in fukuokaWard.aliases)
            assertEquals("Chuo Ward, Fukuoka", fukuokaWard.translations["en"])
            assertEquals("401307", result.single { it.name == "福岡市" }.code)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
