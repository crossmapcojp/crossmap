package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class MennoniteBrethrenCouncilJpDenominationChurchListCrawlerTest {
    @Test
    fun parsesOfficialTableAndRestoresOmittedHokkaidoPrefix() {
        val churches = MennoniteBrethrenCouncilJpDenominationChurchListCrawler(
            "https://www.mennonite.jp/church/",
        ).parse(
            """
            <article><table>
              <tr><th>教会名（五十音順）</th><th>住所</th><th>連絡先</th><th>代表者・牧師等</th></tr>
              <tr><td><a href="https://asahikawa.example/">旭川キリスト教会</a></td>
                <td>旭川市末広4条2丁目3-2</td><td>0166-51-4071 church@example.jp</td><td>森 博</td></tr>
              <tr><td>教会外施設</td><td>札幌市</td><td></td><td></td></tr>
            </table></article>
            """.trimIndent(),
        )

        val church = churches.single()
        assertEquals("旭川キリスト教会", church.name)
        assertEquals("北海道旭川市末広４条２丁目３−２", church.address)
        assertEquals("北海道", church.jurisdiction)
        assertEquals("0166-51-4071", church.phone)
        assertEquals("church@example.jp", church.email)
        assertEquals("https://asahikawa.example/", church.websiteUrl)
        assertEquals("森 博", church.ministers.single().name)
    }
}
