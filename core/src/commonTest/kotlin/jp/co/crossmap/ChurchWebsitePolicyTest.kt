package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChurchWebsitePolicyTest {
    private val policy = ChurchWebsitePolicy(
        ChurchWebsitePolicy.parse(
            """
            christchurches-map.com
            kyokai.com
            church-info.jp # third-party directory
            """.trimIndent(),
        ),
    )

    @Test
    fun excludesExactAndSubdomainsButKeepsARealChurchWebsite() {
        assertTrue(policy.isExcluded("https://kyokai.com/church/123"))
        assertTrue(policy.isExcluded("https://www.church-info.jp/tokyo/church"))
        assertTrue(policy.isExcluded("https://map.christchurches-map.com/place"))
        assertTrue(policy.isExcluded("https://kyokai.com?church=123"))
        assertFalse(policy.isExcluded("https://haramachi-kyokai.com/"))
        assertFalse(policy.isExcluded("https://www.tokyobaptist.org/"))
    }

    @Test
    fun excludedOrMissingWebsiteFallsBackToTheGooglePlacePage() {
        assertEquals(
            "https://www.google.com/maps?cid=8998728770320543438",
            policy.publicWebsiteUrl(
                "https://kyokai.com/church/fusa",
                "8998728770320543438",
                "google:8998728770320543438",
            ),
        )
        assertEquals(
            "https://www.google.com/maps?cid=8998728770320543438",
            policy.publicWebsiteUrl("", null, "google:8998728770320543438"),
        )
    }

    @Test
    fun socialPlatformsAreNeverCrawledAsChurchWebsites() {
        listOf(
            "https://www.facebook.com/TKBCJapaneseSection/",
            "https://m.facebook.com/tokyo.church",
            "https://instagram.com/tokyo_church",
            "https://twitter.com/tokyo_church",
            "https://x.com/tokyo_church",
            "https://youtube.com/channel/UC123",
            "https://youtu.be/abc123",
        ).forEach { url ->
            assertTrue(policy.isSocialPlatform(url), url)
            assertFalse(policy.isCrawlableChurchWebsite(url), url)
        }
        assertFalse(policy.isSocialPlatform("https://tokyo-church.example/"))
        assertTrue(policy.isCrawlableChurchWebsite("https://tokyo-church.example/"))
    }

    @Test
    fun socialPlatformWebsiteUrlFallsBackToGooglePlacePage() {
        listOf(
            "https://www.facebook.com/TKBCJapaneseSection/",
            "https://m.facebook.com/tokyo.church",
            "https://instagram.com/tokyo_church",
            "https://x.com/tokyo_church",
            "https://youtube.com/channel/UC123",
        ).forEach { url ->
            assertEquals(
                "https://www.google.com/maps?cid=8998728770320543438",
                policy.publicWebsiteUrl(url, "8998728770320543438"),
                "social URL: $url",
            )
        }
    }
}
