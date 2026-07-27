package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class TokyoHorizonChapelDenominationChurchListCrawlerTest {
    private val crawler = TokyoHorizonChapelDenominationChurchListCrawler("https://horizonchapel.jp/service")

    @Test
    fun parsesChapelNamesFromTheServicePage() {
        val churches = crawler.parse(
            """
            <h3>町田チャペル</h3>
            <table><tr><th>曜日</th><td>日曜日</td></tr></table>
            <h3>世田谷チャペル</h3>
            <table><tr><th>曜日</th><td>日曜日</td></tr></table>
            """.trimIndent(),
        )

        assertEquals(listOf("町田チャペル", "世田谷チャペル"), churches.map { it.name })
        assertEquals(2, churches.size)
        assertEquals("東京都", churches[0].jurisdiction)
    }
}