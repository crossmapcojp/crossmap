package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChurchIdentityEnglishJapaneseAddressTest {
    @Test
    fun usesRomanizedJapaneseAddressScoreDuringOfficialDirectoryMatching() {
        val catalog = ChurchIdentity(
            name = "川越教会",
            address = "〒350-1133 埼玉県川越市砂新田143-5",
            websiteUrl = "",
            alternateNames = listOf("Kawagoe Church"),
        )

        assertNotNull(
            catalog.matchConfidence(
                ChurchIdentity(
                    name = "Kawagoe Church",
                    address = "143-5 Sunashinden, Kawagoe-shi, Saitama-ken 350-1133",
                    websiteUrl = "",
                ),
            ),
        )
        assertNull(
            catalog.matchConfidence(
                ChurchIdentity(
                    name = "Kawagoe Church",
                    address = "143-5 Sunashinden, Kawagoe-shi, Saitama-ken 350-0001",
                    websiteUrl = "",
                ),
            ),
        )
    }
}
