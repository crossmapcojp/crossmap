package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class OCCJDenominationChurchListCrawlerTest {
    private val crawler = OCCJDenominationChurchListCrawler("https://www.occj.net/church")

    @Test
    fun pairsWixChurchTitlesWithTheirDetailButtons() {
        val churches = crawler.parse(
            """
            <p class="font_5">所屬各教會</p>
            <p class="font_5">宗教法人​ 大阪基督教生命堂</p>
            <a class="wixui-button" href="/osaka">查看更多</a>
            <p class="font_5">廣島基督教生命堂</p>
            <a class="wixui-button" href="/hiroshima">查看更多</a>
            <p class="font_5">熊本國際基督教生命堂</p>
            <a class="wixui-button" href="/kumamoto-international">查看更多</a>
            <p class="font_5">靜岡基督教生命堂</p>
            <a class="wixui-button" href="/shitsuoka">查看更多</a>
            """.trimIndent(),
        )

        assertEquals(
            listOf("大阪基督教生命堂", "広島基督教生命堂", "熊本国際基督教生命堂", "静岡基督教生命堂"),
            churches.map { it.name },
        )
        assertEquals("https://www.occj.net/osaka", churches[0].websiteUrl)
        assertEquals(churches[0].websiteUrl, churches[0].denominationChurchListDetailPage)
    }

    @Test
    fun enrichesOnlyTheLocalContactBlockAndNotTheDenominationFooter() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "大阪基督教生命堂",
                websiteUrl = "https://www.occj.net/osaka",
                denominationChurchListDetailPage = "https://www.occj.net/osaka",
            ),
            """
            <div data-testid="richTextElement">
              宗教法人・大阪基督教生命堂
              聚會地址：〒534-0014 大阪市都島区都島本通4-22-33
              電話：06-6921-6100 電子郵件：osaka@occj.net 莫牧師 090-3610-6300
            </div>
            <div data-testid="richTextElement">
              日本華僑基督教團 監督：石井豊 牧師 地址：〒534-0021 大阪市都島区都島本通4丁目22番33号
              TEL/FAX 06-6921-6100 EMAIL info@occj.net
            </div>
            """.trimIndent(),
        )

        assertEquals("〒534-0014 大阪府大阪市都島区都島本通４−２２−３３", enriched.address)
        assertEquals("大阪府", enriched.jurisdiction)
        assertEquals("06-6921-6100", enriched.phone)
        assertEquals("osaka@occj.net", enriched.email)
        assertEquals(emptyList(), enriched.ministers)
    }

    @Test
    fun restoresPrefecturesOmittedByLocalContactBlocks() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "名古屋基督教生命堂",
                denominationChurchListDetailPage = "https://www.occj.net/nagoya",
            ),
            """
            <div data-testid="richTextElement">
              〒457-0861 名古屋市南区明治2-25-5 info@occj.net
            </div>
            """.trimIndent(),
        )

        assertEquals("〒457-0861 愛知県名古屋市南区明治２−２５−５", enriched.address)
        assertEquals("愛知県", enriched.jurisdiction)
    }
}
