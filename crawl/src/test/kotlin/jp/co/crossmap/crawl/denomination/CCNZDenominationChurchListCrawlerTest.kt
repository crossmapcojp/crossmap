package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class CCNZDenominationChurchListCrawlerTest {
    private val crawler = CCNZDenominationChurchListCrawler(
        listOf(
            "https://www.ccnz.jp/group/",
            "https://www.ccnz.jp/church/access.html",
        ),
    )

    @Test
    fun parsesBranchChurchesWithoutMistakingTheOsakaContactForTheirAddress() {
        val churches = crawler.parsePage(
            "https://www.ccnz.jp/group/",
            """
            <div class="grBox">
              <h4>横浜クリスチャンの集い</h4>
              <table>
                <tr><th>連絡先</th><td>大阪教会</td></tr>
                <tr><th>住所</th><td>〒565-0875 大阪府吹田市青山台3-52-1</td></tr>
                <tr><th>TEL</th><td>06-6387-8178</td></tr>
              </table>
              <table>
                <tr><th>会場</th><td>生麦地区センター</td></tr>
                <tr><th>住所</th><td>〒230-0052 神奈川県横浜市鶴見区生麦4-6-37</td></tr>
                <tr><th>TEL</th><td>080-4912-4729</td></tr>
              </table>
            </div>
            <div class="grBox">
              <h4>京都バイブルチャーチ</h4>
              <table>
                <tr><th>責任者</th><td>天野 賢雄</td></tr>
                <tr><th>住所</th><td>〒607-8022 京都府京都市山科区四ノ宮小金塚8-706</td></tr>
                <tr><th>TEL</th><td>075-644-5398</td></tr>
              </table>
            </div>
            <div class="grBox">
              <h4>西脇クリスチャンの集い</h4>
              <table>
                <tr><th>責任者</th><td>坂本 誠一</td></tr>
                <tr><th>連絡先</th><td>大阪教会</td></tr>
                <tr><th>住所</th><td>〒565-0875 大阪府吹田市青山台3-52-1</td></tr>
              </table>
            </div>
            """.trimIndent(),
        )

        assertEquals(listOf("横浜クリスチャンの集い", "京都バイブルチャーチ", "西脇クリスチャンの集い"), churches.map { it.name })
        assertEquals("〒230-0052 神奈川県横浜市鶴見区生麦４−６−３７", churches[0].address)
        assertEquals("神奈川県", churches[0].jurisdiction)
        assertEquals("080-4912-4729", churches[0].phone)
        assertEquals("", churches[0].note)
        assertEquals("〒607-8022 京都府京都市山科区四ノ宮小金塚８−７０６", churches[1].address)
        assertEquals("京都府", churches[1].jurisdiction)
        assertEquals(listOf("天野 賢雄"), churches[1].ministers.map { it.name })
        assertEquals("", churches[2].address)
        assertEquals("兵庫県", churches[2].jurisdiction)
        assertEquals("公式一覧は大阪教会を連絡先として掲載", churches[2].note)
    }

    @Test
    fun parsesTheOsakaParentChurchAccessPage() {
        val churches = crawler.parsePage(
            "https://www.ccnz.jp/church/access.html",
            """
            <header>TEL 06-6387-8178</header>
            <main>
              <p>宗教法人チャーチオブクライストニュージーランド日本 大阪教会</p>
              <p>〒565-0875大阪府吹田市青山台3丁目52番1号</p>
              <p>TEL:06-6387-8178</p>
              <p>FAX:06-6387-8161</p>
              <a href="mailto:osaka@ccnz.jp">osaka@ccnz.jp</a>
            </main>
            """.trimIndent(),
        )

        assertEquals(1, churches.size)
        assertEquals("チャーチオブクライストニュージーランド日本 大阪教会", churches.single().name)
        assertEquals("〒565-0875大阪府吹田市青山台３丁目５２番１号", churches.single().address)
        assertEquals("大阪府", churches.single().jurisdiction)
        assertEquals("06-6387-8178", churches.single().phone)
        assertEquals("06-6387-8161", churches.single().fax)
        assertEquals("osaka@ccnz.jp", churches.single().email)
    }
}
