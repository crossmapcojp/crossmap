package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class EasternGospelMessiahDenominationChurchListCrawlerTest {
    private val crawler = EasternGospelMessiahDenominationChurchListCrawler("https://ja.wikipedia.org/wiki/%E6%9D%B1%E6%B4%8B%E7%A6%8F%E9%9F%B3%E6%95%99%E5%9B%A3")

    @Test
    fun parsesTheWikipediaInfoboxForDenominationInfo() {
        val churches = crawler.parse(
            """
            <table class="infobox">
            <tr><th>教団名</th><td>東洋福音教団</td></tr>
            <tr><th>所在地</th><td>〒101-0062 東京都千代田区神田神保町１丁目２５番地</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("東洋福音教団", churches[0].name)
        assertEquals("東京都", churches[0].jurisdiction)
    }
}