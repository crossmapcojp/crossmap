package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class GFADenominationChurchListCrawlerTest {
    @Test
    fun parsesLegacyChurchRowsAndDeduplicatesAncestorTables() {
        val churches = GFADenominationChurchListCrawler("http://fkk-web.net/church/church.html").parse(
            """
            <font color="#990000"><span>所属教会 京都聖書教会</span></font>
            <table><tr><td><font color="#990000">京都聖書教会</font></td></tr>
              <tr><td>〒603-8425 京都府京都市北区紫竹緑町80</td></tr>
              <tr><td>TEL：075-492-2384/FAX：同 E-mail：kyoto1952＠yahoo.co.jp
                <a href="https://kyoto.example/">URL</a> 牧師：閨谷 欣也</td></tr>
            </table>
            <table><tr><td><font color="#990000">高石聖書教会</font></td></tr>
              <tr><td>〒592-0003 大阪府高石市東羽衣6-17-35</td></tr>
              <tr><td>TEL：072-261-7348 牧師：清水 担 協力牧師：清水 昭三</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(listOf("京都聖書教会", "高石聖書教会"), churches.map { it.name })
        assertEquals("〒603-8425 京都府京都市北区紫竹緑町８０", churches[0].address)
        assertEquals("京都府", churches[0].jurisdiction)
        assertEquals("075-492-2384", churches[0].phone)
        assertEquals("075-492-2384", churches[0].fax)
        assertEquals("kyoto1952@yahoo.co.jp", churches[0].email)
        assertEquals("https://kyoto.example/", churches[0].websiteUrl)
        assertEquals(listOf("清水 担", "清水 昭三"), churches[1].ministers.map { it.name })
    }
}
