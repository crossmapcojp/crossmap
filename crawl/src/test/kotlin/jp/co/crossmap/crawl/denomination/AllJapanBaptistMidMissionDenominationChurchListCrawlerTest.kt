package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllJapanBaptistMidMissionDenominationChurchListCrawlerTest {
    private val crawler = AllJapanBaptistMidMissionDenominationChurchListCrawler("https://bmmjapan.org/")

    @Test
    fun parsesTheOrganizationInfoForAJBMM() {
        val churches = crawler.parse(
            """
            <div>
            <p>全日本バプテスト・ミド・ミッション宣教師団</p>
            <p>〒101-0062 東京都千代田区神田神保町１丁目２５番地</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("全日本バプテスト・ミド・ミッション宣教師団", churches[0].name)
        assertTrue(churches[0].address.contains("東京都"))
    }
}