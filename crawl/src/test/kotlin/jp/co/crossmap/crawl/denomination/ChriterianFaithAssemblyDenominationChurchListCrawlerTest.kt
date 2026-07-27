package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class ChriterianFaithAssemblyDenominationChurchListCrawlerTest {
    private val crawler = ChriterianFaithAssemblyDenominationChurchListCrawler("https://ja.wikipedia.org/wiki/%E3%82%AD%E3%83%AA%E3%82%B9%E3%83%88%E4%BF%A1%E5%BE%92%E3%81%AE%E9%9B%86%E4%BC%9A")

    @Test
    fun parsesTheDenominationPageForChurchInfo() {
        val churches = crawler.parse(
            """
            <div>
            <p>キリスト信徒の集会は、日本のプロテスタントの教会団体です。</p>
            <p>〒101-0062 東京都千代田区神田神保町１丁目２５番地</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("キリスト信徒の集会", churches[0].name)
        assertEquals("東京都", churches[0].jurisdiction)
    }
}