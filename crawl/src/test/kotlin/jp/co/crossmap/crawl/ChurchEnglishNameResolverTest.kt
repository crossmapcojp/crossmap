package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ChurchEnglishNameResolverTest {
    @Test
    fun acceptsOfficialLatinBrandNamesButRejectsGenericCongregationWords() {
        assertTrue(ChurchEnglishNameResolver.isUsableEnglishChurchName("enCounter"))
        assertTrue(ChurchEnglishNameResolver.isUsableEnglishChurchName("ADOMJ Oizumi"))
        assertFalse(ChurchEnglishNameResolver.isUsableEnglishChurchName("Church"))
    }

    @Test
    fun completesGenericGoogleTitleFromDenominationAndMostSpecificAddressGeoname() {
        val rule = GenericChurchNameFromAddressRule(
            denominations = listOf(Denomination("JAG", "日本アッセンブリーズ・オブ・ゴッド教団")),
            geonames = mapOf("静岡" to "Shizuoka", "袋井" to "Fukuroi"),
        )
        val church = ChurchEnglishNameInput(
            id = "google:5358878719376239645",
            name = "教会",
            denominationId = "JAG",
            address = "〒437-1121 静岡県袋井市諸井９４１−１",
            location = GeoPoint(34.7255175, 137.9221988),
            websiteUrl = "https://www.google.com/maps/contrib/112345582264093332624?hl=ja",
        )

        assertEquals("JAG Fukuroi Church", rule.translate(church)?.englishName)
    }

    @Test
    fun deterministicRulesRomanizeRealGeonamesBeyondTheCuratedOverrides() = runBlocking {
        val resolver = ChurchEnglishNameResolver(
            translationRules = ChurchNameEnglishTranslationRules.create(
                listOf(Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))),
            ),
            translator = { error("LLM must not run for address-confirmed geonames") },
        )
        val denenchofu = ChurchEnglishNameInput(
            id = "google:denenchofu-uccj",
            name = "日本基督教団田園調布教会",
            denominationId = "UCCJ",
            address = "東京都大田区田園調布３丁目",
            location = GeoPoint(35.59, 139.67),
            websiteUrl = "https://uccj.org/",
        )
        val tomigusuku = ChurchEnglishNameInput(
            id = "google:tomigusuku-assembly",
            name = "豊見城キリスト集会",
            address = "沖縄県豊見城市",
            location = GeoPoint(26.16, 127.67),
            websiteUrl = "https://example.jp/",
        )

        val names = listOf(denenchofu, tomigusuku).map { resolver.findOutChurchEnglishName(it) }

        assertTrue(names[0].endsWith(" Church"))
        assertTrue(names[1].endsWith(" Christian Assembly"))
        assertTrue(names.all { name -> name.all { it.code < 128 } })
    }

    @Test
    fun deterministicRulesCoverRealGeonameTraditionAndDenominationAliasNames() = runBlocking {
        val realCases = listOf(
            ChurchEnglishNameInput(
                id = "google:6646597370070891755",
                name = "東京バプテスト教会",
                denominationId = "HPBC",
                address = "東京都渋谷区鉢山町９−２",
                location = GeoPoint(35.650855, 139.700465),
                websiteUrl = "https://www.tokyobaptist.org/",
            ) to "Tokyo Baptist Church",
            ChurchEnglishNameInput(
                id = "rule:kawasaki-holiness",
                name = "川崎ホーリネス教会",
                denominationId = "JHC",
                address = "神奈川県川崎市",
                location = GeoPoint(35.53, 139.70),
                websiteUrl = "https://www.jhc.or.jp/",
            ) to "Kawasaki Holiness Church",
            ChurchEnglishNameInput(
                id = "rule:uccj-akabane",
                name = "日本基督教団赤羽教会",
                denominationId = "UCCJ",
                address = "東京都北区赤羽",
                location = GeoPoint(35.78, 139.72),
                websiteUrl = "https://uccj.org/",
            ) to "UCCJ Akabane Church",
            ChurchEnglishNameInput(
                id = "rule:jelc-osaka",
                name = "日本福音ルーテル大阪教会",
                denominationId = "JELC",
                address = "大阪府大阪市",
                location = GeoPoint(34.69, 135.50),
                websiteUrl = "https://jelc.or.jp/",
            ) to "JELC Osaka Church",
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for deterministic translation rules") }

        realCases.forEach { (church, expected) ->
            assertEquals(expected, resolver.findOutChurchEnglishName(church), church.name)
        }
    }

    @Test
    fun everyConfiguredUccjNameAndAliasCanPrefixTheSameRule() = runBlocking {
        val uccj = Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団", "日本基督教会"))
        val resolver = ChurchEnglishNameResolver(
            translator = { error("LLM must not run for denomination aliases") },
            translationRules = ChurchNameEnglishTranslationRules.create(listOf(uccj)),
        )

        (listOf(uccj.name) + uccj.aliases).forEachIndexed { index, alias ->
            val church = ChurchEnglishNameInput(
                id = "rule:uccj-alias-$index",
                name = "${alias}赤羽教会",
                denominationId = "UCCJ",
                address = "東京都北区赤羽",
                location = GeoPoint(35.78, 139.72),
                websiteUrl = "https://uccj.org/",
            )
            assertEquals("UCCJ Akabane Church", resolver.findOutChurchEnglishName(church), alias)
        }
    }

    @Test
    fun denominationPrefixRuleRomanizesMultiplePlaceAndDistrictTokens() = runBlocking {
        val uccj = Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))
        val resolver = ChurchEnglishNameResolver(
            translator = { error("Known composite place reading must not invoke LLM") },
            translationRules = ChurchNameEnglishTranslationRules.create(listOf(uccj)),
        )
        val church = ChurchEnglishNameInput(
            id = "google:10003468413261460406",
            name = "日本基督教団 神戸雲内教会",
            denominationId = "UCCJ",
            address = "〒657-0051 兵庫県神戸市灘区八幡町１丁目６−９",
            location = GeoPoint(34.719125, 135.237793),
            websiteUrl = "http://blog.goo.ne.jp/kumochi/",
        )

        assertEquals("UCCJ Kobe Kumouchi Church", resolver.findOutChurchEnglishName(church))
        assertEquals("UCCJ Kobe Kumouchi Church", resolver.determineProgrammatically(church)?.englishName)
    }

    @Test
    fun denominationPrefixRuleRomanizesConceptualProperNames() = runBlocking {
        val uccj = Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))
        val resolver = ChurchEnglishNameResolver(
            translator = { error("LLM must not run for an exact denomination prefix") },
            translationRules = ChurchNameEnglishTranslationRules.create(listOf(uccj)),
        )
        val church = ChurchEnglishNameInput(
            id = "real:uccj-megumi",
            name = "日本基督教団 静岡めぐみ教会",
            denominationId = "UCCJ",
            address = "静岡県静岡市",
            location = GeoPoint(34.975, 138.383),
            websiteUrl = "https://uccj.org/",
        )

        assertEquals("UCCJ Shizuoka Megumi Church", resolver.findOutChurchEnglishName(church))
    }

    @Test
    fun structuredRuleTransliteratesBiblicalConceptNameInsteadOfSemanticallyTranslatingIt() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:10002614478709874444",
            name = "大阪聖和教会",
            denominationId = NOT_DETERMINED,
            address = "大阪府大阪市",
            location = GeoPoint(34.69, 135.50),
            websiteUrl = "http://osakaseiwachurch.wix.com/kyoukaisyoukai",
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for a known conceptual proper name") }

        assertEquals("Osaka Seiwa Church", resolver.findOutChurchEnglishName(church))
        assertTrue(resolver.determineProgrammatically(church)?.evidence.orEmpty().contains("structured"))
    }

    @Test
    fun componentAnalyzerSplitsKnownCityFromUnknownDistrictProperName() {
        val analyzer = ChurchNameComponentAnalyzer(
            listOf(Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団"))),
        )
        val church = ChurchEnglishNameInput(
            id = "google:10003468413261460406",
            name = "日本基督教団 神戸雲内教会",
            denominationId = "UCCJ",
            address = "〒657-0051 兵庫県神戸市灘区八幡町１丁目６−９",
            location = GeoPoint(34.719125, 135.237793),
            websiteUrl = "http://blog.goo.ne.jp/kumochi/",
        )

        val analysis = requireNotNull(analyzer.analyze(church))

        assertEquals("日本基督教団", analysis.denominationAlias)
        assertEquals(
            listOf(
                ChurchNameComponent("神戸", ChurchNamePartRole.GEONAME, "Kobe", "name lexicon"),
                ChurchNameComponent("雲内", ChurchNamePartRole.GEONAME, "Kumouchi", "name lexicon"),
            ),
            analysis.components,
        )
        assertEquals("Church", analysis.congregationEnglish)
    }

    @Test
    fun independentKyodoChristianAssemblyPreservesAssemblyWording() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "rule:kyodo-christian-assembly",
            name = "経堂キリスト集会",
            denominationId = "INDEPENDENT_CHURCH",
            address = "東京都世田谷区経堂",
            location = GeoPoint(35.651, 139.637),
            websiteUrl = "https://kyodo-assembly.example/",
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for Christian assembly rule") }

        assertEquals("Kyodo Christian Assembly", resolver.findOutChurchEnglishName(church))
        assertEquals(
            "Deterministic structured denomination/name-part/congregation translation",
            resolver.determineProgrammatically(church)?.evidence,
        )
    }

    @Test
    fun crawledOfficialEnglishNameWinsWithoutCallingLlm() = runBlocking {
        val church = okayamaBaptist().copy(
            name = "岡山希望教会",
            pages = listOf(
                CrawledPage(
                    url = "http://okayama-baptist.jp/",
                    title = "岡山希望教会 - Okayama Hope Church",
                    text = "岡山希望教会 Okayama Hope Church",
                ),
            ),
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for official webpage evidence") }

        assertEquals("Okayama Hope Church", resolver.findOutChurchEnglishName(church))
        assertEquals("Crawled church webpage", resolver.determineProgrammatically(church)?.evidence)
    }

    @Test
    fun genericGoogleNameUsesRealJapaneseChurchTitleFromCrawledPage() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:15972447304536824717",
            name = "教会",
            denominationId = "JCCJ",
            address = "〒662-0865 兵庫県西宮市神垣町６−４１",
            location = GeoPoint(34.748359, 135.339875),
            websiteUrl = "https://nseiai.kyoukai.jp/",
            pages = listOf(CrawledPage("https://nseiai.jccj.or.jp/", title = "西宮聖愛教会 日本イエス・キリスト教団")),
        )
        val resolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(additionalConcepts = mapOf("聖愛" to "Seiai")),
            ChurchEnglishNameTranslator { error("Japanese page title must avoid LLM") },
        )

        assertEquals("Nishinomiya Seiai Church", resolver.findOutChurchEnglishName(church))
    }

    @Test
    fun urlShapedGoogleNameUsesOfficialLatinChurchTitle() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:11195437511384004215",
            name = "https://direcciones.idmji.org/es/",
            denominationId = "INDEPENDENT_CHURCH",
            address = "〒113-0034 東京都文京区",
            location = GeoPoint(35.717, 139.759),
            websiteUrl = "http://www.idmji.org/",
            pages = listOf(
                CrawledPage(
                    "http://www.idmji.org/",
                    title = "Iglesia de Dios Ministerial de Jesucristo Internacional - IDMJI",
                ),
            ),
        )
        val resolver = ChurchEnglishNameResolver { error("Official Latin title must avoid LLM") }

        assertEquals("Iglesia de Dios Ministerial de Jesucristo Internacional", resolver.findOutChurchEnglishName(church))
    }

    @Test
    fun deterministicNameRuleRunsBeforeUrlEvidenceAndLlm() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:6646597370070891755",
            name = "東京バプテスト教会",
            denominationId = NOT_DETERMINED,
            address = "〒150-0035 東京都渋谷区鉢山町９−２",
            location = GeoPoint(35.650855, 139.700465),
            websiteUrl = "http://www.tokyobaptist.org/",
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run after a deterministic rule") }

        assertEquals("Tokyo Baptist Church", resolver.findOutChurchEnglishName(church))
        assertEquals(
            "Deterministic structured denomination/name-part/congregation translation",
            resolver.determineProgrammatically(church)?.evidence,
        )
    }

    @Test
    fun realSaintLuciaUrlSpellingIsPassedToLlmAsAuthoritativeEvidence() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:9753525676873678048",
            name = "日本聖公会聖ルシヤ教会",
            denominationId = "ANGLICAN_JP",
            address = "〒584-0074 大阪府富田林市久野喜台２丁目１５−１",
            location = GeoPoint(34.4991617, 135.5613646),
            websiteUrl = "http://www.nskk.org/osaka/church/lucia/",
        )
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val dictionaries = ChurchNameEnglishDictionary.load(root.resolve("resources"))
        val resolver = ChurchEnglishNameResolver(
            translationRules = ChurchNameEnglishTranslationRules.create(additionalConcepts = dictionaries.concepts),
            translator = ChurchEnglishNameTranslator { error("Known Lucia spelling must not invoke LLM") },
        )

        assertEquals("St. Lucia Church", resolver.findOutChurchEnglishName(church))
        assertEquals("St. Lucia Church", resolver.determineProgrammatically(church)?.englishName)
    }

    @Test
    fun realPortugueseLatinScriptNameIsSanitizedWithoutLlm() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:10104591574545293184",
            name = "Igreja evangélica Dúnamis Minokamo",
            denominationId = NOT_DETERMINED,
            address = "岐阜県美濃加茂市",
            location = GeoPoint(35.440, 137.015),
            websiteUrl = "https://www.google.com/maps?cid=10104591574545293184",
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for a Latin-script name") }

        assertEquals("Igreja evangelica Dunamis Minokamo", resolver.findOutChurchEnglishName(church))
        assertEquals("Latin-script church name", resolver.determineProgrammatically(church)?.evidence)
    }

    @Test
    fun linkedSocialDisplayNameIsProgrammaticEvidence() = runBlocking {
        val church = okayamaBaptist().copy(
            name = "岡山希望教会",
            socialProfiles = listOf(
                SocialProfile(
                    platform = SocialPlatform.YOUTUBE,
                    url = "https://www.youtube.com/channel/UCCBpKmS8N-lP4FRdOWy1MRQ",
                    displayName = "Okayama Hope Church",
                ),
            ),
        )
        val resolver = ChurchEnglishNameResolver { error("LLM must not run for linked social evidence") }

        assertEquals("Okayama Hope Church", resolver.findOutChurchEnglishName(church))
        assertEquals("Linked social account", resolver.determineProgrammatically(church)?.evidence)
    }

    @Test
    fun llmFallbackSplitsGeonameTraditionAndCongregation() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "official:tokyo-sophia",
            name = "東京ソフィア長老教会",
            denominationId = "XLSX_18816F940131",
            address = "東京都新宿区西早稲田",
            location = GeoPoint(35.708, 139.709),
            websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
        )
        val resolver = ChurchEnglishNameResolver(
            translationRules = emptyList(),
            translator = {
                ChurchEnglishNameGuess(
                englishName = "Tokyo Sophia International Presbyterian Church",
                parts = listOf(
                    TranslatedChurchNamePart("東京", ChurchNamePartRole.GEONAME, "Tokyo"),
                    TranslatedChurchNamePart("ソフィア", ChurchNamePartRole.PROPER_NAME, "Sophia International"),
                    TranslatedChurchNamePart("長老", ChurchNamePartRole.TRADITION, "Presbyterian"),
                    TranslatedChurchNamePart("教会", ChurchNamePartRole.CONGREGATION, "Church"),
                ),
                confidence = 0.94f,
                reasoning = "Translated name components",
                )
            },
        )

        assertEquals("Tokyo Sophia International Presbyterian Church", resolver.findOutChurchEnglishName(church))
    }

    @Test
    fun independentChurchFallbackDoesNotInventDenomination() = runBlocking {
        val church = ChurchEnglishNameInput(
            id = "google:11718723684115805132",
            name = "ホサナ福音キリスト教会 府中チャペル",
            denominationId = NOT_DETERMINED,
            address = "〒183-0005 東京都府中市若松町１丁目１−３",
            location = GeoPoint(35.6701735, 139.49467),
            websiteUrl = "https://hosannafucyu.wixsite.com/mysite",
        )
        val resolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(
                additionalConcepts = mapOf("ホサナ福音キリスト" to "Hosanna Gospel"),
            ),
            ChurchEnglishNameTranslator { error("Known branch-chapel structure must not invoke LLM") },
        )

        assertEquals("Fuchu Hosanna Gospel Church", resolver.findOutChurchEnglishName(church))
    }

    @Test
    fun unusableLlmOutputIsRejected() {
        val resolver = ChurchEnglishNameResolver {
            ChurchEnglishNameGuess("東京の教会", confidence = 0.2f)
        }

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                resolver.findOutChurchEnglishName(
                    okayamaBaptist().copy(
                        name = "名称未詳",
                        pages = emptyList(),
                        websiteUrl = "https://okayama-kibo.example/",
                    ),
                )
            }
        }
    }

    @Test
    fun publicationPipelinePopulatesEveryRecordAndStoresProvenance() = runBlocking {
        val deterministic = okayamaBaptist().copy(
            pages = listOf(
                CrawledPage(
                    url = "http://okayama-baptist.jp/",
                    title = "岡山バプテスト教会 Okayama Baptist Church",
                ),
            ),
        )
        val fallback = ChurchEnglishNameInput(
            id = "official:tokyo-sophia",
            name = "東京ソフィア長老教会",
            denominationId = "XLSX_18816F940131",
            address = "東京都新宿区西早稲田",
            location = GeoPoint(35.708, 139.709),
            websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
        )
        val resolver = ChurchEnglishNameResolver(
            translationRules = emptyList(),
            translator = {
                ChurchEnglishNameGuess(
                "Tokyo Sophia International Presbyterian Church",
                confidence = 0.94f,
                reasoning = "Split and translated Japanese name",
                model = "fixture-japanese-model",
                )
            },
        )

        val resolved = resolver.resolveInputs(listOf(deterministic, fallback))

        assertEquals("Okayama Baptist Church", resolved.getValue(deterministic.id).englishName)
        assertEquals(DeterminationSource.PROGRAMMATIC, resolved.getValue(deterministic.id).source)
        assertEquals(DeterminationSource.LLM, resolved.getValue(fallback.id).source)
        assertEquals("fixture-japanese-model", resolved.getValue(fallback.id).model)
    }

    @Test
    fun publicationPipelineRejectsPartialEnglishNameCoverage() {
        val resolver = ChurchEnglishNameResolver {
            ChurchEnglishNameGuess("未翻訳", confidence = 0.1f)
        }

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                resolver.resolveInputs(
                    listOf(
                        okayamaBaptist().copy(
                            name = "名称未詳",
                            pages = emptyList(),
                            websiteUrl = "https://okayama-kibo.example/",
                        ),
                    ),
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("google:906297735827744432"))
    }

    private fun okayamaBaptist() = ChurchEnglishNameInput(
        id = "google:906297735827744432",
        name = "岡山バプテスト教会",
        denominationId = "JBC",
        address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
        location = GeoPoint(34.6619806, 133.9231824),
        websiteUrl = "http://okayama-baptist.jp/",
    )
}
