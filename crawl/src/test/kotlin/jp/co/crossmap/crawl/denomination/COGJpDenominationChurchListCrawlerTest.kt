package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class COGJpDenominationChurchListCrawlerTest {
    @Test
    fun parsesDomesticChurchesAndExcludesForeignMissionFields() {
        val churches = COGJpDenominationChurchListCrawler(
            "https://www.cogjapan.com/member-churches.html",
        ).parse(
            """
            <main>
              <div class="paragraph">酒田キリスト教会<br>（山形県）</div>
              <div class="wsite-image"><a href="http://jesus-sakata.example/"><img></a></div>
              <div><a href="https://www.youtube.com/channel/sakata">YouTube</a></div>
              <div class="paragraph">川崎キリスト教会<br>（神奈川県）</div>
              <div class="wsite-image"><a><img></a></div>
              <h2>宣教地</h2>
              <div class="paragraph">グアダラハラ教会（メキシコ）</div>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("酒田キリスト教会", "川崎キリスト教会"), churches.map { it.name })
        assertEquals("山形県", churches.first().jurisdiction)
        assertEquals("http://jesus-sakata.example/", churches.first().websiteUrl)
        assertEquals(SocialPlatform.YOUTUBE, churches.first().socialProfiles.single().platform)
    }
}
