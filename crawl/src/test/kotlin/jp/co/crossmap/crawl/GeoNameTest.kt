package jp.co.crossmap.crawl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoNameTest {
    private val geoName = GeoName()

    @Test
    fun cleansReviewedChurchNamesKatakanaOnlyAliasesAndAddressBlocks() {
        val cleaner = JapaneseGeoNameCleaner(setOf("日本福音ルーテル博多教会"))
        val values = listOf(
            "日本福音ルーテル博多教会",
            "マリ",
            "ダイヤランド",
            "一丁目",
            "１２丁目",
            "ユーカリが丘",
            "東京",
        )

        val report = cleaner.report(values)

        assertEquals(JapaneseGeoNameRejectionReason.REVIEWED_CHURCH_NAME, cleaner.rejectionReason(values[0]))
        assertEquals(JapaneseGeoNameRejectionReason.KATAKANA_ONLY, cleaner.rejectionReason("マリ"))
        assertEquals(JapaneseGeoNameRejectionReason.ADDRESS_BLOCK, cleaner.rejectionReason("一丁目"))
        assertTrue(cleaner.isUsable("ユーカリが丘"))
        assertTrue(cleaner.isUsable("東京"))
        assertEquals(7, report.inputNames)
        assertEquals(2, report.retainedNames)
        assertEquals(1, report.reviewedChurchNamesRemoved)
        assertEquals(2, report.katakanaOnlyNamesRemoved)
        assertEquals(2, report.addressBlocksRemoved)
    }

    @Test
    fun removesUnsafeAliasesWhileBuildingTheJapanLexicon() {
        val root = Files.createTempDirectory("crossmap-clean-geoname")
        try {
            val source = root.resolve("JP.txt")
            val japan = root.resolve("japan/geonames.csv")
            val lexicon = root.resolve("japan/japanese-to-english.json")
            Files.writeString(
                source,
                listOf(
                    row("1", "ナザレン教会", "Nazarene Church", "", "JP", "0"),
                    row("2", "マリ", "Mari", "", "JP", "0"),
                    row("3", "一丁目", "Itchome", "", "JP", "0"),
                    row("4", "ユーカリが丘", "Yukarigaoka", "", "JP", "1000"),
                ).joinToString("\n") { it.replace(',', '\t') },
            )
            val cleaned = GeoName(cleaner = JapaneseGeoNameCleaner(setOf("ナザレン教会")))

            cleaned.buildJapanCache(source, japan, lexicon)
            val names = cleaned.readLexicon(lexicon)

            assertEquals(mapOf("ユーカリが丘" to "Yukarigaoka"), names)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun filtersJapanRowsAndBuildsJapaneseToEnglishLexicon() {
        val root = Files.createTempDirectory("crossmap-geoname")
        try {
            val world = root.resolve("world.csv")
            val japan = root.resolve("japan/geonames.csv")
            val lexicon = root.resolve("japan/japanese-to-english.json")
            Files.writeString(
                world,
                listOf(
                    HEADER,
                    row("1853909", "大阪市", "Osaka", "大阪;大阪市;Ōsaka", "JP", population = "2753862"),
                    row("1850147", "Tokyo", "Tokyo", "", "JP", population = "14047594"),
                    row("9990001", "衣笠病院", "Kinugasa Hospital", "病院", "JP", "100", "S", "HSP"),
                    row("5128581", "New York City", "New York City", "ニューヨーク", "US", population = "8804190"),
                ).joinToString("\n"),
            )

            val report = geoName.buildJapanCache(world, japan, lexicon)
            val names = geoName.readLexicon(lexicon)

            assertEquals(4, report.sourceRowsRead)
            assertEquals(3, report.japanRowsRetained)
            assertEquals("Osaka", names["大阪"])
            assertEquals("Osaka", names["大阪市"])
            assertNull(names["ニューヨーク"])
            assertNull(names["病院"])
            assertTrue(Files.readAllLines(japan).any { it.contains("Tokyo") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadsAndExtractsOfficialDumpOnlyWhenMissing() {
        val root = Files.createTempDirectory("crossmap-geoname-download")
        try {
            val japanText = root.resolve("japan/JP.txt")
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("JP.txt"))
                    zip.write(row("1863967", "神戸市", "Kobe", "神戸;神戸市", "JP", "1525152").replace(',', '\t').toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()
            var downloads = 0

            assertTrue(geoName.ensureOfficialJapanDump(japanText) { downloads++; ByteArrayInputStream(archive) })
            assertEquals(1, downloads)
            assertTrue(Files.readString(japanText).contains("神戸市"))
            assertFalse(geoName.ensureOfficialJapanDump(japanText) { error("Existing JP.txt must be reused") })
            assertEquals(1, downloads)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadsAndJoinsOfficialLanguageTaggedAlternateNamesByGeonameId() {
        val root = Files.createTempDirectory("crossmap-geoname-alternate-names")
        try {
            val japanText = root.resolve("japan/JP.txt")
            val alternateText = root.resolve("japan/alternatenames/JP.txt")
            val multilingual = root.resolve("japan/church-name-multilingual-lexicon.json")
            Files.createDirectories(japanText.parent)
            Files.writeString(
                japanText,
                row("1853909", "大阪市", "Osaka", "大阪;大阪市", "JP", "2753862").replace(',', '\t'),
            )
            val alternateRows = listOf(
                "1\t1853909\ten\tOsaka\t1\t0\t0\t0\t\t",
                "2\t1853909\tko\t오사카시\t1\t0\t0\t0\t\t",
                "3\t1853909\tpt\tOsaka\t1\t0\t0\t0\t\t",
                "4\t1853909\tid\tOsaka\t1\t0\t0\t0\t\t",
            ).joinToString("\n")
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("JP.txt"))
                    zip.write(alternateRows.toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()

            assertTrue(
                geoName.ensureOfficialJapanAlternateNamesDump(alternateText) { ByteArrayInputStream(archive) }
            )
            val report = geoName.buildJapanAlternateNamesCache(japanText, alternateText, multilingual)
            val names = geoName.readMultilingualLexicon(multilingual)

            assertEquals(4, report.alternateRowsRead)
            assertEquals("Osaka", names.getValue("大阪").getValue("en"))
            assertEquals("오사카시", names.getValue("大阪市").getValue("ko"))
            assertEquals("Osaka", names.getValue("大阪").getValue("pt"))
            assertEquals("Osaka", names.getValue("大阪").getValue("id"))
            assertFalse(
                geoName.ensureOfficialJapanAlternateNamesDump(alternateText) {
                    error("Existing alternate names JP.txt must be reused")
                }
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadsAndReadsJmaCityTranslationsWithFullAndShortMunicipalityNames() {
        val root = Files.createTempDirectory("crossmap-jma-city")
        try {
            val destination = root.resolve("resources/geonames/jma-city.json")
            val content = """
                {
                  "1020100": {
                    "japanese": "前橋市",
                    "english": "Maebashi City",
                    "korean": "마에바시 시",
                    "portuguese": "cidade de Maebashi",
                    "indonesian": "kota Maebashi"
                  }
                }
            """.trimIndent()
            var downloads = 0

            assertTrue(
                geoName.ensureJmaCityDictionary(destination) {
                    downloads++
                    ByteArrayInputStream(content.toByteArray())
                },
            )
            val names = geoName.readJmaMultilingualLexicon(destination)

            assertEquals("Maebashi City", names.getValue("前橋市").getValue("en"))
            assertEquals("마에바시", names.getValue("前橋").getValue("ko"))
            assertEquals("Maebashi", names.getValue("前橋").getValue("pt"))
            assertEquals("Maebashi", names.getValue("前橋").getValue("id"))
            assertFalse(
                geoName.ensureJmaCityDictionary(destination) {
                    error("Existing JMA city dictionary must be reused")
                },
            )
            assertEquals(1, downloads)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun jmaTranslationsOverrideMatchingGeoNamesAliasesWithoutDroppingOtherAliases() {
        val merged = geoName.mergeMultilingualLexicons(
            base = mapOf(
                "前橋市" to mapOf("en" to "Maebashi"),
                "雲内" to mapOf("en" to "Kumouchi"),
            ),
            additional = mapOf(
                "前橋市" to mapOf("en" to "Maebashi City", "ko" to "마에바시 시"),
            ),
        )

        assertEquals("Maebashi City", merged.getValue("前橋市").getValue("en"))
        assertEquals("마에바시 시", merged.getValue("前橋市").getValue("ko"))
        assertEquals("Kumouchi", merged.getValue("雲内").getValue("en"))
    }

    @Test
    fun detectsLongestNonOverlappingGeonameInRealChurchName() {
        val detected = geoName.detectAndTranslate(
            "日本基督教団 神戸雲内教会",
            mapOf("神戸" to "Kobe", "神戸市" to "Kobe", "雲内" to "Kumouchi"),
        )

        assertEquals(listOf("Kobe", "Kumouchi"), detected.map(DetectedGeoName::englishName))
        assertEquals("神戸雲内", detected.joinToString("") { it.japaneseName })
    }

    @Test
    fun parsesQuotedKaggleCsvColumnsWithoutSplittingEmbeddedComma() {
        val fields = geoName.parseCsvLine(
            """1853909,"大阪市, 大阪府",Osaka,"大阪;大阪市",34.6937,135.5023,P,PPLA,JP,,32,,,,2753862,10,15,Asia/Tokyo,2024-01-01""",
        )

        assertEquals("大阪市, 大阪府", fields[1])
        assertEquals("大阪;大阪市", fields[3])
        assertEquals("JP", fields[8])
    }

    @Test
    fun readsOfficialHeaderlessTabDelimitedJapanDump() {
        val root = Files.createTempDirectory("crossmap-geoname-jp")
        try {
            val source = root.resolve("JP.txt")
            val japan = root.resolve("japan/geonames.csv")
            val lexicon = root.resolve("japan/japanese-to-english.json")
            Files.writeString(
                source,
                listOf(
                    row("1863967", "神戸市", "Kobe", "神戸;神戸市", "JP", "1525152").replace(',', '\t'),
                    row("1853909", "大阪市", "Osaka", "大阪;大阪市", "JP", "2753862").replace(',', '\t'),
                ).joinToString("\n"),
            )

            val report = geoName.buildJapanCache(source, japan, lexicon)
            val names = geoName.readLexicon(lexicon)

            assertEquals(2, report.sourceRowsRead)
            assertEquals(2, report.japanRowsRetained)
            assertEquals("Kobe", names["神戸"])
            assertEquals("Osaka", names["大阪市"])
            assertEquals(2, Files.readAllLines(japan).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenJapaneseNameHasNoAuthoritativeEnglishForm() {
        assertNull(geoName.translateJapaneseName("聖和", mapOf("神戸" to "Kobe")))
    }

    @Test
    fun detectionOnlyCatalogNameIsNotInventedAsAnEnglishTranslation() {
        val analysis = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = mapOf("神戸" to "Kobe"),
            detectionOnlyGeonames = setOf("雲内"),
        ).analyze(
            ChurchEnglishNameInput(
                id = "google:10003468413261460406",
                name = "神戸雲内教会",
                address = "〒657-0051 兵庫県神戸市灘区八幡町１丁目６−９",
                location = GeoPoint(34.719125, 135.237793),
                websiteUrl = "http://blog.goo.ne.jp/kumochi/",
            ),
        )!!

        assertEquals("Kobe", analysis.components.single { it.japanese == "神戸" }.english)
        assertNull(analysis.components.single { it.japanese == "雲内" }.english)
    }

    private fun row(
        id: String,
        name: String,
        asciiName: String,
        alternateNames: String,
        countryCode: String,
        population: String,
        featureClass: String = "P",
        featureCode: String = "PPL",
    ): String = listOf(
        id,
        name,
        asciiName,
        alternateNames,
        "35.0",
        "135.0",
        featureClass,
        featureCode,
        countryCode,
        "",
        "00",
        "",
        "",
        "",
        population,
        "10",
        "15",
        "Asia/Tokyo",
        "2024-01-01",
    ).joinToString(",")

    private companion object {
        const val HEADER =
            "geonameid,name,asciiname,alternatenames,latitude,longitude,feature_class,feature_code,country_code,cc2,admin1_code,admin2_code,admin3_code,admin4_code,population,elevation,dem,timezone,modification_date"
    }
}
