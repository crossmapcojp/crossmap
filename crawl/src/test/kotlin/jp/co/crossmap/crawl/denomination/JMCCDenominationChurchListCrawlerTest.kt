package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JMCCDenominationChurchListCrawlerTest {
    @Test
    fun parsesOfficialChurchCardsAndRestoresOmittedPrefectures() {
        val churches = JMCCDenominationChurchListCrawler("https://mennonite.jpn.org/").parse(
            """
            <div id="churchs"><ul class="special-icons">
              <li><div class="box"><h3>神戸メノナイト・キリスト教会</h3>
                〒652-0016 <a href="https://kobe.example/">ホームページ</a>
                神戸市兵庫区馬場町18-24 【代表】野村竹二</div></li>
              <li><div class="box"><h3>愛宕キリスト教会</h3>
                〒882-0872 <a href="atago.html">詳しくはコチラ</a>
                延岡市愛宕町2-3-2〒874-0919 0982-33-2218 【牧師】片伯部千鶴子</div></li>
              <li><div class="box"><h3>霧島キリスト教兄弟団</h3>
                〒886-0004 宮崎県小林市大字細野448-3 0984-22-2658 【協力牧師】前川吉晴</div></li>
            </ul></div>
            """.trimIndent(),
        )

        assertEquals(3, churches.size)
        assertEquals("〒652-0016 兵庫県神戸市兵庫区馬場町１８−２４", churches[0].address)
        assertEquals("兵庫県", churches[0].jurisdiction)
        assertEquals("https://kobe.example/", churches[0].websiteUrl)
        assertEquals("〒882-0872 宮崎県延岡市愛宕町２−３−２", churches[1].address)
        assertEquals("0982-33-2218", churches[1].phone)
        assertEquals("片伯部千鶴子", churches[1].ministers.single().name)
        assertEquals("霧島キリスト教兄弟団", churches[2].name)
    }
}
