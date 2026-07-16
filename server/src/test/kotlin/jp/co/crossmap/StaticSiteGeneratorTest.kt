package jp.co.crossmap

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                denominationEnglishNames = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
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
            assertTrue(error.message.orEmpty().contains("XLSX_18816F940131"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun independentChurchOmitsDenominationSegment() {
        val output = Files.createTempDirectory("crossmap-static-site-independent")
        try {
            val church = ChurchRecord(
                id = "google:11718723684115805132",
                name = "ホサナ福音キリスト教会 府中チャペル",
                englishName = "Fuchu Hosanna Gospel Church",
                denominationId = "NOT_DETERMINED",
                address = "〒183-0005 東京都府中市若松町１丁目１−３",
                location = GeoPoint(35.6701735, 139.49467),
                websiteUrl = "https://hosannafucyu.wixsite.com/mysite",
            )
            val generated = StaticSiteGenerator().generate(
                churches = listOf(church),
                denominationEnglishNames = emptyMap(),
                outputDirectory = output,
            ).single()

            assertEquals("fuchu-hosanna-gospel-church.html", generated.fileName)
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
                    denominationEnglishNames = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
                    outputDirectory = output,
                )
            }

            val generated = StaticSiteGenerator().generate(
                churches = listOf(first, second),
                denominationEnglishNames = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
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
        val denominations = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan")

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
                denominationEnglishNames = mapOf("XLSX_18816F940131" to "Olivet Assembly Japan"),
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
        denominationId = "XLSX_18816F940131",
        address = "東京都新宿区西早稲田",
        location = GeoPoint(35.708, 139.709),
        websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
    )
}
