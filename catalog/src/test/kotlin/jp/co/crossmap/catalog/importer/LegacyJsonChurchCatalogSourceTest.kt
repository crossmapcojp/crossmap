package jp.co.crossmap.catalog.importer

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameGenerationMethod
import jp.co.crossmap.LocalizedNameMetadata
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyJsonChurchCatalogSourceTest {
    private val source = LegacyJsonChurchCatalogSource()

    @Test
    fun normalizesNamesUrlsAndSocialDuplicatesDeterministically() {
        val record = source.normalize(
            church(
                localizedNames = listOf(LocalizedName("en-US", " Example Church ")),
                socialProfiles = listOf(
                    SocialProfile(SocialPlatform.FACEBOOK, "HTTPS://Example.COM/church/#top"),
                    SocialProfile(SocialPlatform.FACEBOOK, "https://example.com/church"),
                ),
            ),
            SourceMetadata("churches.json", "checksum", 0),
        )
        assertEquals(mapOf("en" to "Example Church", "ja" to "教会"), record.names.values)
        assertEquals("https://example.com/#top", record.website?.normalizedUrl)
        assertEquals(1, record.socialAccounts.size)
        assertEquals("https://example.com/church", record.socialAccounts.single().normalizedUrl)
    }

    @Test
    fun rejectsInvalidCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            source.normalize(church(location = GeoPoint(91.0, 0.0)), SourceMetadata("churches.json", "checksum", 0))
        }
    }

    @Test
    fun preservesBothChineseScriptsAsDirectCanonicalValuesWithoutDuplicatedMetadataBlob() {
        val reviewed = LocalizedNameMetadata(
            source = LocalizedNameSource.MANUAL,
            generationMethod = LocalizedNameGenerationMethod.EXACT_OVERRIDE,
            confidence = 1.0,
            reviewStatus = LocalizedNameReviewStatus.REVIEWED,
        )
        val record = source.normalize(
            church(
                localizedNames = listOf(
                    LocalizedName("zh-Hans", "东京恩典教会", reviewed),
                    LocalizedName("zh-Hant", "東京恩典教會", reviewed),
                ),
            ),
            SourceMetadata("churches.json", "checksum", 0),
        )

        assertEquals("东京恩典教会", record.names["zh-CN"])
        assertEquals("東京恩典教會", record.names["zh-TW"])
        assertEquals(setOf("zh-Hans", "zh-Hant"), record.localizedNames.map(LocalizedName::languageCode).filter { it.startsWith("zh-") }.toSet())
        assertEquals(null, record.localizedNames.single { it.languageCode == "zh-Hans" }.metadata)
    }

    private fun church(
        localizedNames: List<LocalizedName> = emptyList(),
        socialProfiles: List<SocialProfile> = emptyList(),
        location: GeoPoint = GeoPoint(35.0, 139.0),
    ) = ChurchRecord(
        id = "google:1",
        googleCid = "1",
        name = "教会",
        englishName = "Example Church",
        localizedNames = localizedNames,
        address = "Tokyo",
        location = location,
        websiteUrl = "HTTPS://Example.COM/#top",
        socialProfiles = socialProfiles,
    )
}
