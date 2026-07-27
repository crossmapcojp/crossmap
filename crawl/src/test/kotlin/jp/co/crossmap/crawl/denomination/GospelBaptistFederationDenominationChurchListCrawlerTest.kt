package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class GospelBaptistFederationDenominationChurchListCrawlerTest {
    private val crawler = GospelBaptistFederationDenominationChurchListCrawler("https://ja.wikipedia.org/wiki/%E7%A6%8F%E9%9F%B3%E3%83%90%E3%83%97%E3%83%86%E3%82%B9%E3%83%88%E9%80%A3%E5%90%88")

    @Test
    fun parsesTheGBFWikipediaPageForDenominationInfo() {
        val churches = crawler.parse(
            """
            <div>
            <p>福音バプテスト連合は日本のプロテスタントの教団です。</p>
            <p>〒101-0062 東京都千代田区神田神保町１丁目２５番地</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("福音バプテスト連合", churches[0].name)
        assertEquals("東京都", churches[0].jurisdiction)
    }
}