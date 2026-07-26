package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class ChristianFellowshipDenominationChurchListCrawlerTest {
    private val crawler = ChristianFellowshipDenominationChurchListCrawler(
        "https://nakano-psc.org/about/other_area/",
    )

    @Test
    fun parsesNamedAndOtherMeetingsWithoutPublishingDemolishedVenue() {
        val churches = crawler.parse(
            """
            <div id="post-597">
              <h2>茅ケ崎集会</h2>
              <p>〒253-0056 神奈川県茅ケ崎市共恵2-8-18<br>茅ヶ崎駅南口より徒歩10分</p>
              <table><tr><th>礼拝</th><td>毎週日曜日</td></tr></table>
              <h2>大阪集会</h2>
              <p>〒540-0016大阪市中央区神崎町4-5</p>
              <blockquote><p>大阪集会の建物は老朽化したため、解体することになりました。</p></blockquote>
              <h2>その他の集会</h2>
              <p>その他、<strong>名古屋・</strong><strong>京都</strong>・<strong>姫路</strong>でも集会を持ちます。</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("茅ケ崎集会", "大阪集会", "名古屋集会", "京都集会", "姫路集会"),
            churches.map { it.name },
        )
        assertEquals("〒253-0056 神奈川県茅ケ崎市共恵２−８−１８", churches[0].address)
        assertEquals("神奈川県", churches[0].jurisdiction)
        assertEquals("", churches[1].address)
    }
}
