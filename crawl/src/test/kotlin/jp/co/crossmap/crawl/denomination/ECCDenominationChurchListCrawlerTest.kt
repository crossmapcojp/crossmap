package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class ECCDenominationChurchListCrawlerTest {
    private val crawler = ECCDenominationChurchListCrawler("https://gracegardenchurch.com/introduction/overview/")

    @Test
    fun parsesECCChurchesFromTheOverviewPage() {
        val churches = crawler.parse(
            """
            <p>日本におけるEC教会</p>
            <p>経堂めぐみ教会 Kyodo Grace Church（東京都世田谷区）</p>
            <p>グレースガーデンチャペル（神奈川県海老名市）</p>
            <p>アメリカのEC教会 Grace Community Church of Willow Street</p>
            """.trimIndent(),
        )

        assertEquals(listOf("経堂めぐみ教会", "グレースガーデンチャペル", "アメリカEC教会"), churches.map { it.name })
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("神奈川県", churches[1].jurisdiction)
    }
}