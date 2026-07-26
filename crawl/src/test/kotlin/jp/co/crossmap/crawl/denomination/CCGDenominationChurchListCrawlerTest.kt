package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class CCGDenominationChurchListCrawlerTest {
    @Test
    fun parsesDirectChurchRowsAndIgnoresSectionHeadings() {
        val churches = CCGDenominationChurchListCrawler(
            "https://www.yamatocalvarychapel.com/service/branch_01.php",
        ).parse(
            """
            <table>
              <tr><td><strong>支教会（国内）</strong></td></tr>
              <tr>
                <td><strong><a href="https://akita.example/">秋田カルバリー・チャペル</a></strong></td>
                <td>〒010-0851 秋田県秋田市手形十七流100-7 （高畑保之兄） Tel/Fax: 018-832-6683</td>
              </tr>
              <tr>
                <td><strong>和田浦カルバリー・チャペル</strong></td>
                <td>299-2702 千葉県安房郡和田町柴208 （釼持 美枝子伝道師） Tel: 0470-47-2680</td>
              </tr>
              <tr>
                <td><strong>グレイス・カルバリー・<br>フェローシップ</strong></td>
                <td>横浜カルバリーチャペルにて礼拝 TEL:045-900-0372</td>
              </tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(
            listOf("秋田カルバリー・チャペル", "和田浦カルバリー・チャペル", "グレイス・カルバリー・フェローシップ"),
            churches.map { it.name },
        )
        assertEquals("〒010-0851 秋田県秋田市手形十七流１００−７", churches.first().address)
        assertEquals("秋田県", churches.first().jurisdiction)
        assertEquals("018-832-6683", churches.first().phone)
        assertEquals("018-832-6683", churches.first().fax)
        assertEquals("https://akita.example/", churches.first().websiteUrl)
        assertEquals("釼持 美枝子", churches[1].ministers.single().name)
        assertEquals("", churches.last().address)
    }
}
