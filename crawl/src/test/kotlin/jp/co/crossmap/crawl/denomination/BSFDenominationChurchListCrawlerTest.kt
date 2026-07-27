package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BSFDenominationChurchListCrawlerTest {
    private val crawler = BSFDenominationChurchListCrawler("https://skk-jpn.com/")

    @Test
    fun parsesTheHeadquartersInfoForBSF() {
        val churches = crawler.parse(
            """
            <div>
            <p>聖書研究会 本部</p>
            <p>〒616-8228 京都市右京区常盤下田町９</p>
            <p>電話（０７５）８６１－２６１９</p>
            <p>ＦＡＸ（０７５）８６１－２７５０</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("聖書研究会", churches[0].name)
        assertTrue(churches[0].address.contains("京都市右京区"))
        assertTrue(churches[0].phone.contains("０７５"))
    }
}