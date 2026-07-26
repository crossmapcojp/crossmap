package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JFMCDenominationChurchListCrawlerTest {
    private val crawler = JFMCDenominationChurchListCrawler("https://methodist-free.jp/")

    @Test
    fun parsesOnlyChurchLinksFromTheOfficialNavigation() {
        val churches = crawler.parse(
            """
            <ul id="menu-link">
              <li><a href="/日本自由メソヂスト教団/">日本自由メソヂスト教団</a></li>
              <li><a href="/岩屋キリスト教会/">岩屋キリスト教会</a></li>
              <li><a href="/葛城キリスト教会/">葛城キリスト教会</a></li>
              <li><a href="/教団からのお知らせ/">教団からのお知らせ</a></li>
            </ul>
            """.trimIndent(),
        )

        assertEquals(listOf("岩屋キリスト教会", "葛城キリスト教会"), churches.map { it.name })
        assertEquals("https://methodist-free.jp/岩屋キリスト教会/", churches[0].denominationChurchListDetailPage)
    }

    @Test
    fun enrichesStructuredChurchDetailsWithoutReadingSiteWideText() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "葛城キリスト教会",
                denominationChurchListDetailPage = "https://methodist-free.jp/葛城キリスト教会/",
            ),
            """
            <figure class="wp-block-table"><table><tbody>
              <tr><td>正式名称</td><td>日本自由メソヂスト葛城キリスト教会</td></tr>
              <tr><td>現住所</td><td>〒589-0011大阪府大阪狭山市半田6-1131-7</td></tr>
              <tr><td>電話番号</td><td>072-368-2518</td></tr>
              <tr><td>ファックス番号</td><td>同上</td></tr>
              <tr><td>牧師</td><td>米澤 澄子<br>安田 正幸</td></tr>
            </tbody></table></figure>
            <footer>電話番号 00-0000-0000　牧師 教団 太郎</footer>
            """.trimIndent(),
        )

        assertEquals("日本自由メソヂスト葛城キリスト教会", enriched.name)
        assertEquals("〒589-0011 大阪府大阪狭山市半田６−１１３１−７", enriched.address)
        assertEquals("大阪府", enriched.jurisdiction)
        assertEquals("072-368-2518", enriched.phone)
        assertEquals("072-368-2518", enriched.fax)
        assertEquals(
            listOf("米澤 澄子" to "pastor", "安田 正幸" to "pastor"),
            enriched.ministers.map { it.name to it.roleId },
        )
    }

    @Test
    fun parsesPastorRolesWrittenAfterNames() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "布施源氏ケ丘教会",
                denominationChurchListDetailPage = "https://methodist-free.jp/布施源氏ケ丘教会/",
            ),
            """
            <figure class="wp-block-table"><table><tbody>
              <tr><td>牧師</td><td>安藤 眞一　主任牧師<br>黒田 敏子　牧師<br>高須 純子　副牧師</td></tr>
            </tbody></table></figure>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "安藤 眞一" to "senior_pastor",
                "黒田 敏子" to "pastor",
                "高須 純子" to "associate_pastor",
            ),
            enriched.ministers.map { it.name to it.roleId },
        )
    }
}
