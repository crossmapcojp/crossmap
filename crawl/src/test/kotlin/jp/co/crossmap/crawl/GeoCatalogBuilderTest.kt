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
}
