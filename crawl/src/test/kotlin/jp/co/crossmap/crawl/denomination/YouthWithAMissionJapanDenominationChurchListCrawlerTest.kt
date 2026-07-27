package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouthWithAMissionJapanDenominationChurchListCrawlerTest {
    private val crawler = YouthWithAMissionJapanDenominationChurchListCrawler("https://www.ywamjapan.org/ja/")

    @Test
    fun parsesYwamActivityLocationsFromTheHomepage() {
        val churches = crawler.parse(
            """
            <div class="js-id-location">
            最終更新 2022-10-13アイランドブリーズ・ジャパン場所｜関西地方・大阪府・寝屋川市
            </div>
            <div class="js-id-location">
            最終更新 2020-05-27ニセコ・ワイワム場所｜北海道・虻田郡・ニセコ町
            </div>
            <div class="js-id-location">
            最終更新 2020-04-16ホープチャペル鹿児島場所｜九州地方・鹿児島県・鹿児島市
            </div>
            """.trimIndent(),
        )

        assertEquals(3, churches.size)
        assertEquals("日本", churches[0].jurisdiction)
        assertEquals("アイランドブリーズ・ジャパン", churches[0].name)
        assertTrue(churches.map { it.name }.contains("ニセコ・ワイワム"))
    }
}