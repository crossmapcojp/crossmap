package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultilingualChurchNameLocalizerTest {
    private val resourcesRoot = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        .resolve("resources")
    private val dictionaries = ChurchNameEnglishDictionary.load(resourcesRoot)
    private val congregationTerms = CongregationTermDictionary.load(resourcesRoot)

    @Test
    fun catholicChurchNameOmitsRedundantJapanQualifierInEveryLanguage() {
        val catholic = Denomination(
            id = "CATHOLIC_JP",
            name = "カトリック中央協議会",
            aliases = listOf("カトリック教会"),
        )
        val denominationNames = mapOf(
            Language.JAPANESE to mapOf("CATHOLIC_JP" to "カトリック"),
            Language.ENGLISH to mapOf("CATHOLIC_JP" to "Catholic Church in Japan"),
            Language.KOREAN to mapOf("CATHOLIC_JP" to "일본 가톨릭교회"),
            Language.PORTUGUESE to mapOf("CATHOLIC_JP" to "Igreja Católica no Japão"),
            Language.INDONESIAN to mapOf("CATHOLIC_JP" to "Gereja Katolik di Jepang"),
        )
        val result = localizer(
            geonames = mapOf("萩" to "Hagi"),
            denominations = listOf(catholic),
            denominationNames = denominationNames,
            multilingualGeonames = mapOf("萩" to mapOf("ko" to "하기", "pt" to "Hagi", "id" to "Hagi")),
        ).localize("萩カトリック教会")

        assertEquals("萩カトリック教会", result.localizedNames.single { it.languageCode == "ja" }.name)
        assertEquals("Hagi Catholic Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("하기 가톨릭교회", result.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals("Igreja Católica Hagi", result.localizedNames.single { it.languageCode == "pt" }.name)
        assertEquals("Gereja Katolik Hagi", result.localizedNames.single { it.languageCode == "id" }.name)
    }

    @Test
    fun composesUccjAkabaneIntoEnglishAndKoreanProgrammatically() {
        val localizer = localizer(
            geonames = mapOf("赤羽" to "Akabane"),
            denominations = listOf(Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))),
        )

        val result = localizer.localize("日本基督教団赤羽教会")

        assertEquals("日本基督教団赤羽教会", result.japaneseName)
        assertEquals("UCCJ Akabane Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("일본기독교단 아카바네 교회", result.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals(setOf("ja", "en", "ko", "pt", "id"), result.localizedNames.map { it.languageCode }.toSet())
        assertTrue(result.components.all { it.sourceLanguage == "ja" })
        assertEquals(
            listOf(
                MultilingualNameComponentRole.DENOMINATION,
                MultilingualNameComponentRole.GEONAME,
                MultilingualNameComponentRole.CONGREGATION,
            ),
            result.components.map(MultilingualNameComponent::role),
        )
    }

    @Test
    fun retainsParentheticalKanaSubstitutionAsAnAdditionalJapaneseSearchName() {
        val result = localizer(
            geonames = mapOf("香貫" to "Kanuki"),
            denominations = listOf(Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))),
        ).localize("日本基督(キリスト)教団 香貫教会")

        assertEquals("日本基督教団 香貫教会", result.japaneseName)
        assertTrue(result.localizedNames.any { it.languageCode == "ja" && it.name == "キリスト教団 香貫教会" })
        assertTrue(result.localizedNames.single { it.languageCode == "ko" }.name.none { it in 'A'..'Z' || it in 'a'..'z' })
    }

    @Test
    fun translatesPortugueseGodIsLoveNameAsAWholePhrase() {
        val result = localizer(geonames = emptyMap())
            .localize("IGREJA PENTECOSTAL DEUS É AMOR", evidencedLanguages = listOf("pt"))

        assertEquals("神は愛なりペンテコステ教会", result.japaneseName)
        assertEquals(
            "하나님은 사랑이시다 오순절 교회",
            result.localizedNames.single { it.languageCode == "ko" }.name,
        )
        assertTrue(result.localizedNames.single { it.languageCode == "ja" }.name.none { it in 'A'..'Z' })

        val decomposedAccent = localizer(geonames = emptyMap())
            .localize("Igreja Deus e\u0301 Amor", evidencedLanguages = listOf("pt"))
        assertEquals("神は愛なり教会", decomposedAccent.japaneseName)
        assertEquals(
            "하나님은 사랑이시다 교회",
            decomposedAccent.localizedNames.single { it.languageCode == "ko" }.name,
        )

        val decomposedAccentWholeName = localizer(geonames = emptyMap())
            .localize("IGREJA PENTECOSTAL DEUS E\u0301 AMOR", evidencedLanguages = listOf("pt"))
        assertEquals("神は愛なりペンテコステ教会", decomposedAccentWholeName.japaneseName)
        assertEquals(
            listOf("神は愛なりペンテコステ教会"),
            decomposedAccentWholeName.localizedNames.filter { it.languageCode == "ja" }.map { it.name },
        )

        val withPortugueseGeoname = localizer(
            geonames = mapOf("名古屋" to "Nagoya"),
            multilingualGeonames = mapOf("名古屋" to mapOf("en" to "Nagoya", "pt" to "Nagoia")),
        )
        assertEquals(
            "名古屋神は愛なり教会",
            withPortugueseGeoname.localize("Igreja Deus é Amor Nagoia", listOf("pt")).japaneseName,
        )
        val withConnector = withPortugueseGeoname.localize(
            "Igreja Pentecostal Deus é Amor de Nagoya",
            listOf("pt"),
        )
        assertEquals(
            "名古屋神は愛なりペンテコステ教会",
            withConnector.japaneseName,
        )
        assertEquals(
            "하나님은 사랑이시다 오순절 교회 나고야",
            withConnector.localizedNames.single { it.languageCode == "ko" }.name,
        )
        assertEquals(
            listOf("名古屋神は愛なりペンテコステ教会"),
            withConnector.localizedNames.filter { it.languageCode == "ja" }.map { it.name },
        )
    }

    @Test
    fun usesConcreteDenominationCatalogNamesAndKeepsTraditionComponentsSeparate() {
        val denomination = Denomination(
            "JELC",
            "日本福音ルーテル教会",
            listOf("日本福音ルーテル"),
        )
        val catalogNames = DenominationNameCatalogFiles.load(resourcesRoot)
        val result = localizer(
            geonames = mapOf("東京" to "Tokyo"),
            denominations = listOf(denomination),
            denominationNames = catalogNames,
            multilingualGeonames = mapOf(
                "東京" to mapOf("en" to "Tokyo", "ko" to "도쿄", "pt" to "Tóquio", "id" to "Tokyo"),
            ),
        ).localize("日本福音ルーテル東京教会")

        assertEquals("JELC Tokyo Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("일본 복음 루터 도쿄 교회", result.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals(MultilingualNameComponentRole.DENOMINATION, result.components.first().role)

        val traditionOnly = localizer(
            geonames = mapOf("東京" to "Tokyo"),
            denominationNames = catalogNames,
        ).localize("東京ルーテル教会")
        assertEquals(MultilingualNameComponentRole.TRADITION, traditionOnly.components[1].role)
        assertEquals("Tokyo Lutheran Church", traditionOnly.localizedNames.single { it.languageCode == "en" }.name)
    }

    @Test
    fun internalDenominationSentinelsCannotEnterLocalizedChurchNames() {
        val sentinelCatalog = Language.entries.associateWith {
            mapOf("INDEPENDENT_CHURCH" to "INDEPENDENT_CHURCH")
        }
        val result = localizer(
            geonames = mapOf("町田" to "Machida"),
            denominations = listOf(Denomination("INDEPENDENT_CHURCH", "単立教会", listOf("単立"))),
            denominationNames = sentinelCatalog,
        ).localize("単立町田バプテスト教会")

        assertTrue(result.localizedNames.none { "INDEPENDENT_CHURCH" in it.name })
        assertTrue(result.components.none { it.role == MultilingualNameComponentRole.DENOMINATION })
    }

    @Test
    fun outsiderMovementLabelIsNotPrefixedToARealChurchName() {
        val result = localizer(
            geonames = mapOf("御茶の水" to "Ochanomizu"),
            denominations = listOf(
                Denomination(
                    id = "CHURCHES_OF_CHRIST",
                    name = "キリストの教会（無楽器派）",
                    useAsChurchNamePrefix = false,
                ),
            ),
        ).localize("キリストの教会（無楽器派） 御茶の水キリストの教会")

        assertEquals("御茶の水キリストの教会", result.japaneseName)
        assertTrue(result.localizedNames.none { "CHURCHES_OF_CHRIST" in it.name })
        assertTrue(result.components.none { it.role == MultilingualNameComponentRole.DENOMINATION })
    }

    @Test
    fun reviewedConceptAndGeonameDictionariesOutrankBroadGeoNamesAliases() {
        val result = localizer(
            geonames = mapOf("大宮" to "Omiya-ku", "共立" to "Kyoritsu Station"),
            denominations = listOf(Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))),
        ).localize("日本基督教団大宮共立教会")

        assertEquals("UCCJ Omiya Kyoritsu Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("일본기독교단 오미야 교리쓰 교회", result.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals(
            listOf(
                MultilingualNameComponentRole.DENOMINATION,
                MultilingualNameComponentRole.GEONAME,
                MultilingualNameComponentRole.CONCEPT,
                MultilingualNameComponentRole.CONGREGATION,
            ),
            result.components.map(MultilingualNameComponent::role),
        )
    }

    @Test
    fun improvesRealPortugueseGoogleTitlesWithReviewedPhrasesGeonamesAndAcronyms() {
        val localizer = localizer(
            geonames = mapOf(
                "大泉" to "Oizumi",
                "浜松" to "Hamamatsu",
                "安城" to "Anjo",
            ),
        )
        val examples = mapOf(
            "A.D.C.D. Assembléia de Deus Central do Dourado - Projeto Vinho Novo" to
                "ADCDアッセンブレイア・デ・デウスセントラルドドウラードプロジェトヴィーニョ・ノーヴォ",
            "ADOMJ Oizumi" to "ADOMJ大泉",
            "ADVM Assembleia de Deus Visão Missionaria Hamamatsu" to
                "浜松ADVMアッセンブレイア・デ・デウスヴィザォン・ミッショナリア",
            "ASSEMBLEIA DE DEUS BELÉM ANJO-SHI" to
                "安城アッセンブレイア・デ・デウスベレン",
        )

        examples.forEach { (title, japanese) ->
            val result = localizer.localize(title)
            assertEquals(japanese, result.japaneseName, title)
            assertTrue(result.localizedNames.any { it.languageCode == "pt" && it.name == title }, title)
        }

        val composed = localizer.localize(
            "A.D.C.D. Assembléia de Deus Central do Dourado - Projeto Vinho Novo",
        )
        assertEquals(
            "ADCD Assemblies of God Central Do Dourado Project Vinho Novo",
            composed.localizedNames.single { it.languageCode == "en" }.name,
        )
        assertEquals(setOf("ja", "en", "ko", "pt", "id"), composed.localizedNames.map { it.languageCode }.toSet())

        val missionary = localizer.localize("ADVM Assembleia de Deus Visão Missionaria Hamamatsu")
        assertEquals(
            listOf("ADVM", "Assembleia de Deus", "Visão Missionaria", "Hamamatsu"),
            missionary.components.map { it.source },
        )
        assertTrue(missionary.components.all { it.sourceLanguage == "pt" })
    }

    @Test
    fun recognizesRealPortugueseMisspellingWithoutFallingBackToWordByWordTransliteration() {
        val result = localizer(emptyMap()).localize("Assembreia de Deus Ministerio da restauracao")

        assertEquals(
            "アッセンブレイア・デ・デウス回復ミニストリー",
            result.japaneseName,
        )
        assertTrue(result.localizedNames.any { it.languageCode == "pt" })
        assertEquals(
            "Assemblies of God Restoration Ministry",
            result.localizedNames.single { it.languageCode == "en" }.name,
        )
    }

    @Test
    fun preservesRealLatinChurchNamePrefixWhenJapaneseSuffixOnlyMeansChurch() {
        val result = localizer(emptyMap()).localize("NEW GRACE 教会")

        assertEquals("ニューグレイス教会", result.japaneseName)
        assertEquals("New Grace Church", result.latinName)
        assertEquals("New Grace Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals(ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_CONGREGATION_SUFFIX, result.pattern)
    }

    @Test
    fun composesJmaMunicipalityTranslationsIntoAllSupportedChurchNames() {
        val result = localizer(
            geonames = mapOf("前橋" to "Maebashi"),
            multilingualGeonames = mapOf(
                "前橋" to mapOf("en" to "Maebashi", "ko" to "마에바시", "pt" to "Maebashi", "id" to "Maebashi"),
            ),
        ).localize("前橋教会")

        assertEquals("Maebashi Church", result.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("마에바시 교회", result.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals("Igreja Maebashi", result.localizedNames.single { it.languageCode == "pt" }.name)
        assertEquals("Gereja Maebashi", result.localizedNames.single { it.languageCode == "id" }.name)
    }

    @Test
    fun usesEvidencedPortugueseForShortTitlePartsInsteadOfStatisticalFalsePositive() {
        val result = localizer(
            geonames = mapOf("四日市" to "Yokkaichi"),
        ).localize("Avivamento Japão Yokkaichi", listOf("pt"))

        assertEquals("四日市日本リバイバル", result.japaneseName)
        assertEquals("Japan Revival Yokkaichi", result.localizedNames.single { it.languageCode == "en" }.name)
        assertTrue(result.components.all { it.sourceLanguage == "pt" })
    }

    @Test
    fun reviewedWholePhrasesReplaceRepeatedPoorPortugueseTransliterationPatterns() {
        val universal = localizer(emptyMap()).localize("Igreja Universal do Reino de Deus", listOf("pt"))
        val revival = localizer(
            geonames = mapOf("越前" to "Echizen"),
        ).localize("Igreja Evangélica Avivamento Japão - Echizen-shi", listOf("pt"))

        assertEquals("ユニバーサル神の王国教会", universal.japaneseName)
        assertEquals(
            "Universal Church of the Kingdom of God",
            universal.localizedNames.single { it.languageCode == "en" }.name,
        )
        assertEquals("越前福音派日本リバイバル教会", revival.japaneseName)
        assertEquals(
            "Church Evangelical Japan Revival Echizen",
            revival.localizedNames.single { it.languageCode == "en" }.name,
        )
    }

    @Test
    fun reviewedEnglishAndSpanishPhrasesAvoidIcuWholeWordArtifacts() {
        val localizer = localizer(
            geonames = mapOf("浜松" to "Hamamatsu", "山梨" to "Yamanashi"),
        )

        val snowball = localizer.localize("Bola de Neve Church, Hamamatsu-Shi", listOf("en", "pt"))
        val christComes = localizer.localize("Iglesia Cristo Viene Yamanashi", listOf("es"))
        val movement = localizer.localize("Movimiento Misionero Mundial", listOf("es"))

        assertEquals("浜松ボラ・デ・ネーヴェ教会", snowball.japaneseName)
        assertTrue(snowball.components.all { it.sourceLanguage == "pt" })
        assertEquals("山梨再臨キリスト教会", christComes.japaneseName)
        assertEquals(
            setOf("ja", "en", "ko", "pt", "id", "es"),
            christComes.localizedNames.map { it.languageCode }.toSet(),
        )
        assertEquals(
            emptySet(),
            christComes.localizedNames.groupingBy { it.languageCode }.eachCount()
                .filterValues { it > 1 }
                .keys,
        )
        assertEquals("Church Christ Is Coming Yamanashi", christComes.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("世界宣教運動", movement.japaneseName)
        assertEquals("Worldwide Missionary Movement", movement.localizedNames.single { it.languageCode == "en" }.name)
    }

    @Test
    fun jbbfMembershipDoesNotCollapseChurchNamesAndAddressDisambiguatesShimizu() {
        val localizer = localizer(
            geonames = mapOf(
                "清水" to "Shimizumachi",
                "千本浜" to "Senbon Hama",
            ),
            multilingualGeonames = mapOf(
                "清水" to mapOf("en" to "Shimizumachi"),
                "静岡市清水区" to mapOf("en" to "Shimizu-ku"),
            ),
            denominations = listOf(
                Denomination(
                    id = "JBBF",
                    name = "日本バプテスト・バイブル・フェローシップ",
                    aliases = listOf("JBBF"),
                    officialWebsite = "https://jbbf.or.jp/",
                ),
            ),
        )

        val shimizu = localizer.localize(
            "清水聖書バプテスト教会",
            addressContext = "〒424-0832 静岡県静岡市清水区入江南町７－１１",
        )
        val senbonHama = localizer.localize(
            "千本浜聖書バプテスト教会",
            addressContext = "〒410-0866 静岡県沼津市市道町４ー１３",
        )

        assertEquals("Shimizu Bible Baptist Church", shimizu.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("Senbon Hama Bible Baptist Church", senbonHama.localizedNames.single { it.languageCode == "en" }.name)
        assertTrue(shimizu.components.none { it.role == MultilingualNameComponentRole.DENOMINATION })
        assertTrue(senbonHama.components.none { it.role == MultilingualNameComponentRole.DENOMINATION })
    }

    private fun localizer(
        geonames: Map<String, String>,
        denominations: List<Denomination> = emptyList(),
        denominationNames: Map<Language, Map<String, String>> = emptyMap(),
        multilingualGeonames: Map<String, Map<String, String>> = emptyMap(),
    ) = MultilingualChurchNameLocalizer(
        dictionaries = dictionaries,
        congregationTerms = congregationTerms,
        denominations = denominations,
        denominationNames = denominationNames,
        geonames = geonames,
        multilingualGeonames = multilingualGeonames,
    )
}
