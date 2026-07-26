package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JMSDenominationChurchListCrawlerTest {
    private val crawler = JMSDenominationChurchListCrawler(
        "https://nihonsenkyoukai.com/our-churches/",
    )

    @Test
    fun parsesChurchCardsAndDropsTheStaleKitamiVenue() {
        val churches = crawler.parse(
            """
            <div class="e-con e-child">
              <h2 class="elementor-heading-title">代田教会</h2>
              <p>主任牧師 小坂嘉嗣 〒156-0042 世田谷区羽根木1-19-2 TEL：03-3327-1108
                mail : daita_k@hotmail.co.jp <a href="https://maps.app.goo.gl/map">地図</a>
                <a href="https://daita-k.sakura.ne.jp/">公式サイト</a></p>
            </div>
            <div class="e-con e-child">
              <h2 class="elementor-heading-title">きさらづキリスト教会</h2>
              <p>主任牧師 重城博之 副牧師 重城美代子、重城あゆみ
                〒292-0016 千葉県木更津市高砂1-7-4 TEL/FAX：0438-41-8490</p>
            </div>
            <div class="e-con e-child">
              <h2 class="elementor-heading-title">喜多見チャペル</h2>
              <p>〒292-0016 東京都狛江市東和泉3-6-13
                <a href="https://kitamiupperroom.jp/">公式サイト</a></p>
            </div>
            """.trimIndent(),
        )

        assertEquals(listOf("代田教会", "きさらづキリスト教会", "喜多見チャペル"), churches.map { it.name })
        assertEquals("〒156-0042 東京都世田谷区羽根木１−１９−２", churches[0].address)
        assertEquals("https://daita-k.sakura.ne.jp/", churches[0].websiteUrl)
        assertEquals(listOf("小坂嘉嗣"), churches[0].ministers.map { it.name })
        assertEquals("0438-41-8490", churches[1].phone)
        assertEquals("0438-41-8490", churches[1].fax)
        assertEquals(
            listOf("重城博之" to "senior_pastor", "重城美代子" to "associate_pastor", "重城あゆみ" to "associate_pastor"),
            churches[1].ministers.map { it.name to it.roleId },
        )
        assertEquals("", churches[2].address)
    }
}
