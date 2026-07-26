package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JCUCDenominationChurchListCrawlerTest {
    @Test
    fun parsesChurchHeadingSections() {
        val churches = JCUCDenominationChurchListCrawler("https://godo.or.jp/churches/").parse(
            """
            <article class="page-article">
              <p><strong>【東京都】</strong></p>
              <h3>板橋教会</h3>
              <p>住所：〒173-0004　東京都板橋区板橋3-32-1</p>
              <p>電話：03-3961-9685</p>
              <p>HP：<a href="https://godo-itabashi-church.jimdofree.com/">Website</a></p>
              <hr>
              <h3>世田谷中原教会</h3>
              <p>住所：〒155-0033　東京都世田谷区代田4-4-1</p>
              <p>電話：03-3323-0576</p>
              <p>HP：<a href="/nakahara/index.html">Website</a></p>
              <h3>御宿教会</h3>
              <p>住所：〒299-5102 千葉県椅隅郡御宿町久保1800-28</p>
              <p>電話：0470-68-3444</p>
            </article>
            """.trimIndent(),
        )

        assertEquals(listOf("板橋教会", "世田谷中原教会", "御宿教会"), churches.map { it.name })
        assertEquals("〒173-0004 東京都板橋区板橋３−３２−１", churches[0].address)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("03-3961-9685", churches[0].phone)
        assertEquals("https://godo-itabashi-church.jimdofree.com/", churches[0].websiteUrl)
        assertEquals("https://godo.or.jp/nakahara/index.html", churches[1].websiteUrl)
        assertEquals("〒299-5102 千葉県夷隅郡御宿町久保１８００−２８", churches[2].address)
    }
}
