package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JCGADenominationChurchListCrawlerTest {
    @Test
    fun parsesWixChurchBlocksAndStopsBeforeTheHeadOfficeFooter() {
        val churches = JCGADenominationChurchListCrawler("https://www.japanchurchofgod.org/family").parse(
            """
            <div data-testid="richTextElement"><h2>所 属 教 会</h2></div>
            <div data-testid="richTextElement">
              <h1><a data-popupid="one">伊勢原ガーデンチャペル</a></h1>
              <p>神奈川県伊勢原市岡崎6871-5</p>
              <p>TEL0463-96-1403</p>
              <p>牧師：伊藤 正登</p>
            </div>
            <div data-testid="richTextElement">
              <h1><a data-popupid="two">湘南​セントラルチャーチ</a></h1>
              <p>神奈川県藤沢市湘南台4-1-11</p>
              <p>TEL0466-43-0600 牧師：伊藤正登</p>
            </div>
            <div data-testid="richTextElement">
              <h1><a href="https://jcentershibuya.example/">J - Center</a></h1>
              <p>東京都渋谷区道玄坂2-16-7 花菱ビル6F</p>
              <p>TEL090-3431-0301 牧師：八束 慰也</p>
            </div>
            <div data-testid="richTextElement"><p>©2018 Japan Church of God.</p></div>
            <div data-testid="richTextElement"><p>日本チャーチオブゴッド教団 本部 〒146-0093 東京都大田区矢口2-1-18</p></div>
            """.trimIndent(),
        )

        assertEquals(listOf("伊勢原ガーデンチャペル", "湘南セントラルチャーチ", "J - Center"), churches.map { it.name })
        assertEquals("神奈川県伊勢原市岡崎６８７１−５", churches[0].address)
        assertEquals("神奈川県", churches[0].jurisdiction)
        assertEquals("0463-96-1403", churches[0].phone)
        assertEquals("伊藤 正登", churches[0].ministers.single().name)
        assertEquals("神奈川県藤沢市湘南台４−１−１１", churches[1].address)
        assertEquals("https://jcentershibuya.example/", churches[2].websiteUrl)
        assertEquals("東京都渋谷区道玄坂２−１６−７ 花菱ビル６F", churches[2].address)
        assertEquals("八束 慰也", churches[2].ministers.single().name)
    }
}
