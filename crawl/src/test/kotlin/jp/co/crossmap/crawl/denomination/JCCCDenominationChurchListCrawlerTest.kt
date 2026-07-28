package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JCCCDenominationChurchListCrawlerTest {
    private val crawler = JCCCDenominationChurchListCrawler("https://tokyo-jcc.com/link5-j/")

    @Test
    fun parsesNumberedChurchRowsAndSkipsRegionalHeadingRows() {
        val churches = crawler.parse(
            """
            <table><tbody>
              <tr><th>#</th><th>Church</th><th>Address</th><th>Contact</th></tr>
              <tr><td>東京都 墨田区</td><td>東京都 墨田区</td><td>東京都 墨田区</td><td>東京都 墨田区</td></tr>
              <tr><td>18</td><td><a href="https://www.tokyochurch.org/">Tokyo Multicultural Church</a></td><td>東京都墨田区太平4丁目6番13号</td><td></td></tr>
              <tr><td>19</td><td>正道圣爱基督教会</td><td>東京都江戸川区北小岩2-5-12</td><td>羅華 牧师 080-3013-7680</td></tr>
            </tbody></table>
            """.trimIndent(),
        )

        assertEquals(2, churches.size)
        assertEquals("https://www.tokyochurch.org/", churches[0].websiteUrl)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("080-3013-7680", churches[1].phone)
        assertEquals("羅華", churches[1].ministers.single().name)
        assertEquals("zh-Hans", churches[1].localizedNames.single().languageCode)
    }
}
