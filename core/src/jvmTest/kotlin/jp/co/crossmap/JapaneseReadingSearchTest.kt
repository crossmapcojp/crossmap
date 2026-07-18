package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class JapaneseReadingSearchTest {
    @Test
    fun kuromojiProducesStableHiraganaReadingsForSearchVocabulary() {
        assertEquals("とうきょう", JapaneseReadingNormalizer.compactReading("東京"))
        assertEquals("おおさか", JapaneseReadingNormalizer.compactReading("大阪"))
        assertEquals("ばぷてすと", JapaneseReadingNormalizer.compactReading("バプテスト"))
        assertEquals("さぬきし", JapaneseReadingNormalizer.compactReading("さぬき市"))
        assertTrue(
            JapaneseReadingNormalizer.searchReadings("日本キリスト教団")
                .map { it.replace(" ", "") }
                .contains("にほんきりすときょうだん"),
        )
        assertEquals("かぬき教会", ChurchIndex.normalizeExactName("  かぬき 教会  "))
        assertEquals("tokyo baptist church", ChurchIndex.normalizeExactName(" Tokyo  Baptist Church "))
    }

    @Test
    fun searchesChurchDenominationTraditionAndAdministrativeGeonameByReading() {
        val root = Files.createTempDirectory("crossmap-japanese-readings")
        try {
            val tokyo = prefecture("13", "東京都", 35.6897, 139.6920)
            val osakaPrefecture = prefecture("27", "大阪府", 34.6863, 135.5200)
            val osakaCity = GeoName(
                code = "27100",
                name = "大阪市",
                aliases = listOf("大阪"),
                type = GeoNameType.MUNICIPALITY,
                prefectureCode = "27",
                center = GeoPoint(34.6937, 135.5023),
                coveringRadiusKm = 1.0,
                translations = mapOf("en" to "Osaka"),
            )
            val sanukiCity = GeoName(
                code = "372064",
                name = "さぬき市",
                aliases = listOf("さぬき"),
                type = GeoNameType.MUNICIPALITY,
                prefectureCode = "37",
                center = GeoPoint(34.3252, 134.1720),
                coveringRadiusKm = 1.0,
                translations = mapOf("en" to "Sanuki"),
            )
            val geonames = listOf(tokyo, osakaPrefecture, osakaCity, sanukiCity)
            val churches = listOf(
                church(
                    id = "kanuki",
                    name = "日本基督教団 香貫教会",
                    aliases = listOf("キリスト教団 香貫教会", "かぬき教会"),
                    denominationNames = listOf("日本基督教団", "日本キリスト教団"),
                    category = "キリスト教教会",
                    address = "静岡県沼津市上香貫宮原町1515",
                    latitude = 35.0873783,
                    longitude = 138.8719646,
                ),
                church(
                    id = "semaru",
                    name = "日本キリスト教団世真留教会",
                    aliases = listOf("せまる教会"),
                    denominationNames = listOf("日本キリスト教団"),
                    category = "キリスト教教会",
                    address = "広島県福山市",
                    latitude = 34.4859,
                    longitude = 133.3623,
                ),
                church(
                    id = "tokyo-baptist",
                    name = "東京バプテスト教会",
                    aliases = emptyList(),
                    denominationNames = emptyList(),
                    category = "バプテスト教会",
                    address = "東京都渋谷区鉢山町9-2",
                    latitude = 35.6506,
                    longitude = 139.6967,
                ),
                church(
                    id = "osaka-lutheran",
                    name = "日本福音ルーテル大阪教会",
                    aliases = emptyList(),
                    denominationNames = listOf("日本福音ルーテル教会"),
                    category = "ルーテル教会",
                    address = "大阪府大阪市中央区",
                    latitude = 34.6810,
                    longitude = 135.5090,
                ),
            )
            val normalizedAddresses = mapOf(
                "kanuki" to address(churches[0].address, "静岡県", "22", "沼津市", "22203"),
                "semaru" to address(churches[1].address, "広島県", "34", "福山市", "34207"),
                "tokyo-baptist" to address(churches[2].address, "東京都", "13", "渋谷区", "13113"),
                "osaka-lutheran" to address(churches[3].address, "大阪府", "27", "大阪市", "27100"),
            )
            val index = root.resolve("index").toString().toPath()
            ChurchIndex.build(
                index,
                churches,
                languageCode = "ja",
                geonames = geonames,
                normalizedAddresses = normalizedAddresses,
            )

            val resolver = GeoNameResolver(geonames)
            listOf("おおさか", "大阪").forEach { query ->
                assertEquals("27100", resolver.resolve(query).locations.single().code, query)
            }
            assertEquals("27100", resolver.resolve("Osaka", language = "en").locations.single().code)
            assertEquals("27", resolver.resolve("大阪府").locations.single().code)
            assertEquals("27", resolver.resolve("Osaka Prefecture", language = "en").locations.single().code)
            assertTrue(resolver.resolve("かぬき 教会").locations.isEmpty())

            val engine = ChurchSearchEngine(index, geonames, languageCode = "ja")
            try {
                val kanukiResult = engine.search(ChurchSearchRequest("かぬき 教会"))
                assertEquals("kanuki", kanukiResult.hits.first().churchId)
                assertTrue(kanukiResult.resolvedLocations.isEmpty())
                assertEquals("semaru", engine.search(ChurchSearchRequest("せまる 教会")).hits.first().churchId)
                assertEquals(
                    setOf("kanuki", "semaru"),
                    engine.search(ChurchSearchRequest("にほんきりすときょうだん")).hits
                        .map { it.churchId }
                        .toSet(),
                )
                assertEquals(
                    listOf("tokyo-baptist"),
                    engine.search(ChurchSearchRequest("ばぷてすと")).hits.map { it.churchId },
                )

                val tokyoResult = engine.search(ChurchSearchRequest("とうきょう"))
                assertTrue(tokyoResult.hits.isNotEmpty())
                assertTrue(tokyoResult.hits.all { it.address.startsWith("東京都") })
                assertEquals(listOf("東京都"), tokyoResult.resolvedLocations.map { it.name })

                val osakaResult = engine.search(ChurchSearchRequest("おおさか"))
                assertEquals(listOf("osaka-lutheran"), osakaResult.hits.map { it.churchId })
                assertEquals(listOf("大阪市"), osakaResult.resolvedLocations.map { it.name })
            } finally {
                engine.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun prefecture(code: String, name: String, latitude: Double, longitude: Double) = GeoName(
        code = code,
        name = name,
        type = GeoNameType.PREFECTURE,
        prefectureCode = code,
        center = GeoPoint(latitude, longitude),
        coveringRadiusKm = 1.0,
        translations = mapOf("en" to when (code) {
            "13" -> "Tokyo"
            "27" -> "Osaka"
            else -> name
        }),
    )

    private fun church(
        id: String,
        name: String,
        aliases: List<String>,
        denominationNames: List<String>,
        category: String,
        address: String,
        latitude: Double,
        longitude: Double,
    ) = ChurchRecord(
        id = id,
        name = name,
        englishName = "$id Church",
        localizedNames = (listOf(name) + aliases).map { LocalizedName("ja", it) },
        localizedDenominationNames = denominationNames.map { LocalizedName("ja", it) },
        denominationId = "UCCJ".takeIf { denominationNames.isNotEmpty() },
        category = category,
        address = address,
        location = GeoPoint(latitude, longitude),
        websiteUrl = "https://example.invalid/$id",
    )

    private fun address(
        original: String,
        prefecture: String,
        prefectureCode: String,
        municipality: String,
        municipalityCode: String,
    ) = JapaneseAddress(
        original = original,
        normalized = original,
        prefecture = prefecture,
        prefectureCode = prefectureCode,
        municipality = municipality,
        municipalityCode = municipalityCode,
    )
}
