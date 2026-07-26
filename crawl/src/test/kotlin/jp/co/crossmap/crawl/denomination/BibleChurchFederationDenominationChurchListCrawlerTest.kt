package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleChurchFederationDenominationChurchListCrawlerTest {
    @Test
    fun parsesOfficialChurchRowsAndCombinedTelephoneFaxFields() {
        val churches = BibleChurchFederationDenominationChurchListCrawler(
            "http://www.kyoukai.com/rennmei/syo/hp/1ran.html",
        ).parse(
            """
            <table>
              <tr><td>内灘聖書教会</td><td>
                主任牧師：酒井 信也　伝道師：鳥井 志乃　伝道師：竹中 由季
                〒920-0277 石川県河北郡内灘町千鳥台3丁目13番地
                TEL&amp;FAX 076(237)7967
              </td></tr>
              <tr><td><a href="../../../mattou/index.html">松任聖書教会</a></td><td>
                牧師 塚田 安喜
                〒924-0075 石川県白山市米永町279-16
                TEL&amp;FAX 076(275)8449
              </td></tr>
              <tr><td><a href="https://kanazawa-nishi.biblechurch.jp/">金沢西聖書教会</a></td><td>
                〒921-8801 石川県野々市市御経塚2-258
                TEL&amp;FAX 076(249)1763
              </td></tr>
              <tr><td>聖書教会連盟</td><td>連盟のご案内</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(listOf("内灘聖書教会", "松任聖書教会", "金沢西聖書教会"), churches.map { it.name })
        assertEquals("〒920-0277 石川県河北郡内灘町千鳥台３丁目１３番地", churches[0].address)
        assertEquals("石川県", churches[0].jurisdiction)
        assertEquals("076-237-7967", churches[0].phone)
        assertEquals("076-237-7967", churches[0].fax)
        assertEquals(
            listOf(
                "酒井 信也" to "senior_pastor",
                "鳥井 志乃" to "evangelist",
                "竹中 由季" to "evangelist",
            ),
            churches[0].ministers.map { it.name to it.roleId },
        )
        assertEquals("http://www.kyoukai.com/mattou/index.html", churches[1].websiteUrl)
        assertEquals("https://kanazawa-nishi.biblechurch.jp/", churches[2].websiteUrl)
    }
}
