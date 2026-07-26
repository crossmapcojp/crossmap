package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class NLCCUDenominationChurchListCrawlerTest {
    private val crawler = NLCCUDenominationChurchListCrawler(
        "https://mccjapa8.wixsite.com/mccjapan/--c1k3t",
    )

    @Test
    fun parsesMachidaAndThreeLinkedMemberChurches() {
        val churches = crawler.parse(
            """
            <div id="MtrxGllry0-u32">
              <div class="wixui-gallery__item">
                <a href="http://www.glorychrist.com/"><img alt="キリストの栄光教会"></a>
              </div>
              <div class="wixui-gallery__item">
                <a href="http://hopechurch.holy.jp"><img alt="相模原ホープチャーチ"></a>
              </div>
              <div class="wixui-gallery__item">
                <a href="http://www.tadami-church.org/"><img alt="只見キリスト教会"></a>
              </div>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("町田クリスチャンセンター", "キリストの栄光教会", "相模原ホープチャーチ", "只見キリスト教会"),
            churches.map { it.name },
        )
        assertEquals("https://hopechurch.holy.jp/access/", churches[2].denominationChurchListDetailPage)
    }

    @Test
    fun prefersCurrentMachidaFooterContactAndDeobfuscatesEmail() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "町田クリスチャンセンター",
                websiteUrl = "https://mccjapa8.wixsite.com/mccjapan",
                denominationChurchListDetailPage = "https://mccjapa8.wixsite.com/mccjapan/access",
            ),
            """
            <div data-testid="richTextElement">〒194-0013 東京都町田市原町田4-18-3</div>
            <div data-testid="richTextElement">
              TEL 042-732-8341 FAX 042-732-8340
              〒194-0021. 東京都町田市中町1-15-11 U&amp;Eビル B1
              Email mccjapan☆nifty.com
            </div>
            """.trimIndent(),
        )

        assertEquals("〒194-0021 東京都町田市中町１−１５−１１ U&Eビル B１", enriched.address)
        assertEquals("042-732-8341", enriched.phone)
        assertEquals("042-732-8340", enriched.fax)
        assertEquals("mccjapan@nifty.com", enriched.email)
    }

    @Test
    fun enrichesHopeAndTadamiContacts() {
        val hope = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "相模原ホープチャーチ",
                denominationChurchListDetailPage = "https://hopechurch.holy.jp/access/",
            ),
            """<body>〒252-0336 相模原市南区当麻888-16 TEL/FAX 042-703-8603 牧師 佐藤 聡</body>""",
        )
        val tadami = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "只見キリスト教会",
                denominationChurchListDetailPage = "http://www.tadami-church.org/",
            ),
            """
            <body>〒968-0421 福島県南会津郡只見町只見字寺456-1 国道252号沿い
              Tel: 090-6025-8348 hiroyuki.ueno.tcc@gmail.com 牧師： 上野浩之</body>
            """.trimIndent(),
        )

        assertEquals("〒252-0336 神奈川県相模原市南区当麻８８８−１６", hope.address)
        assertEquals("042-703-8603", hope.phone)
        assertEquals("042-703-8603", hope.fax)
        assertEquals(listOf("佐藤 聡"), hope.ministers.map { it.name })
        assertEquals("〒968-0421 福島県南会津郡只見町只見字寺４５６−１", tadami.address)
        assertEquals("hiroyuki.ueno.tcc@gmail.com", tadami.email)
    }
}
