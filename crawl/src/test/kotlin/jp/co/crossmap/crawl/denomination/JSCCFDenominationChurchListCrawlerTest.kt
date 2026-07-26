package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JSCCFDenominationChurchListCrawlerTest {
    @Test
    fun parsesNumberedOfficialDirectoryEntries() {
        val churches = JSCCFDenominationChurchListCrawler("https://seisen-rengou.blogspot.com/directory").parse(
            """
            <div class="post-body">
              日本聖泉キリスト教会連合に所属する教会を紹介します。
              盛岡聖泉キリスト教会
              ①1958年8月 ②〒020-0016 岩手県盛岡市名須川町21-10 ※連絡先は本部となります。
              ③0196-22-2685/fax0196-22-4118
              ④中野直文: moseisen2110（アット・マーク）yahoo.co.jp http://www.nnet.ne.jp/~pom/
              ⑤中野直文(1969)・中野與子(1994)
              仙台聖泉キリスト教会
              ①1951年6月30日 ②〒980-0811 宮城県仙台市青葉区一番町1-11-25
              ③022-266-8773（Fax共）
              ④山本嘉納: eagles7（アット・マーク）rk9.so-net.ne.jp http://sendai-seisen.net/
              ⑤山本嘉納(1991)・山本盡子(1990)
              所沢ミレニアムチャーチ（盛岡教会伝道所）
              ①2006年4月9日 ②〒359-1105 埼玉県所沢市青葉台1337-1-508
              ③04-2939-8470（Fax共） http://millenniumchurch.blog11.fc2.com/
              ④長谷川与志充: toyoshi（アット・マーク）io.ocn.ne.jp
              ⑤長谷川与志充(1994)
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("盛岡聖泉キリスト教会", "仙台聖泉キリスト教会", "所沢ミレニアムチャーチ（盛岡教会伝道所）"),
            churches.map { it.name },
        )
        assertEquals("〒020-0016 岩手県盛岡市名須川町２１−１０", churches[0].address)
        assertEquals("0196-22-2685", churches[0].phone)
        assertEquals("0196-22-4118", churches[0].fax)
        assertEquals("moseisen2110@yahoo.co.jp", churches[0].email)
        assertEquals("http://www.nnet.ne.jp/~pom/", churches[0].websiteUrl)
        assertEquals(listOf("中野直文", "中野與子"), churches[0].ministers.map { it.name })
        assertEquals("022-266-8773", churches[1].fax)
        assertEquals("埼玉県", churches[2].jurisdiction)
    }
}
