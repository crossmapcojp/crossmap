package jp.co.crossmap.crawl

import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChurchNameDecomposerTest {
    private val decomposer = ChurchNameDecomposer(languageIdentifier = { value ->
        when {
            value.startsWith("Gereja ") -> "id"
            value.startsWith("Igreja ") -> "pt"
            else -> "en"
        }
    })

    @Test
    fun detectsFukuokaOhoriParkChurchAsJapanese() {
        val result = ChurchNameDecomposer().decompose("福岡大濠公園教会")

        assertEquals("福岡大濠公園教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(listOf(LocalizedName("ja", "福岡大濠公園教会")), result.localizedNames)
    }

    @Test
    fun cybozuShortTextProfilesDetectRealLatinChurchNameLanguages() {
        val decomposer = ChurchNameDecomposer()
        val examples = mapOf(
            "His Presence Church" to "en",
            "Igreja Evangélica das Nações" to "pt",
            "Gereja Interdenominasi Injili Indonesia" to "id",
        )

        examples.forEach { (name, language) ->
            assertEquals(language, decomposer.decompose(name).localizedNames.first { it.name == name }.languageCode, name)
        }
    }

    @Test
    fun localizedNamesRetainCanonicalJapaneseAndEnglishNames() {
        val result = decomposer.decompose("Just Church（ジャスト・チャーチ）")

        assertEquals(
            listOf(
                LocalizedName("ja", "ジャスト・チャーチ"),
                LocalizedName("en", "Just Church"),
            ),
            result.localizedNames,
        )
    }

    @Test
    fun extractsLatinAndJapaneseNamesFromParentheticalAliases() {
        val examples = mapOf(
            "Just Church（ジャスト・チャーチ）" to ("ジャスト・チャーチ" to "Just Church"),
            "IEQ Chuo Gospel Church (中央フォースクエア福音教会）" to
                ("中央フォースクエア福音教会" to "IEQ Chuo Gospel Church"),
            "Gospel Life Church (ゴスペルライフチャーチ)" to
                ("ゴスペルライフチャーチ" to "Gospel Life Church"),
        )

        examples.forEach { (source, expected) ->
            val result = decomposer.decompose(source)
            assertEquals(expected.first, result.japaneseName, source)
            assertEquals(expected.second, result.latinName, source)
            assertEquals(ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_PARENTHETICAL, result.pattern, source)
        }
    }

    @Test
    fun removesLatinAbbreviationAndLocationAfterFullIndonesianName() {
        val source = "Gereja Interdenominasi Injili Indonesia (GIII) Oarai (大洗インドネシア福音教会 )"

        val result = decomposer.decompose(source)

        assertEquals("大洗インドネシア福音教会", result.japaneseName)
        assertEquals("Gereja Interdenominasi Injili Indonesia", result.latinName)
        assertEquals(
            listOf(
                LocalizedName("ja", "大洗インドネシア福音教会"),
                LocalizedName("id", "Gereja Interdenominasi Injili Indonesia"),
            ),
            result.localizedNames,
        )
    }


    @Test
    fun extractsLatinAndJapaneseNamesSeparatedByWhitespace() {
        val examples = mapOf(
            "Be One Hokusetsu ビーワン北摂キリスト教会" to ("ビーワン北摂キリスト教会" to "Be One Hokusetsu"),
            "Sakuragi Christian Center 桜木クリスチャンセンター" to ("桜木クリスチャンセンター" to "Sakuragi Christian Center"),
            "Lifehouse International Church Osaka ライフハウス大阪" to ("ライフハウス大阪" to "Lifehouse International Church Osaka"),
            "Calvary Baptist Church カルバリバプテスト教会" to ("カルバリバプテスト教会" to "Calvary Baptist Church"),
        )

        examples.forEach { (source, expected) ->
            val result = decomposer.decompose(source)
            assertEquals(expected.first, result.japaneseName, source)
            assertEquals(expected.second, result.latinName, source)
            assertEquals(ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_WHITESPACE_ALIAS, result.pattern, source)
        }
    }

    @Test
    fun treatsLatinNameFollowedOnlyByJapaneseCongregationWordAsOneComposedName() {
        val result = decomposer.decompose("NEW GRACE 教会")

        assertTrue(result.japaneseName.orEmpty().endsWith("教会"))
        assertTrue(result.japaneseName.orEmpty().none { it in 'A'..'Z' || it in 'a'..'z' })
        assertEquals("NEW GRACE", result.latinName)
        assertEquals(ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_CONGREGATION_SUFFIX, result.pattern)
    }


    @Test
    fun usesFullLatinNameInsteadOfLeadingAbbreviation() {
        val result = decomposer.decompose("IEN(Igreja Evangélica das Nações)")

        assertEquals("諸国福音教会", result.japaneseName)
        assertEquals("Igreja Evangélica das Nações", result.latinName)
        assertEquals(
            listOf(
                LocalizedName("ja", "諸国福音教会"),
                LocalizedName("pt", "Igreja Evangélica das Nações"),
            ),
            result.localizedNames,
        )
        assertEquals(ChurchNamePattern.LATIN_ABBREVIATION_WITH_FULL_NAME, result.pattern)
    }


    @Test
    fun extractsJapaneseAndLatinNamesSeparatedByPipe() {
        val result = decomposer.decompose(
            "マスタードシードクリスチャン教会 さいたま | MUSTARD SEED Christian Church Saitama",
        )

        assertEquals("マスタードシードクリスチャン教会 さいたま", result.japaneseName)
        assertEquals("MUSTARD SEED Christian Church Saitama", result.latinName)
        assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_PIPE_ALIAS, result.pattern)
    }


    @Test
    fun preservesKoreanParentheticalAliasAlongsideJapaneseName() {
        val result = decomposer.decompose("マリ キリスト教会 (마리 그리스도교회)")

        assertEquals("マリ キリスト教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(
            listOf(LocalizedName("ja", "マリ キリスト教会"), LocalizedName("ko", "마리 그리스도교회")),
            result.localizedNames,
        )
        assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_KOREAN_PARENTHETICAL, result.pattern)
    }


    @Test
    fun extractsJapaneseParentheticalAliasFromKoreanPrimaryName() {
        val result = decomposer.decompose("동경지구촌교회 (東京ジグチョン教会)")

        assertEquals("東京ジグチョン教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(
            listOf(LocalizedName("ja", "東京ジグチョン教会"), LocalizedName("ko", "동경지구촌교회")),
            result.localizedNames,
        )
        assertEquals(ChurchNamePattern.KOREAN_NAME_WITH_JAPANESE_PARENTHETICAL, result.pattern)
    }


    @Test
    fun retainsJapaneseBranchAndBuildingNameForEnglishComposition() {
        val examples = mapOf(
            "東京日暮里国際教会(六本木会堂)" to "東京日暮里国際教会 六本木会堂",
            "改革派国際基督長老教会(西東京礼拝堂)" to "改革派国際基督長老教会 西東京礼拝堂",
        )

        examples.forEach { (source, expected) ->
            val result = decomposer.decompose(source)
            assertEquals(expected, result.japaneseName, source)
            assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_BRANCH_PARENTHETICAL, result.pattern, source)
        }
    }


    @Test
    fun removesParentheticalChurchDescriptorFromCanonicalJapaneseName() {
        val examples = mapOf(
            "ザ・クラウドチャーチ(キリスト教会)" to "ザ・クラウドチャーチ",
            "ベイサイドチャーチ（キリスト教会）" to "ベイサイドチャーチ",
        )

        examples.forEach { (source, expected) ->
            val result = decomposer.decompose(source)
            assertEquals(expected, result.japaneseName, source)
            assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_CHURCH_DESCRIPTOR, result.pattern, source)
        }
    }


    @Test
    fun usesParentheticalJapaneseFullNameInsteadOfMixedAbbreviation() {
        val result = decomposer.decompose("別府EMC(別府地の果て宣教教会）")

        assertEquals("別府地の果て宣教教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(ChurchNamePattern.JAPANESE_ABBREVIATION_WITH_FULL_NAME_PARENTHETICAL, result.pattern)
    }


    @Test
    fun usesWhitespaceSeparatedJapaneseFullNameInsteadOfMixedAbbreviation() {
        val result = decomposer.decompose("東京EMC 東京地の果て宣教教会")

        assertEquals("東京地の果て宣教教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(ChurchNamePattern.JAPANESE_ABBREVIATION_WITH_FULL_NAME_WHITESPACE, result.pattern)
    }


    @Test
    fun keepsPrimaryJapaneseFullNameWhenAnotherJapaneseFullNameIsAnAlias() {
        val result = decomposer.decompose("寝屋川福音キリスト教会 (ファミリーチャーチねや川)")

        assertEquals("寝屋川福音キリスト教会", result.japaneseName)
        assertEquals(null, result.latinName)
        assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_JAPANESE_ALIAS, result.pattern)
    }


    @Test
    fun decomposesSlashSeparatedAliasesFoundInSavedPlaces() {
        val examples = listOf(
            Triple("グレイスチャペル大分/GRACE CHAPEL OITA", "グレイスチャペル大分", "GRACE CHAPEL OITA"),
            Triple(
                "Tokyo Immanuel Church / 東京インマヌエル教会/동경임마누엘교회",
                "東京インマヌエル教会",
                "Tokyo Immanuel Church",
            ),
            Triple(
                "埼玉ペンテコステ教会/バイブルフェローシップ所沢/Saitama Pentecostal Church",
                "埼玉ペンテコステ教会",
                "Saitama Pentecostal Church",
            ),
        )

        examples.forEach { (source, japanese, latin) ->
            val result = decomposer.decompose(source)
            assertEquals(japanese, result.japaneseName, source)
            assertEquals(latin, result.latinName, source)
            assertEquals(ChurchNamePattern.MULTILINGUAL_SLASH_ALIASES, result.pattern, source)
        }
        assertEquals(
            listOf(
                LocalizedName("ja", "東京インマヌエル教会"),
                LocalizedName("en", "Tokyo Immanuel Church"),
                LocalizedName("ko", "동경임마누엘교회"),
            ),
            decomposer.decompose(examples[1].first).localizedNames,
        )
    }

    @Test
    fun takesFirstJapaneseAliasInsideLatinParentheticalName() {
        val result = decomposer.decompose("JOY CHURCH(ジョイチャーチ／ジョイ教会／カフェのある教会)")

        assertEquals("ジョイチャーチ", result.japaneseName)
        assertEquals("JOY CHURCH", result.latinName)
    }

    @Test
    fun extractsLatinParentheticalAliasFromJapanesePrimaryName() {
        val examples = mapOf(
            "あずみ野ファミリーチャペル (Azumino Family Chapel)" to ("あずみ野ファミリーチャペル" to "Azumino Family Chapel"),
            "熊谷福音キリスト教会(Kumagaya Gospel Christ Church)" to ("熊谷福音キリスト教会" to "Kumagaya Gospel Christ Church"),
            "ビジョンセンター （JESUS FAMILY CHURCH）" to ("ビジョンセンター" to "JESUS FAMILY CHURCH"),
        )
        examples.forEach { (source, expected) ->
            val result = decomposer.decompose(source)
            assertEquals(expected.first, result.japaneseName, source)
            assertEquals(expected.second, result.latinName, source)
            assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_PARENTHETICAL, result.pattern, source)
        }
    }

    @Test
    fun keepsAdditionalLatinPipeNameAsEnglishAlias() {
        val result = decomposer.decompose("JCOB Power House | JCOB Shizuoka Office")

        assertEquals("JCOB Power House", result.latinName)
        assertEquals(
            listOf(
                LocalizedName("ja", "ジコブ ポヱル ホウセ"),
                LocalizedName("en", "JCOB Power House"),
                LocalizedName("en", "JCOB Shizuoka Office"),
            ),
            result.localizedNames,
        )
        assertEquals(ChurchNamePattern.LATIN_PIPE_ALIASES, result.pattern)
    }

    @Test
    fun normalizesAdditionalSavedPlacesNameShapes() {
        assertEquals("清瀬福音自由教会", decomposer.decompose("(宗) 清瀬福音自由教会").japaneseName)

        val japaneseAbbreviation = decomposer.decompose("寝屋川キリスト教会（MB）")
        assertEquals("寝屋川キリスト教会", japaneseAbbreviation.japaneseName)
        assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_ABBREVIATION, japaneseAbbreviation.pattern)

        val latinAbbreviation = decomposer.decompose("Filipino Nazarene Christian Fellowship (FNCF)")
        assertEquals("Filipino Nazarene Christian Fellowship", latinAbbreviation.latinName)
        assertEquals(ChurchNamePattern.LATIN_NAME_WITH_ABBREVIATION, latinAbbreviation.pattern)

        val whitespace = decomposer.decompose("名古屋クリスチャンセルチャーチ Nagoya Christian Cell Church（NC3）")
        assertEquals("名古屋クリスチャンセルチャーチ", whitespace.japaneseName)
        assertEquals("Nagoya Christian Cell Church", whitespace.latinName)
        assertEquals(ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_WHITESPACE_ALIAS, whitespace.pattern)
    }

    @Test
    fun transliteratesLatinOnlyAndEmbeddedLatinNamesIntoJapaneseCanonicalNames() {
        val latinOnly = decomposer.decompose("Tokyo Multicultural Church")
        assertEquals("Tokyo Multicultural Church", latinOnly.latinName)
        assertEquals(false, latinOnly.japaneseName.orEmpty().any { it in 'A'..'Z' || it in 'a'..'z' })
        assertEquals(true, latinOnly.japaneseName.orEmpty().any { it in '\u3040'..'\u30ff' })

        val embedded = decomposer.decompose("Awakening Tokyo教会")
        assertEquals(false, embedded.japaneseName.orEmpty().any { it in 'A'..'Z' || it in 'a'..'z' })
        assertEquals(true, embedded.japaneseName.orEmpty().endsWith("教会"))

        val korean = ChurchNameDecomposer(languageIdentifier = { "ko" }).decompose("기타카미벧엘교회")
        assertEquals(
            listOf(
                LocalizedName("ja", "ギタカミベデ-エルギョホエ"),
                LocalizedName("ko", "기타카미벧엘교회"),
            ),
            korean.localizedNames,
        )
        assertEquals(false, korean.japaneseName.orEmpty().any { it in '\uac00'..'\ud7af' })
    }

    @Test
    fun preservesLatinAbbreviationWhenJapaneseChurchNameEndsWithBranchGeoname() {
        val decomposer = ChurchNameDecomposer(
            branchGeonames = setOf("寸座", "津山", "山"),
            knownLatinAbbreviations = setOf("HCC"),
        )
        val examples = listOf("HCCライブチャーチ寸座", "HCCライブチャーチ津山")

        examples.forEach { source ->
            val result = decomposer.decompose(source)
            assertEquals(source, result.japaneseName, source)
            assertEquals(null, result.latinName, source)
            assertEquals(
                ChurchNamePattern.LATIN_ABBREVIATION_JAPANESE_NAME_TRAILING_GEONAME_BRANCH,
                result.pattern,
                source,
            )
        }
    }

    @Test
    fun preservesDenominationAbbreviationsInJapaneseGoogleTitles() {
        val decomposer = ChurchNameDecomposer(
            knownLatinAbbreviations = setOf("JELC", "JEC"),
        )

        listOf("JELC大阪教会", "JEC横浜教会").forEach { title ->
            val result = decomposer.decompose(title)
            assertEquals(title, result.japaneseName)
            assertEquals(null, result.latinName)
            assertEquals(
                ChurchNamePattern.LATIN_DENOMINATION_ABBREVIATION_WITH_JAPANESE_NAME,
                result.pattern,
            )
        }
    }

    @Test
    fun composesLatinChurchNamesFromReviewedConceptsAndGeonames() {
        val composer = LatinChurchNameJapaneseComposer(
            concepts = linkedMapOf(
                "グレイス" to "Grace",
                "グレース" to "Grace",
                "センター" to "Center",
                "グローバル" to "Global",
                "ミッション" to "Mission",
                "ハレルヤ" to "Halleluya",
                "ヒズ・プレゼンス" to "His Presence",
            ),
            geonames = mapOf("仙台" to "Sendai"),
        )
        val decomposer = ChurchNameDecomposer(latinToJapanese = composer::translate)
        val expected = mapOf(
            "Grace Center Church Sendai" to "グレースセンターチャーチ仙台",
            "Global Mission Japan" to "グローバルミッションジャパン",
            "His Presence Church" to "ヒズ・プレゼンスチャーチ",
            "Halleluya Church" to "ハレルヤチャーチ",
            // Unknown proper-name parts still use ICU; the structural word is deterministic.
            "Hija Belmont Church" to "ヒジャベルモンテチャーチ",
            "FirstVineyardChurch可児福音教会" to "フィルステヸネヤルデチャーチ可児福音教会",
            "Snowballchurch Japan" to "スノウバッルチャーチジャパン",
        )

        expected.forEach { (title, japaneseName) ->
            val result = decomposer.decompose(title)
            assertEquals(japaneseName, result.japaneseName, title)
            if (title.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }) {
                assertEquals(null, result.latinName, title)
                assertEquals(ChurchNamePattern.SINGLE_NAME, result.pattern, title)
            } else {
                assertEquals(title, result.latinName, title)
                assertEquals(ChurchNamePattern.LATIN_NAME_COMPOSED_TO_JAPANESE, result.pattern, title)
            }
        }
    }
}
