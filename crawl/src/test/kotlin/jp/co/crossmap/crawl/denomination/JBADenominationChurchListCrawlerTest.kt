package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JBADenominationChurchListCrawlerTest {
    @Test
    fun parsesJapaneseMemberBlocksAndExcludesTheCebuChurch() {
        val churches = JBADenominationChurchListCrawler("https://www.jbaptist.org/blank-2").parse(
            """
            <main><div id="comp-k1hgky6m1">
              <p>我孫子バプテスト教会</p>
              <p>牧師：天利 信勝　宣教師：天利 信司</p>
              <p>〒270-1144 千葉県我孫子市東我孫子1-1-3</p>
              <p>04-7185-0185 <a href="https://www.abikobaptistchurch.com/">HP</a>
                 <a href="https://www.facebook.com/abiko/">Facebook</a></p>
              <p>綾バプテスト教会 牧師：上園 英身</p>
              <p>宮崎県東諸県郡綾町大字南俣192-6</p>
              <p>0985-77-3593</p>
              <p>セブ日本語バプテスト教会</p>
              <p>MBIS, Cebu 6046, Philippines</p>
            </div></main>
            """.trimIndent(),
        )

        assertEquals(listOf("我孫子バプテスト教会", "綾バプテスト教会"), churches.map { it.name })
        assertEquals("〒270-1144 千葉県我孫子市東我孫子１−１−３", churches.first().address)
        assertEquals("千葉県", churches.first().jurisdiction)
        assertEquals("04-7185-0185", churches.first().phone)
        assertEquals(listOf("天利 信勝", "天利 信司"), churches.first().ministers.map { it.name })
        assertEquals("https://www.abikobaptistchurch.com/", churches.first().websiteUrl)
        assertEquals("FACEBOOK", churches.first().socialProfiles.single().platform.name)
        assertEquals("上園 英身", churches.last().ministers.single().name)
    }
}
