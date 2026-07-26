package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class TFMCDenominationChurchListCrawlerTest {
    @Test
    fun parsesChurchHeadingSectionsAndTheirStructuredTables() {
        val churches = TFMCDenominationChurchListCrawler("https://tokyofree.net/").parse(
            """
            <h2 class="wp-block-heading">東京フリー･メソジスト教団とは</h2>
            <figure class="wp-block-table"><table><tr><th>本部所在地</th><td>東京都多摩市</td></tr></table></figure>

            <h2 id="koganei" class="wp-block-heading">小金井教会</h2>
            <div class="wp-block-columns">
              <figure class="wp-block-table"><table>
                <tr><th>住所</th><td>小金井市本町5-8-7</td></tr>
                <tr><th>牧師</th><td>伊藤 真人</td></tr>
                <tr><th>伝道師</th><td>青木 恵介　青山 奈央</td></tr>
              </table></figure>
            </div>
            <div><a href="https://koganei.tokyofree.net/">小金井教会のホームページを見る</a></div>

            <h2 id="sakuragaoka" class="wp-block-heading">桜ケ丘キリスト教会</h2>
            <div class="wp-block-columns">
              <figure class="wp-block-table"><table>
                <tr><th>住所</th><td>東京都多摩市関戸3-14-12</td></tr>
                <tr><th>主任牧師</th><td>水口 功</td></tr>
                <tr><td><strong>牧師</strong></td><td>岩崎 星美</td></tr>
              </table></figure>
            </div>
            <div><a href="https://sakuragaoka.example/">桜ヶ丘キリスト教会のホームページを見る</a></div>

            <h2 id="senkyoushi-syoukai" class="wp-block-heading">宣教師の紹介</h2>
            """.trimIndent(),
        )

        assertEquals(listOf("小金井教会", "桜ケ丘キリスト教会"), churches.map { it.name })
        assertEquals("東京都小金井市本町５−８−７", churches[0].address)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("https://koganei.tokyofree.net/", churches[0].websiteUrl)
        assertEquals(
            listOf(
                "伊藤 真人" to "pastor",
                "青木 恵介" to "evangelist",
                "青山 奈央" to "evangelist",
            ),
            churches[0].ministers.map { it.name to it.roleId },
        )
        assertEquals(
            listOf("水口 功" to "senior_pastor", "岩崎 星美" to "pastor"),
            churches[1].ministers.map { it.name to it.roleId },
        )
    }
}
