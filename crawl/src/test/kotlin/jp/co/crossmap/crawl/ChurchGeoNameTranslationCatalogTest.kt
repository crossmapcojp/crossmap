package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChurchGeoNameTranslationCatalogTest {
    @Test
    fun derivesKoreanJapanesePronunciationFromEnglishRomaji() {
        assertEquals("가누키", romajiToHangul("Kanuki"))
        assertEquals("가미카누키", romajiToHangul("Kamikanuki"))
        assertEquals("시모카누키", romajiToHangul("Shimokanuki"))
        assertEquals("세타가야", romajiToHangul("Setagaya"))
        assertEquals("도쿄", romajiToHangul("Tokyo"))
        assertEquals("젠카이미나미마치", romajiToHangul("Zenkaiminamimachi"))
        assertTrue(JapaneseRomajiToHangul.hasCompatibleInitial("Kamikanuki", "가미카누키"))
        assertTrue(JapaneseRomajiToHangul.hasCompatibleInitial("Kamikanuki", "카미카누키"))
        assertTrue(!JapaneseRomajiToHangul.hasCompatibleInitial("Kamikanuki", "상향관"))
        assertEquals(emptySet(), JapaneseRomajiToHangul.churchAbbreviations("UNIDOS COM CRISTO"))
        assertEquals(emptySet(), JapaneseRomajiToHangul.churchAbbreviations("LORD ABBA"))
        assertEquals(setOf("HCC", "JBC", "EMC"), JapaneseRomajiToHangul.churchAbbreviations("HCC JBC EMC"))
        assertEquals(setOf("PMCC"), JapaneseRomajiToHangul.churchAbbreviations("PMCC4テーワッチ川崎"))
        assertEquals("에이치시시 가누키 교회", JapaneseRomajiToHangul.transliterateLatinFragments("HCC Kanuki 교회"))
        assertEquals(
            "HCC 가누키 교회",
            JapaneseRomajiToHangul.transliterateLatinFragments(
                "HCC Kanuki 교회",
                JapaneseRomajiToHangul.churchAbbreviations("HCCライブチャーチ寸座"),
            ),
        )
    }
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
            assertEquals(3, report.translatedCounts.getValue("ko"))
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
    fun everyPreviouslyMissingKoreanGeonameUsesItsEnglishRomajiPronunciation() {
        val resources = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("resources")
        val entries = json.decodeFromString<List<ChurchGeoNameTranslation>>(
            Files.readString(resources.resolve("geonames/church-ja-all.json")),
        ).associateBy(ChurchGeoNameTranslation::ja)
        val rows = listOf("title", "address").flatMap { kind ->
            Files.readAllLines(resources.resolve("geonames/church-ja-ko-$kind-missing.csv"))
                .filter(String::isNotBlank)
        }

        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val japanese = row.substringBefore(',')
            val korean = row.substringAfter(',').trim()
            val romaji = entries.getValue(japanese).translations.getValue("en")
            assertTrue(korean.isNotBlank(), japanese)
            assertTrue(korean.none { it in 'A'..'Z' || it in 'a'..'z' }, "$japanese: $romaji -> $korean")
            assertEquals(romajiToHangul(romaji), korean, japanese)
            assertTrue(JapaneseRomajiToHangul.hasCompatibleInitial(romaji, korean), "$japanese: $romaji -> $korean")
        }
    }

    @Test
    fun replacesReviewedHanjaStyleMissingValueWithRomajiPronunciation() {
        val root = Files.createTempDirectory("crossmap-korean-romaji-review")
        try {
            val resources = root.resolve("resources")
            val candidates = root.resolve("google-place-candidates.json")
            Files.createDirectories(resources.resolve("geonames"))
            Files.writeString(resources.resolve("geonames/church-ja-ko-address-missing.csv"), "上香貫,상향관\n")
            Files.writeString(
                candidates,
                json.encodeToString(
                    listOf(
                        candidate(
                            "kanuki",
                            "香貫教会",
                            "香貫",
                            mapOf("en" to "Kanuki"),
                            address = "静岡県沼津市上香貫",
                        ),
                    ),
                ),
            )

            ChurchGeoNameTranslationCatalog(json).build(
                candidates,
                resources,
                officialTranslations = mapOf(
                    "香貫" to mapOf("en" to "Kanuki"),
                    "上香貫" to mapOf("en" to "Kamikanuki", "ko" to "상향관"),
                    "東京都" to mapOf("en" to "Tokyo", "ko" to "도쿄"),
                ),
            )

            val entries = json.decodeFromString<List<ChurchGeoNameTranslation>>(
                Files.readString(resources.resolve("geonames/church-ja-all.json")),
            )
            assertEquals("가미카누키", entries.single { it.ja == "上香貫" }.translations["ko"])
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
