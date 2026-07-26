package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class CCMJDenominationChurchListCrawlerTest {
    @Test
    fun parsesAffiliatedAndFellowshipChurchesButExcludesBibleInstitute() {
        val churches = CCMJDenominationChurchListCrawler("http://www.calvaryjapan.com/").parse(
            """
            <div class="paragraph">
              <strong>Calvary Chapels in Japan カルバリーチャペル 提携教会</strong><br><br>
              アバイド大阪 Abide Osaka 大阪市東住吉区(長居)<br>
              Pastor Joseph Totsis ジョセフ・トッツィス 牧師<br>
              <a href="https://abide.example/">Website</a><br><br>
              カルバリーチャペル名護 Calvary Chapel Nago, Okinawa 沖縄県名護市<br>
              Pastor Tim Newell ティム・ニューエル 牧師<br>
              <a href="https://facebook.com/ccnago">Website</a><br><br>
              他のカルバリーチャペルのフェローシップ教会 Other Fellowships<br><br>
              J’s Cafe カルバリーチャペル フェローシップ石巻 J’s Cafe, Ishinomaki 宮城県石巻市<br>
              Pastor Richard Giddens リチャード・ギデンス 牧師<br>
              <a href="https://ishinomaki.example/">Website</a><br><br>
              Other Calvary Chapel Ministries in Japan<br><br>
              カルバリー聖書学院 Calvary Bible Institute Okinawa 沖縄県宜野湾市<br>
              Tom Ruiz, Jr.- Director <a href="http://cbij.example/">Website</a>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("アバイド大阪", "カルバリーチャペル名護", "J’s Cafe カルバリーチャペル フェローシップ石巻"),
            churches.map { it.name },
        )
        assertEquals("Abide Osaka", churches[0].localizedNames.single().name)
        assertEquals("大阪市東住吉区(長居)", churches[0].address)
        assertEquals("大阪府", churches[0].jurisdiction)
        assertEquals("ジョセフ・トッツィス", churches[0].ministers.single().name)
        assertEquals("https://abide.example/", churches[0].websiteUrl)
        assertEquals("", churches[1].websiteUrl)
        assertEquals(SocialPlatform.FACEBOOK, churches[1].socialProfiles.single().platform)
        assertEquals("宮城県", churches[2].jurisdiction)
    }
}
