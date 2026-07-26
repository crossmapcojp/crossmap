package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class ENCJDenominationChurchListCrawlerTest {
    @Test
    fun parsesDiviDirectoryCards() {
        val churches = ENCJDenominationChurchListCrawler("https://everynation.jp/directory/").parse(
            """
            <div class="et_pb_text_inner"><p>現在、2つの地域に7つの教会があります。</p></div>
            <div class="et_pb_column">
              <h4>エブリネイションチャーチ 横浜</h4>
              <p>ダウマ・スコット&amp;直美 主任牧師夫妻<br>細井・景介&amp;聖良 牧師夫妻</p>
              <p>〒231-0033 神奈川県横浜市中区長者町5丁目85番地 三共横浜ビル4階</p>
              <p>Tel: 045-315-3649 <a href="mailto:yokohama@everynation.jp">Email</a></p>
              <a href="http://www.everynation.jp/yokohama/">Website</a>
            </div>
            <div class="et_pb_column">
              <h4>エブリネイション・ハーベスト東京</h4>
              <p>リュウ・デイビット&amp;小絵 主任牧師夫妻</p>
              <p>〒116-0002 東京都荒川区荒川1-58-6</p>
              <p>Tel/Fax: 03-6755-2300 <a href="https://www.facebook.com/enharvesttokyo">Facebook</a></p>
            </div>
            <div class="et_pb_column">
              <h4>エブリネイション 静岡</h4>
              <p>クリスチャンソン・ビオン&amp;絵里 主任牧師夫妻</p>
              <p>〒420-0813 静岡市葵区長沼3丁目8番24号</p>
              <p>Tel: 090‐4182‐7926</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("エブリネイションチャーチ 横浜", "エブリネイション・ハーベスト東京", "エブリネイション 静岡"),
            churches.map { it.name },
        )
        assertEquals("〒231-0033 神奈川県横浜市中区長者町５丁目８５番地 三共横浜ビル４階", churches[0].address)
        assertEquals("045-315-3649", churches[0].phone)
        assertEquals("yokohama@everynation.jp", churches[0].email)
        assertEquals(
            listOf("ダウマ・スコット", "ダウマ・直美", "細井・景介", "細井・聖良"),
            churches[0].ministers.map { it.name },
        )
        assertEquals("http://www.everynation.jp/yokohama/", churches[0].websiteUrl)
        assertEquals("03-6755-2300", churches[1].fax)
        assertEquals(1, churches[1].socialProfiles.size)
        assertEquals("〒420-0813 静岡県静岡市葵区長沼３丁目８番２４号", churches[2].address)
        assertEquals("静岡県", churches[2].jurisdiction)
        assertEquals("090-4182-7926", churches[2].phone)
    }
}
