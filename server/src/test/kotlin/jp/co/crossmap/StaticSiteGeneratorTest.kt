package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticSiteGeneratorTest {
    @Test
    fun apostropheIsDeletedInsteadOfBecomingUrlSeparator() {
        assertEquals("lords-church", StaticSiteGenerator().pageSlug(null, "Lord's Church"))
    }

    @Test
    fun realNskkSaintLuciaNameUsesAuthoritativeUrlSpelling() {
        assertEquals("nskk-st-lucia-church", StaticSiteGenerator().pageSlug("NSKK", "St. Lucia Church"))
    }

    @Test
    fun denominationAlreadyPresentInEnglishChurchNameIsNotDuplicatedInUrl() {
        assertEquals("jelc-glory-church", StaticSiteGenerator().pageSlug("jelc", "JELC Glory Church"))
    }

    @Test
    fun independentChristianAssemblyKeepsAssemblyAndOmitsDenomination() {
        assertEquals("kyodo-christian-assembly", StaticSiteGenerator().pageSlug(null, "Kyodo Christian Assembly"))
    }

    @Test
    fun generatesFriendlyProductionAndDevelopmentUrlsFromFreeMarkerTemplate() {
        val output = Files.createTempDirectory("crossmap-static-site")
        try {
            val church = tokyoSophia()
            val generated = StaticSiteGenerator().generate(
                churches = listOf(church),
                denominationEnglishNames = mapOf("JOAC" to "Olivet Assembly Japan"),
                outputDirectory = output,
            ).single()

            assertEquals(
                "olivet-assembly-japan-tokyo-sophia-international-presbyterian-church.html",
                generated.fileName,
            )
            assertEquals(
                "/church/${generated.fileName}",
                generated.pageUrl,
            )
            assertEquals(
                "/church/${generated.fileName}",
                generated.canonicalUrl,
            )
            val html = Files.readString(generated.path)
            assertTrue(html.contains("<h1 id=\"church-name\">東京ソフィア長老教会</h1>"))
            assertTrue(html.contains("Tokyo Sophia International Presbyterian Church"))
            assertTrue(html.contains("id=\"language\""))
            assertTrue(html.contains("data-language=\"ja\""))
            assertTrue(html.contains("data-language=\"en\""))
            assertTrue(html.contains("Olivet Assembly Japan"))
            assertTrue(html.contains("rel=\"canonical\" href=\"${generated.canonicalUrl}\""))
            assertTrue(html.contains("東京都新宿区西早稲田"))
            assertTrue(!html.contains("app.js"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalChurchRecordRejectsAnyBlankEnglishNameBeforePublication() {
        val output = Files.createTempDirectory("crossmap-static-site-missing")
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                tokyoSophia().copy(englishName = "")
            }
            assertTrue(error.message.orEmpty().contains("ChurchRecord.englishName"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun knownDenominationCannotFallBackToInternalIdInUrl() {
        val output = Files.createTempDirectory("crossmap-static-site-denomination")
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                StaticSiteGenerator().generate(
                    churches = listOf(tokyoSophia()),
                    denominationEnglishNames = emptyMap(),
                    outputDirectory = output,
                )
            }
            assertTrue(error.message.orEmpty().contains("JOAC"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun internalDenominationSentinelsAreAbsentFromPublishedChurchNamesAndDetails() {
        val output = Files.createTempDirectory("crossmap-static-site-independent")
        try {
            val churches = listOf(
                ChurchRecord(
                    id = "google:8936876971814287439",
                    name = "単立町田バプテスト教会",
                    englishName = "Machida Baptist Church",
                    denominationId = "INDEPENDENT_CHURCH",
                    localizedNames = listOf(LocalizedName("en", "INDEPENDENT_CHURCH Machida Baptist Church")),
                    address = "東京都町田市",
                    location = GeoPoint(35.54, 139.45),
                    websiteUrl = "https://example.invalid/machida",
                ),
                ChurchRecord(
                    id = "google:11718723684115805132",
                    name = "ホサナ福音キリスト教会 府中チャペル",
                    englishName = "Fuchu Hosanna Gospel Church",
                    denominationId = "NOT_DETERMINED",
                    localizedNames = listOf(LocalizedName("ko", "NOT_DETERMINED 후추 호산나 복음 교회")),
                    address = "〒183-0005 東京都府中市若松町１丁目１−３",
                    location = GeoPoint(35.6701735, 139.49467),
                    websiteUrl = "https://hosannafucyu.wixsite.com/mysite",
                ),
            )

            val generated = StaticSiteGenerator().generate(
                churches = churches,
                denominationEnglishNames = emptyMap(),
                outputDirectory = output,
            )

            assertEquals(
                setOf("machida-baptist-church.html", "fuchu-hosanna-gospel-church.html"),
                generated.map(GeneratedChurchPage::fileName).toSet(),
            )
            generated.forEach { page ->
                val html = Files.readString(page.path)
                assertFalse(html.contains("INDEPENDENT_CHURCH"), page.fileName)
                assertFalse(html.contains("NOT_DETERMINED"), page.fileName)
                assertFalse(html.contains("<strong>教派</strong>"), page.fileName)
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun excludedChurchListingDomainIsNotPublishedAsTheChurchWebsite() {
        val output = Files.createTempDirectory("crossmap-static-site-listing-domain")
        try {
            val generated = StaticSiteGenerator().generate(
                churches = listOf(
                    ChurchRecord(
                        id = "google:10158070367548216990",
                        googleCid = "10158070367548216990",
                        name = "錦キリスト教会",
                        englishName = "Nishiki Christ Church",
                        denominationId = "NOT_DETERMINED",
                        address = "熊本県球磨郡錦町",
                        location = GeoPoint(32.20, 130.84),
                        websiteUrl = "http://www.church-info.jp/sp/search/detail.php?key=16230012",
                    ),
                ),
                denominationEnglishNames = emptyMap(),
                outputDirectory = output,
                excludedChurchListingDomains = setOf("church-info.jp"),
            ).single()
            val html = Files.readString(generated.path)

            assertFalse(html.contains("church-info.jp"))
            assertTrue(html.contains("https://www.google.com/maps?cid=10158070367548216990"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun collisionsRequireAndPrefixEnglishCityOrAddressName() {
        val output = Files.createTempDirectory("crossmap-static-site-collision")
        val first = tokyoSophia()
        val second = tokyoSophia().copy(
            id = "official:yokohama-sophia",
            name = "横浜ソフィア長老教会",
            address = "神奈川県横浜市",
        )
        try {
            assertFailsWith<IllegalArgumentException> {
                StaticSiteGenerator().generate(
                    churches = listOf(first, second),
                    denominationEnglishNames = mapOf("JOAC" to "Olivet Assembly Japan"),
                    outputDirectory = output,
                )
            }

            val generated = StaticSiteGenerator().generate(
                churches = listOf(first, second),
                denominationEnglishNames = mapOf("JOAC" to "Olivet Assembly Japan"),
                outputDirectory = output,
                collisionLocationEnglishNames = mapOf(
                    first.id to "Tokyo",
                    second.id to "Yokohama",
                ),
            )
            assertEquals(
                setOf(
                    "olivet-assembly-japan-tokyo-tokyo-sophia-international-presbyterian-church.html",
                    "olivet-assembly-japan-yokohama-tokyo-sophia-international-presbyterian-church.html",
                ),
                generated.map { it.fileName }.toSet(),
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun collisionLocationsAreDerivedFromRealJapaneseAddresses() {
        val first = tokyoSophia()
        val second = tokyoSophia().copy(
            id = "official:yokohama-sophia",
            name = "横浜ソフィア長老教会",
            address = "神奈川県横浜市中区山下町",
        )
        val denominations = mapOf("JOAC" to "Olivet Assembly Japan")

        val locations = ChurchPageCollisionResolver.resolve(
            listOf(first, second),
            denominations,
            mapOf("東京都" to "Tokyo", "横浜市" to "Yokohama"),
        )

        assertEquals("Tokyo", locations[first.id])
        assertEquals("Yokohama", locations[second.id])
    }

    @Test
    fun collisionPrefixDoesNotCreateCollisionWithExistingBaseSlug() {
        val tokyoLife = tokyoSophia().copy(
            id = "real:tokyo-life-existing",
            englishName = "Tokyo Life Church",
            denominationId = "INDEPENDENT_CHURCH",
        )
        val firstLife = tokyoLife.copy(
            id = "real:life-tokyo",
            englishName = "Life Church",
            address = "東京都新宿区",
        )
        val secondLife = firstLife.copy(
            id = "real:life-osaka",
            address = "大阪府大阪市",
        )

        val locations = ChurchPageCollisionResolver.resolve(
            listOf(tokyoLife, firstLife, secondLife),
            emptyMap(),
            mapOf("東京都" to "Tokyo", "新宿区" to "Shinjuku", "大阪市" to "Osaka"),
        )

        assertEquals("Shinjuku", locations[firstLife.id])
        assertEquals("Osaka", locations[secondLife.id])
    }

    @Test
    fun regenerationRemovesObsoleteGeneratedHtmlFiles() {
        val output = Files.createTempDirectory("crossmap-static-site-stale")
        Files.writeString(output.resolve("obsolete-church.html"), "stale")
        try {
            StaticSiteGenerator().generate(
                churches = listOf(tokyoSophia()),
                denominationEnglishNames = mapOf("JOAC" to "Olivet Assembly Japan"),
                outputDirectory = output,
            )

            assertEquals(false, Files.exists(output.resolve("obsolete-church.html")))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    private fun tokyoSophia() = ChurchRecord(
        id = "official:tokyo-sophia",
        name = "東京ソフィア長老教会",
        englishName = "Tokyo Sophia International Presbyterian Church",
        denominationId = "JOAC",
        address = "東京都新宿区西早稲田",
        location = GeoPoint(35.708, 139.709),
        websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
    )
}
