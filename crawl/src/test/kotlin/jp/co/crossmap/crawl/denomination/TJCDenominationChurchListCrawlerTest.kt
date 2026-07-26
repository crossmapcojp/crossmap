package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class TJCDenominationChurchListCrawlerTest {
    private val crawler = TJCDenominationChurchListCrawler("https://www.tjc.or.jp/churchIndex#main")

    @Test
    fun parsesChurchPrayerHouseAndHouseGatheringCards() {
        val churches = crawler.parse(
            """
            <div class="pro">
              <dl class="clearfix">
                <dt><a href="/churchShow?id=1917081002#main"><img alt="東京教会"></a></dt>
                <dd><div class="des">〒359-0025 埼玉県所沢市上安松341-1</div>
                  <a href="https://www.youtube.com/channel/example/live">集会放送</a></dd>
              </dl>
              <dl class="clearfix">
                <dt><a href="/churchShow?id=1917081009#main"><img alt="神戸家庭集会"></a></dt>
                <dd><div class="des">〒652-0047 兵庫県神戶市兵庫区下沢通8丁目4-25</div></dd>
              </dl>
            </div>
            """.trimIndent(),
        )

        assertEquals(listOf("東京教会", "神戸家庭集会"), churches.map { it.name })
        assertEquals("〒359-0025 埼玉県所沢市上安松３４１−１", churches[0].address)
        assertEquals("https://www.tjc.or.jp/churchShow?id=1917081002", churches[0].denominationChurchListDetailPage)
        assertEquals(1, churches[0].socialProfiles.size)
        assertEquals("〒652-0047 兵庫県神戸市兵庫区下沢通８丁目４−２５", churches[1].address)
    }

    @Test
    fun enrichesContactFieldsFromDetailPage() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "東京教会",
                denominationChurchListDetailPage = "https://www.tjc.or.jp/churchShow?id=1917081002#main",
            ),
            """
            <div class="about1">
              <table class="table-condensed">
                <tr><th>電話番号：</th><td>042-994-8336</td></tr>
                <tr><th>郵便番号：</th><td>359-0025</td></tr>
                <tr><th>住所：</th><td>埼玉県所沢市上安松341-1</td></tr>
                <tr><th>Email：</th><td>tokyo@tjc.org</td></tr>
              </table>
            </div>
            <footer>電話番号： 050-3569-1917 Email： japan@tjc.org</footer>
            """.trimIndent(),
        )

        assertEquals("042-994-8336", enriched.phone)
        assertEquals("tokyo@tjc.org", enriched.email)
        assertEquals("〒359-0025 埼玉県所沢市上安松３４１−１", enriched.address)
    }
}
