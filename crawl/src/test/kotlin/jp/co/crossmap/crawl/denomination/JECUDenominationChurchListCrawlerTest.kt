package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JECUDenominationChurchListCrawlerTest {
    @Test
    fun parsesThreeRowChurchRecordsFromTheShiftJisDirectoryLayout() {
        val crawler = JECUDenominationChurchListCrawler("https://church.ne.jp/jecu/link.htm")
        val churches = crawler.parse(
            """
            <table>
              <tr><td>東関東地区協議会</td><td>東関東地区協議会</td><td>東関東地区協議会</td><td>東関東地区協議会</td></tr>
              <tr><td>○</td><td>富士見丘キリスト教会</td><td>広沢 裕・久子</td><td>Email: church@example.jp</td></tr>
              <tr><td></td><td>〒371-0103</td><td></td><td><a href="https://fujimi.example/">website</a></td></tr>
              <tr><td></td><td>群馬県前橋市富士見町小暮1589-48</td><td>O27-288-9142</td><td></td></tr>
            </table>
            """.trimIndent(),
        )

        val church = churches.single()
        assertEquals("富士見丘キリスト教会", church.name)
        assertEquals("〒371-0103 群馬県前橋市富士見町小暮１５８９−４８", church.address)
        assertEquals("東関東地区協議会", church.jurisdiction)
        assertEquals("027-288-9142", church.phone)
        assertEquals("church@example.jp", church.email)
        assertEquals("https://fujimi.example/", church.websiteUrl)
        assertEquals("広沢 裕", church.ministers.single().name)
    }
}
