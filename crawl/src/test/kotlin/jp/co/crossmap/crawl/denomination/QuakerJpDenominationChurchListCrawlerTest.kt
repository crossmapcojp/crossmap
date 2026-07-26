package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class QuakerJpDenominationChurchListCrawlerTest {
    private val crawler = QuakerJpDenominationChurchListCrawler(
        "https://quakerjapan.wixsite.com/tokyogekkai/about-us",
    )

    @Test
    fun parsesJapaneseMonthlyMeetingsAndExcludesWorldwideBranches() {
        val churches = crawler.parse(
            """
            <section id="comp-lbenz59n">
              <div data-testid="richTextElement"><h2 class="font_2">
                <span class="backcolor_21">日本年会/東京月会</span><br>
                <span>​東京都港区三田4-8-19</span><br>
                <span>日曜礼拝: 毎日曜10:30 - 11:30</span>
              </h2></div>
              <a href="https://quakerjapan.wixsite.com/tokyogekkai/tokyo"></a>
              <div data-testid="richTextElement"><h2 class="font_2">
                <a href="https://quakerjapan.wixsite.com/tokyogekkai/osaka">
                  <span class="backcolor_21">大阪月会</span>
                </a><br>
                <span>開催場所については日本年会までお問合せください。</span>
              </h2></div>
              <div data-testid="richTextElement"><h2 class="font_2">
                <span class="backcolor_21">土浦月会</span><br>
                <span>​茨城県土浦市文京町2-20</span>
              </h2></div>
              <div data-testid="richTextElement">
                <h2 class="font_2">FWCC Friends World Committee for Consultation</h2>
              </div>
            </section>
            """.trimIndent(),
        )

        assertEquals(listOf("日本年会/東京月会", "大阪月会", "土浦月会"), churches.map { it.name })
        assertEquals("東京都港区三田４−８−１９", churches[0].address)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals(
            "https://quakerjapan.wixsite.com/tokyogekkai/tokyo",
            churches[0].websiteUrl,
        )
        assertEquals("", churches[1].address)
        assertEquals("茨城県土浦市文京町２−２０", churches[2].address)
        assertEquals("", churches[2].websiteUrl)
    }
}
