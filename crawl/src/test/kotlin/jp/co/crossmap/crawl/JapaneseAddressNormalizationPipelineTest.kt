package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoName
import jp.co.crossmap.GeoNameType
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JapaneseAddressNormalizationPipelineTest {
    @Test
    fun recordsRealChurchSuccessLevelsStructuredPartsAndDetailedErrors() {
        val directory = Files.createTempDirectory("crossmap-address-normalization")
        try {
            val churches = listOf(
                ChurchRecord(
                    id = "google:8998728770320543438",
                    name = "布佐キリスト教会",
                    englishName = "Fusa Christ Church",
                    address = "〒270-1101 千葉県我孫子市布佐１２３４−５ 布佐ビル",
                    location = GeoPoint(35.85, 140.13),
                    websiteUrl = "https://www.fusa-christ-church.example/",
                ),
                ChurchRecord(
                    id = "google:2225537460932230335",
                    name = "日本聖公会東京聖アンデレ教会",
                    englishName = "NSKK Tokyo St Andrew Church",
                    address = "不明な住所",
                    location = GeoPoint(35.66, 139.74),
                    websiteUrl = "https://www.st-andrew-tokyo.com/",
                ),
            )
            val geonames = listOf(
                GeoName("12", "千葉県", type = GeoNameType.PREFECTURE, prefectureCode = "12", center = GeoPoint(35.60, 140.12), coveringRadiusKm = 80.0),
                GeoName("12222", "我孫子市", type = GeoNameType.MUNICIPALITY, prefectureCode = "12", center = GeoPoint(35.86, 140.03), coveringRadiusKm = 20.0),
            )
            val normalizer = ChurchAddressBatchNormalizer { requests ->
                requests.map { request ->
                    if (request.churchId == churches.first().id) {
                        GeoloniaAddressResult(
                            request.churchId,
                            status = "success",
                            pref = "千葉県",
                            city = "我孫子市",
                            town = "布佐",
                            addr = "1234-5",
                            other = " 布佐ビル",
                            level = 8,
                        )
                    } else {
                        GeoloniaAddressResult(request.churchId, status = "error", error = "都道府県を判別できません")
                    }
                }
            }

            val report = JapaneseAddressNormalizationPipeline(normalizer)
                .normalize(churches, geonames, directory.resolve("normalized-addresses.json"))

            assertEquals(mapOf(0 to 1, 8 to 1), report.levelCounts)
            assertEquals(1, report.errors.size)
            with(report.entries.first()) {
                assertEquals("address-number", levelName)
                assertEquals("千葉県", normalizedAddress.prefecture)
                assertEquals("12", normalizedAddress.prefectureCode)
                assertEquals("我孫子市", normalizedAddress.municipality)
                assertEquals("12222", normalizedAddress.municipalityCode)
                assertEquals("布佐", normalizedAddress.locality)
                assertEquals("1234-5", normalizedAddress.addressNumber)
                assertEquals("布佐ビル", normalizedAddress.building)
            }
            assertTrue(report.errors.single().error.orEmpty().contains("判別"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun reusesOnlySuccessfulEntriesWhoseOriginalAddressIsUnchanged() {
        val directory = Files.createTempDirectory("crossmap-address-cache")
        try {
            val church = ChurchRecord(
                id = "google:6646597370070891755",
                name = "東京バプテスト教会",
                englishName = "Tokyo Baptist Church",
                address = "〒150-0035 東京都渋谷区鉢山町９−２",
                location = GeoPoint(35.65, 139.69),
                websiteUrl = "https://tokyobaptist.org/",
            )
            var executions = 0
            val normalizer = ChurchAddressBatchNormalizer { requests ->
                executions += requests.size
                requests.map {
                    GeoloniaAddressResult(it.churchId, "success", "東京都", "渋谷区", "鉢山町", "9-2", level = 8)
                }
            }
            val cache = directory.resolve("normalized-addresses.json")
            val pipeline = JapaneseAddressNormalizationPipeline(normalizer)
            pipeline.normalize(listOf(church), emptyList(), cache)
            val second = pipeline.normalize(listOf(church), emptyList(), cache)

            assertEquals(1, executions)
            assertEquals(1, second.reused)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun changedGeonameCatalogReenrichesCachedGeoloniaPartsWithoutExecutingNodeAgain() {
        val directory = Files.createTempDirectory("crossmap-address-geoname-reenrichment")
        try {
            val church = ChurchRecord(
                id = "google:fukuoka-chuo",
                name = "福岡城南教会",
                englishName = "Fukuoka Jonan Church",
                address = "〒810-0044 福岡県福岡市中央区六本松１丁目",
                location = GeoPoint(33.578, 130.378),
                websiteUrl = "https://fukuoka.example/",
            )
            val prefecture = GeoName("40", "福岡県", type = GeoNameType.PREFECTURE, prefectureCode = "40", center = church.location, coveringRadiusKm = 80.0)
            val city = GeoName("401307", "福岡市", type = GeoNameType.MUNICIPALITY, prefectureCode = "40", center = church.location, coveringRadiusKm = 30.0)
            val ward = GeoName("401331", "中央区", aliases = listOf("福岡市中央区"), type = GeoNameType.WARD, prefectureCode = "40", center = church.location, coveringRadiusKm = 15.0)
            var executions = 0
            val normalizer = ChurchAddressBatchNormalizer { requests ->
                executions += requests.size
                requests.map {
                    GeoloniaAddressResult(
                        it.churchId,
                        "success",
                        pref = "福岡県",
                        city = "福岡市",
                        town = "中央区六本松",
                        addr = "1丁目",
                        level = 8,
                    )
                }
            }
            val cache = directory.resolve("normalized-addresses.json")
            val pipeline = JapaneseAddressNormalizationPipeline(normalizer)
            pipeline.normalize(listOf(church), listOf(prefecture, city), cache)
            val second = pipeline.normalize(listOf(church), listOf(prefecture, city, ward), cache)

            assertEquals(1, executions)
            assertEquals(0, second.reused)
            assertEquals(1, second.reEnriched)
            assertEquals("401331", second.entries.single().normalizedAddress.cityWardCode)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun removesGoogleMapsPostalPrefixBeforeCallingGeolonia() {
        assertEquals(
            "東京都渋谷区鉢山町9-2",
            prepareAddressForGeolonia("〒150-0035 東京都渋谷区鉢山町９−２"),
        )
    }
}
