package jp.co.crossmap.crawl

import jp.co.crossmap.CrawledPage
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ChurchEnglishNameResolverTest {
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
            ) to "Akabane Church",
            ChurchEnglishNameInput(
                id = "rule:jelc-osaka",
                name = "日本福音ルーテル大阪教会",
                denominationId = "JELC",
                address = "大阪府大阪市",
                location = GeoPoint(34.69, 135.50),
                websiteUrl = "https://jelc.or.jp/",
            ) to "Osaka Church",
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
            assertEquals("Akabane Church", resolver.findOutChurchEnglishName(church), alias)
        }
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
        assertEquals("Geoname + Christian assembly rule", resolver.determineProgrammatically(church)?.evidence)
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
        assertEquals("Geoname + tradition + church rule", resolver.determineProgrammatically(church)?.evidence)
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
        val resolver = ChurchEnglishNameResolver { record ->
            assertTrue(record.websiteUrl.endsWith("/lucia/"))
            ChurchEnglishNameGuess("St. Lucia Church", confidence = 0.98f)
        }

        assertEquals("St. Lucia Church", resolver.findOutChurchEnglishName(church))
        assertEquals(null, resolver.determineProgrammatically(church))
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
        val resolver = ChurchEnglishNameResolver {
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
        }

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
        val resolver = ChurchEnglishNameResolver {
            ChurchEnglishNameGuess("Fuchu Hosanna Gospel Church", confidence = 0.9f)
        }

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
                        name = "岡山希望教会",
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
        val resolver = ChurchEnglishNameResolver {
            ChurchEnglishNameGuess(
                "Tokyo Sophia International Presbyterian Church",
                confidence = 0.94f,
                reasoning = "Split and translated Japanese name",
                model = "fixture-japanese-model",
            )
        }

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
                            name = "岡山希望教会",
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
