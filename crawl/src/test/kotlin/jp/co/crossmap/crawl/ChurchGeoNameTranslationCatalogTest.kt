package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChurchGeoNameTranslationCatalogTest {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun writesDetectedRealGeonamesAndPreservesReviewedTranslations() {
        val root = Files.createTempDirectory("crossmap-church-geonames")
        try {
            val resources = root.resolve("resources")
            val candidates = root.resolve("google-place-candidates.json")
            Files.createDirectories(resources.resolve("geonames"))
            val legacyPortuguese = resources.resolve("geonames/church-ja-pt-missing.csv")
            Files.writeString(legacyPortuguese, "雲内,Kumouchi\n")
            Files.writeString(
                candidates,
                json.encodeToString(
                    listOf(
                        candidate("google:2225537460932230335", "大阪聖和教会", "大阪", mapOf("en" to "Osaka", "ko" to "오사카")),
                        candidate("google:6646597370070891755", "日本基督教団神戸雲内教会", "雲内", mapOf("en" to "Kumouchi")),
                    )
                ),
            )

            val report = ChurchGeoNameTranslationCatalog(json).build(
                candidates,
                resources,
                mapOf(
                    "大阪" to mapOf("en" to "Osaka", "ko" to "오사카", "pt" to "Osaka", "id" to "Osaka"),
                    "東京都" to mapOf("en" to "Tokyo Metropolis", "ko" to "도쿄도"),
                ),
            )

            assertEquals(3, report.churchGeoNames)
            assertEquals(2, report.titleGeoNames)
            assertEquals(1, report.addressGeoNames)
            assertEquals(3, report.translatedCounts.getValue("en"))
            assertEquals(2, report.translatedCounts.getValue("ko"))
            val all = Files.readString(resources.resolve("geonames/church-ja-all.json"))
            assertTrue(all.contains("Kumouchi"))
            assertTrue(all.contains("Osaka"))
            val entries = json.decodeFromString<List<ChurchGeoNameTranslation>>(all)
            assertEquals("Kumouchi", entries.single { it.ja == "雲内" }.translations["pt"])
            assertTrue(Files.notExists(legacyPortuguese))
            assertEquals(entries.size, entries.map { it.ja }.distinct().size)
            assertTrue(entries.all { entry ->
                entry.translations.keys.all { it in ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES }
            })
            ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.forEach { language ->
                val titleRows = Files.readAllLines(resources.resolve("geonames/church-ja-$language-title-missing.csv"))
                    .filter(String::isNotBlank)
                val addressRows = Files.readAllLines(resources.resolve("geonames/church-ja-$language-address-missing.csv"))
                    .filter(String::isNotBlank)
                assertEquals(titleRows.size, titleRows.map { it.substringBefore(',') }.distinct().size)
                assertEquals(addressRows.size, addressRows.map { it.substringBefore(',') }.distinct().size)
                assertTrue(titleRows.map { it.substringBefore(',') }.intersect(addressRows.map { it.substringBefore(',') }.toSet()).isEmpty())
            }
            assertTrue(Files.readString(resources.resolve("geonames/church-ja-pt-title-missing.csv")).contains("雲内,Kumouchi"))
            assertTrue(Files.readString(resources.resolve("geonames/church-ja-pt-address-missing.csv")).contains("東京都,"))
            val usages = json.decodeFromString<List<ChurchGeoNameUsage>>(
                Files.readString(resources.resolve("geonames/church-usage.json")),
            )
            val osakaUsage = usages.single { it.churchId == "google:2225537460932230335" }
            assertEquals("大阪聖和教会", osakaUsage.googlePlaceTitle)
            assertEquals(listOf("大阪"), osakaUsage.title)
            assertTrue(usages.any { "東京都" in it.address })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun usesJapaneseTranslationAsCatalogKeyForLatinGeonameComponent() {
        val root = Files.createTempDirectory("crossmap-latin-geoname-catalog")
        try {
            val resources = root.resolve("resources")
            val candidates = root.resolve("google-place-candidates.json")
            Files.createDirectories(resources.resolve("geonames"))
            Files.writeString(
                candidates,
                json.encodeToString(
                    listOf(
                        candidate(
                            id = "google:3",
                            name = "Iglesia Cristo Viene Yamanashi",
                            geoname = "Yamanashi",
                            translations = mapOf("ja" to "山梨", "en" to "Yamanashi", "es" to "Yamanashi"),
                            sourceLanguage = "es",
                        ),
                    ),
                ),
            )

            ChurchGeoNameTranslationCatalog(json).build(
                candidates,
                resources,
                mapOf("山梨" to mapOf("en" to "Yamanashi")),
            )

            val entries = json.decodeFromString<List<ChurchGeoNameTranslation>>(
                Files.readString(resources.resolve("geonames/church-ja-all.json")),
            )
            assertTrue(entries.any { it.ja == "山梨" })
            assertTrue(entries.none { it.ja == "Yamanashi" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cleansUnsafeTitleAndAddressGeonamesBeforeWritingReviewQueues() {
        val root = Files.createTempDirectory("crossmap-clean-church-geonames")
        try {
            val resources = root.resolve("resources")
            val candidates = root.resolve("google-place-candidates.json")
            Files.createDirectories(resources.resolve("geonames"))
            Files.writeString(
                candidates,
                json.encodeToString(
                    listOf(
                        candidate(
                            id = "google:4",
                            name = "ナザレン教会",
                            geoname = "ナザレン教会",
                            translations = mapOf("en" to "Nazarene Church"),
                            address = "千葉県佐倉市ユーカリが丘マリ一丁目",
                        ),
                    ),
                ),
            )
            val report = ChurchGeoNameTranslationCatalog(
                json,
                JapaneseGeoNameCleaner(setOf("ナザレン教会")),
            ).build(
                candidates,
                resources,
                mapOf(
                    "ユーカリが丘" to mapOf("en" to "Yukarigaoka"),
                    "マリ" to mapOf("en" to "Mari"),
                    "一丁目" to mapOf("en" to "Itchome"),
                ),
            )

            val entries = json.decodeFromString<List<ChurchGeoNameTranslation>>(
                Files.readString(resources.resolve("geonames/church-ja-all.json")),
            )
            assertEquals(listOf("ユーカリが丘"), entries.map(ChurchGeoNameTranslation::ja))
            assertEquals(1, report.reviewedChurchNamesRemoved)
            assertEquals(1, report.katakanaOnlyNamesRemoved)
            assertEquals(1, report.addressBlocksRemoved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun candidate(
        id: String,
        name: String,
        geoname: String,
        translations: Map<String, String>,
        sourceLanguage: String = "ja",
        address: String = "東京都港区芝公園３丁目６−１８",
    ) = GooglePlaceChurchCandidate(
        id = id,
        googleCid = id.substringAfter(':'),
        name = name,
        nameComponents = listOf(
            MultilingualNameComponent(geoname, MultilingualNameComponentRole.GEONAME, translations, sourceLanguage),
        ),
        address = address,
        location = GeoPoint(35.6601808, 139.743601),
        websiteUrl = "https://example.test/",
        sourceLists = listOf("教会"),
        resolvedAt = "2026-07-16T00:00:00Z",
    )
}
