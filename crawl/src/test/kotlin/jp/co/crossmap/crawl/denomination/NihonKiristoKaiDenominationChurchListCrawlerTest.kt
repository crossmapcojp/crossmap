package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class NihonKiristoKaiDenominationChurchListCrawlerTest {
    private val crawler = NihonKiristoKaiDenominationChurchListCrawler("https://shibuya-kirisutokai.la.coocan.jp/nihonkirisutokai.htm")

    @Test
    fun parsesTheNihonKiristoKaiContactInfo() {
        val churches = crawler.parse(
            """
            <div>
            <p>日本基督会</p>
            <p>〒150-0001 東京都渋谷区神宮前１丁目２０番地</p>
            <p>Tel: 03-3401-1234</p>
            <p>Email: info@nihonkirisutokai.la.coocan.jp</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("日本基督会", churches[0].name)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("info@nihonkirisutokai.la.coocan.jp", churches[0].email)
    }
}