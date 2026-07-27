package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class MinoMissionDenominationChurchListCrawlerTest {
    private val crawler = MinoMissionDenominationChurchListCrawler("https://www.cty-net.ne.jp/~mmi/church.html")

    @Test
    fun parsesChurchNamesFromTheChurchesPage() {
        val churches = crawler.parse(
            """
            <h3>富田浜聖書教会</h3>
            <a href="tomidahama.html">詳細</a>
            <h3>大垣・高田聖書教会</h3>
            <a href="oogaki.html">詳細</a>
            <h3>追分聖書教会</h3>
            <a href="oiwake.html">詳細</a>
            <h3>コンテンツ</h3>
            """.trimIndent(),
        )

        assertEquals(listOf("富田浜聖書教会", "大垣・高田聖書教会", "追分聖書教会"), churches.map { it.name })
        assertEquals(3, churches.size)
    }
}