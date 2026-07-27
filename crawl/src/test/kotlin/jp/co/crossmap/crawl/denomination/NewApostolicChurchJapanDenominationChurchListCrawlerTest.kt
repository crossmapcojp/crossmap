package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class NewApostolicChurchJapanDenominationChurchListCrawlerTest {
    private val crawler = NewApostolicChurchJapanDenominationChurchListCrawler("https://www.accjapan.org/")

    @Test
    fun parsesTheNewApostolicChurchJapanContactInfo() {
        val churches = crawler.parse(
            """
            <div>
            <p>日本使徒キリスト教会</p>
            <p>〒405-0064 山梨県笛吹市一宮町塩田692-2</p>
            <p>TEL/FAX : 055-347-1177</p>
            <p>Apostolic Christian Church of Japan</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("日本使徒キリスト教会", churches[0].name)
        assertEquals("山梨県", churches[0].jurisdiction)
        assertEquals("055-347-1177", churches[0].phone)
    }
}