package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class KECCJCCDenominationChurchListCrawlerTest {
    @Test
    fun parsesDomesticChurchRowsAndExcludesRelatedOrganizationsAndOverseasChurches() {
        val churches = KECCJCCDenominationChurchListCrawler("https://www.cumberland.jp/introduction/").parse(
            """
            <table><tbody>
              <tr><th>教会名</th><th>牧師</th><th>住所</th></tr>
              <tr>
                <td><a href="https://koza.example/">高座教会</a></td>
                <td>和田一郎<br>宮井岳彦</td>
                <td>神奈川県大和市南林間2-14-1</td>
              </tr>
              <tr>
                <td><a href="https://www.sagamino.org/">ー さがみ野チャペル</a></td>
                <td></td>
                <td>神奈川県座間市東原4-13-24</td>
              </tr>
              <tr>
                <td><a href="https://megumi.cumberland.jp/">めぐみ教会</a></td>
                <td>篠﨑千穂子</td>
                <td>東京都東大和市上北台3-355-4</td>
              </tr>
            </tbody></table>
            <table><tbody>
              <tr><td><a href="https://kindergarten.example/">認定こども園</a></td><td>神奈川県大和市</td></tr>
              <tr><td><a href="https://foreign.example/">ルイビル日本語教会</a></td><td>Louisville, KY</td></tr>
            </tbody></table>
            """.trimIndent(),
        )

        assertEquals(listOf("高座教会", "さがみ野チャペル", "めぐみ教会"), churches.map { it.name })
        assertEquals("神奈川県大和市南林間２−１４−１", churches[0].address)
        assertEquals("神奈川県", churches[0].jurisdiction)
        assertEquals(listOf("和田一郎", "宮井岳彦"), churches[0].ministers.map { it.name })
        assertEquals("https://koza.example/", churches[0].websiteUrl)
        assertEquals(emptyList(), churches[1].ministers)
        assertEquals("東京都", churches[2].jurisdiction)
    }
}
