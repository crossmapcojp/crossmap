package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class BarnabasEvangelicalMissionSocietyDenominationChurchListCrawlerTest {
    private val crawler = BarnabasEvangelicalMissionSocietyDenominationChurchListCrawler("https://barnabas-missionary.amebaownd.com/")

    @Test
    fun parsesBemsContactInfoFromTheHomepage() {
        val churches = crawler.parse(
            """
            <div>
            <p>バルナバ福音宣教会</p>
            <p>住所 〒332-0034 埼玉県川口市並木4-1-1-301 多摩キリスト教会内</p>
            <p>電話番号 048-258-8169</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("バルナバ福音宣教会", churches[0].name)
        assertEquals("埼玉県", churches[0].jurisdiction)
        assertEquals("048-258-8169", churches[0].phone)
    }
}