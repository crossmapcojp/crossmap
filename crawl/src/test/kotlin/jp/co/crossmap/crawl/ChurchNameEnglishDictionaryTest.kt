package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChurchNameEnglishDictionaryTest {
    @Test
    fun multilingualConceptDictionariesCoverEveryJapaneseEnglishConcept() {
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val dictionaryDirectory = root.resolve("resources/dictionary")
        val englishKeys = readDictionary(dictionaryDirectory.resolve("ja-en-concept-dictionary.csv")).keys

        listOf("ko", "pt", "es", "id").forEach { language ->
            val entries = readDictionary(dictionaryDirectory.resolve("ja-$language-concept-dictionary.csv"))
            assertEquals(
                emptySet(),
                englishKeys - entries.keys,
                "ja-$language-concept-dictionary.csv is missing Japanese concepts",
            )
            assertTrue(entries.values.none(String::isBlank), "ja-$language concept translations must not be blank")
        }
    }

    @Test
    fun committedDictionariesLoadWithoutDuplicateEntries() {
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val dictionaries = ChurchNameEnglishDictionary.load(root.resolve("resources"))
        assertTrue("教会" !in dictionaries.concepts, "教会 is a structural congregation word, not a concept")

        assertEquals("Seiyaku", dictionaries.concepts["聖約"])
        assertEquals("Shinsho", dictionaries.concepts["神召"])
        assertEquals("Shonan", dictionaries.geonames["湘南"])
        assertEquals(
            "일본기독교단",
            dictionaries.multilingual.translate(
                "日本基督教団",
                "ja",
                "ko",
                ChurchNameDictionaryCategory.CONCEPT,
            ),
        )
        assertEquals(
            "日本基督教団",
            dictionaries.multilingual.translate(
                "일본기독교단",
                "ko",
                "ja",
                ChurchNameDictionaryCategory.CONCEPT,
            ),
        )
        val congregationTerms = CongregationTermDictionary.load(root.resolve("resources"))
        assertEquals("教会", congregationTerms.translations("pt", "ja")["Igreja"])
        assertEquals("교회", congregationTerms.translations("ja", "ko")["教会"])
    }

    @Test
    fun latinAbbreviationsComeFromRealDenominationIdsAndAliases() {
        val denominations = listOf(
            Denomination("JELC", "日本福音ルーテル教会"),
            Denomination("JEC", "日本福音教会", aliases = listOf("JEC")),
            Denomination("HCC", "ハレルヤコミュニティチャーチ", aliases = listOf("HCC")),
            Denomination("XLSX_123", "架空の教団"),
        )

        assertEquals(setOf("JELC", "JEC", "HCC"), denominations.knownLatinAbbreviations())
    }

    @Test
    fun duplicateDictionaryWordIsRejectedEvenWhenTranslationIsIdentical() {
        val csv = Files.createTempFile("crossmap-duplicate-dictionary", ".csv")
        Files.writeString(csv, "聖和,Seiwa\n聖和,Seiwa\n")

        val error = assertFailsWith<IllegalArgumentException> { ChurchNameEnglishDictionary.read(csv) }

        assertTrue(error.message.orEmpty().contains("Duplicate dictionary entry for 聖和"), error.message)
    }

    @Test
    fun reviewedEntriesOverrideGenericAnalysisForRealChurchWords() {
        val dictionaries = ChurchNameEnglishDictionaries(
            concepts = mapOf("聖約" to "Seiyaku"),
            geonames = mapOf("湘南" to "Shonan"),
        )
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = ChurchNameEnglishLexicon.geonames + dictionaries.geonames,
            concepts = dictionaries.concepts,
            dictionaryEntries = dictionaries.entries,
        )

        assertEquals("Seiyaku Church", analyzer.analyze(church("聖約教会"))?.compose())
        assertEquals("Shonan Church", analyzer.analyze(church("湘南教会"))?.compose())
    }

    @Test
    fun prefixStyleRealChurchNameIsComposedWithoutWholeNameLlm() {
        val analysis = ChurchNameComponentAnalyzer(emptyList()).analyze(church("チャペル・ノア"))

        assertEquals("Noa Chapel", analysis?.compose())
    }

    @Test
    fun attachedNurseryIsNotPartOfRealChurchEnglishName() {
        val analysis = ChurchNameComponentAnalyzer(emptyList()).analyze(church("館山教会&amp;附属保育園"))

        assertEquals("Tateyama Church", analysis?.compose())
    }

    @Test
    fun infixChurchTypeInRealNameDoesNotRequireWholeNameLlm() {
        val analyzer = ChurchNameComponentAnalyzer(
            emptyList(),
            concepts = mapOf("ライフリバー" to "Life River"),
        )
        val english = analyzer.analyze(church("ライフリバーチャーチ北遠"))?.compose().orEmpty()

        assertTrue(english.startsWith("Life River "), english)
        assertTrue(english.endsWith(" Church"), english)
    }

    @Test
    fun denominationPrefixEndingInChurchIsRemovedBeforeLocalNameAnalysis() {
        val church = church("セブンスデー・アドベンチスト教会 多摩永山").copy(denominationId = "SDA_JP")
        val analyzer = ChurchNameComponentAnalyzer(
            listOf(Denomination("SDA_JP", "セブンスデー・アドベンチスト教会")),
        )
        val english = analyzer.analyze(church)?.compose().orEmpty()

        assertTrue(english.endsWith(" Church"), english)
        assertTrue("Adobenchisuto" !in english, english)
    }

    @Test
    fun compositeSpecialGeonameWinsBeforeEmbeddedShortAliases() {
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = mapOf(
                "今治旭方" to "Imabari Hikata",
                "今治" to "Imabari",
                "旭" to "Asahi",
            ),
            dictionaryEntries = setOf("今治旭方"),
        )
        val analysis = analyzer.analyze(church("今治旭方教会"))

        assertEquals("Imabari Hikata Church", analysis?.compose())
        assertEquals(listOf("今治旭方"), analysis?.components?.map { it.japanese })
    }

    @Test
    fun geonameAndMultipleConceptPartsComposeInSourceOrder() {
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = mapOf("静岡" to "Shizuoka"),
            concepts = mapOf("サミル" to "Samiru"),
            dictionaryEntries = setOf("サミル"),
        )
        val analysis = analyzer.analyze(church("静岡サミル聖書教会"))

        assertEquals("Shizuoka Samiru Bible Church", analysis?.compose())
        assertEquals(listOf("静岡", "サミル", "聖書"), analysis?.components?.map { it.japanese })
    }

    @Test
    fun combinedConceptBeforeGeonameComposesInSourceOrder() {
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = mapOf("葛飾" to "Katsushika"),
            concepts = mapOf("独立新生" to "Dokuritsu Shinsei"),
            dictionaryEntries = setOf("独立新生"),
        )
        val analysis = analyzer.analyze(church("独立新生 葛飾教会"))

        assertEquals("Dokuritsu Shinsei Katsushika Church", analysis?.compose())
        assertEquals(listOf("独立新生", "葛飾"), analysis?.components?.map { it.japanese })
    }

    @Test
    fun longestOverlapDistinguishesGloryConceptFromSakaeGeoname() {
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val dictionaries = ChurchNameEnglishDictionary.load(root.resolve("resources"))
        val rule = StructuredChurchNameRule(
            denominations = listOf(
                Denomination("JELC", "日本福音ルーテル教会", listOf("日本福音ルーテル")),
            ),
            geonames = ChurchNameEnglishLexicon.geonames + dictionaries.geonames,
            concepts = dictionaries.concepts,
        )

        assertEquals("Glory Christ Church", rule.translate(church("栄光キリスト教会"))?.englishName)
        assertEquals(
            "JELC Glory Church",
            rule.translate(church("日本福音ルーテル栄光教会").copy(denominationId = "JELC"))?.englishName,
        )
        assertEquals("Sakae Bible Church", rule.translate(church("栄聖書教会"))?.englishName)
    }

    @Test
    fun todoMultiPartJapaneseNamesComposeToExpectedEnglishNames() {
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val dictionaries = ChurchNameEnglishDictionary.load(root.resolve("resources"))
        val analyzer = ChurchNameComponentAnalyzer(
            denominations = emptyList(),
            geonames = ChurchNameEnglishLexicon.geonames + dictionaries.geonames,
            concepts = dictionaries.concepts,
            dictionaryEntries = dictionaries.entries,
        )
        val decomposer = ChurchNameDecomposer()
        val examples = mapOf(
            "マリ キリスト教会 (마리 그리스도교회)" to "Mari Christ Church",
            "동경지구촌교회 (東京ジグチョン教会)" to "Tokyo Jiguchon Church",
            "東京日暮里国際教会(六本木会堂)" to "Tokyo Nippori International Church Roppongi Chapel",
            "改革派国際基督長老教会(西東京礼拝堂)" to "Reformed International Christ Presbyterian Church Nishitokyo Chapel",
            "ザ・クラウドチャーチ(キリスト教会)" to "The Cloud Church",
            "ベイサイドチャーチ（キリスト教会）" to "Bay Side Church",
            "別府EMC(別府地の果て宣教教会）" to "Beppu End Of The Earth Mission Church",
            "東京EMC 東京地の果て宣教教会" to "Tokyo End Of The Earth Mission Church",
            "寝屋川福音キリスト教会 (ファミリーチャーチねや川)" to "Neyagawa Gospel Christ Church",
        )

        examples.forEach { (source, expected) ->
            val japanese = requireNotNull(decomposer.decompose(source).japaneseName)
            assertEquals(expected, analyzer.analyze(church(japanese))?.compose(), source)
        }
    }

    private fun church(name: String) = ChurchEnglishNameInput(
        id = "real:$name",
        name = name,
        denominationId = "INDEPENDENT_CHURCH",
        address = "神奈川県藤沢市",
        location = GeoPoint(35.339, 139.491),
        websiteUrl = "",
    )

    private fun readDictionary(path: Path): Map<String, String> {
        assertTrue(Files.isRegularFile(path), "Missing dictionary: $path")
        return Files.readAllLines(path)
            .filter(String::isNotBlank)
            .associate { line ->
                val separator = line.indexOf(',')
                assertTrue(separator > 0, "Invalid dictionary row in $path: $line")
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
    }
}
