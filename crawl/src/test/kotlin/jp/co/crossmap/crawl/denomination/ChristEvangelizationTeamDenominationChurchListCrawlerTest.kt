package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class ChristEvangelizationTeamDenominationChurchListCrawlerTest {
    @Test
    fun parsesHeadingSectionsAndRestoresOmittedPrefectures() {
        val churches = ChristEvangelizationTeamDenominationChurchListCrawler(
            "https://dendoutai.org/churches/",
        ).parse(
            """
            <main><div class="post_content">
              <h2>平塚福音キリスト教会</h2>
              <div class="wp-block-columns"><p>URL：<a href="https://hiratsuka.example/">website</a></p>
                <p>主任牧師：山口 耕司</p><p>住所：平塚市夕陽ケ丘31-8</p><p>電話：0463-22-2384</p></div>
              <h2>貞光キリスト教会</h2>
              <div class="wp-block-columns"><p>牧師：木下 淳夫</p>
                <p>住所：美馬郡つるぎ町貞光字宮下78-11</p><p>電話：0883-62-3097</p></div>
              <h2>活動案内</h2><p>not a church</p>
            </div></main>
            """.trimIndent(),
        )

        assertEquals(listOf("平塚福音キリスト教会", "貞光キリスト教会"), churches.map { it.name })
        assertEquals("神奈川県平塚市夕陽ケ丘３１−８", churches.first().address)
        assertEquals("神奈川県", churches.first().jurisdiction)
        assertEquals("0463-22-2384", churches.first().phone)
        assertEquals("山口 耕司", churches.first().ministers.single().name)
        assertEquals("https://hiratsuka.example/", churches.first().websiteUrl)
        assertEquals("徳島県美馬郡つるぎ町貞光字宮下７８−１１", churches.last().address)
    }
}
