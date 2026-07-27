package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JapanBethelMissionDenominationChurchListCrawlerTest {
    private val crawler = JapanBethelMissionDenominationChurchListCrawler("https://japanbethelmission.com/")

    @Test
    fun parsesChurchNamesFromTheHomePage() {
        val churches = crawler.parse(
            """
            <nav>
            <ul class="sub-menu">
            <li><a href="https://japanbethelmission.com/rikuzentakata/">陸前高田キリスト教会</a></li>
            <li><a href="https://japanbethelmission.com/fussa/">福生ベテル教会</a></li>
            <li><a href="https://japanbethelmission.com/munakata/">宗像ベテルクリスチャンセンター</a></li>
            <li><a href="https://japanbethelmission.com/kurume/">久留米ベテルキリスト教会</a></li>
            </ul>
            </nav>
            """.trimIndent(),
        )

        assertEquals(4, churches.size)
        assertEquals("陸前高田キリスト教会", churches[0].name)
        assertEquals("https://japanbethelmission.com/rikuzentakata/", churches[0].denominationChurchListDetailPage)
        assertEquals("福生ベテル教会", churches[1].name)
    }
}