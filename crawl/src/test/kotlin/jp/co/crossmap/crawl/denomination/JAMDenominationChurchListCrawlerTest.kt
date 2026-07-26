package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class JAMDenominationChurchListCrawlerTest {
    @Test
    fun mapsTheOfficialEnglishDirectoryToVerifiedJapaneseChurchIdentities() {
        val churches = JAMDenominationChurchListCrawler(
            "https://japanalliancemission.org/info/alliance-church-network/",
        ).parse(
            """
            <div class="location">
              <div class="information">
                <h4>Sengendai Christ Church</h4>
                <p>+81 48-978-1167<br>1-8-15 Sengendai Nishi Koshigaya Shi, Saitama Ken 343-0021</p>
                <a href="https://www.facebook.com/sengendaichurch/">Facebook</a>
                <a href="http://sengendaichurch.org">Website</a>
              </div>
            </div>
            <div class="location">
              <div class="information">
                <h4>Yachiyo Alliance Mission Church</h4>
                <p>+81 47-409-1230<br>5-8-17 Yachiyodai Kita Yachiyo Shi, Chiba Ken 276-0031</p>
                <a href="https://www.facebook.com/yachiyoamc/">Facebook</a>
                <a href="http://yamc-jp.com">Website</a>
              </div>
            </div>
            <div class="location"><h4>Alliance Bible Institute</h4></div>
            """.trimIndent(),
        )

        assertEquals(listOf("千間台キリスト教会", "八千代福音キリスト教会"), churches.map { it.name })
        assertEquals("〒343-0041 埼玉県越谷市千間台西１丁目８−１５", churches[0].address)
        assertEquals("048-978-1167", churches[0].phone)
        assertEquals("http://sengendaichurch.org", churches[0].websiteUrl)
        assertEquals(
            listOf("千間台キリスト教会", "Sengendai Christ Church"),
            churches[0].localizedNames.map { it.name },
        )
        assertEquals(listOf(SocialPlatform.FACEBOOK), churches[0].socialProfiles.map { it.platform })
        assertEquals("八千代福音キリスト教会", churches[1].name)
        assertEquals("http://yamc-jp.com", churches[1].websiteUrl)
    }
}
