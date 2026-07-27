package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class InternationalChapelMinistriesDenominationChurchListCrawlerTest {
    private val crawler = InternationalChapelMinistriesDenominationChurchListCrawler("https://www.ikomachapel.org/iic%E3%81%AB%E3%81%A4%E3%81%84%E3%81%A6")

    @Test
    fun parsesICMChurchListFromTheOverviewPage() {
        val churches = crawler.parse(
            """
            <div>
            <p>ICMは現在は生駒市（奈良県）と京田辺市（京都府）に教会があります。</p>
            <p>〒630-0243 奈良県生駒市俵口町983-2</p>
            <p>TEL/FAX: 0743.74.4274</p>
            <p>Email: icmjimusho@gmail.com</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(2, churches.size)
        assertEquals("生駒インターナショナルチャペル", churches[0].name)
        assertEquals("奈良県", churches[0].jurisdiction)
        assertEquals("京田辺チャペル", churches[1].name)
        assertEquals("京都府", churches[1].jurisdiction)
    }
}