package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JGPCDenominationChurchListCrawlerTest {
    private val crawler = JGPCDenominationChurchListCrawler(
        "https://jgpc.jimdofree.com/%E6%95%99%E4%BC%9A%E4%B8%80%E8%A6%A7/",
    )

    @Test
    fun parsesChurchRowsAndDistinguishesInternalDetailsFromExternalWebsites() {
        val churches = crawler.parse(
            """
            <table>
              <tr><td></td><td>教会名</td><td>牧師</td></tr>
              <tr><td>[ 北海道 ]</td><td><a href="https://www.sapporo.example/">札幌ペンテコステ教会</a></td><td>矢巻 邦彦師</td></tr>
              <tr><td>関 東</td></tr>
              <tr><td>[ 岐阜 ]</td><td></td><td><a href="/教会一覧/ハレルヤチャーチ岐阜/">ハレルヤチャーチ岐阜</a></td><td>大石 英城師</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(listOf("札幌ペンテコステ教会", "ハレルヤチャーチ岐阜"), churches.map { it.name })
        assertEquals("北海道", churches[0].jurisdiction)
        assertEquals("https://www.sapporo.example/", churches[0].websiteUrl)
        assertEquals("矢巻 邦彦", churches[0].ministers.single().name)
        assertEquals("岐阜県", churches[1].jurisdiction)
        assertEquals("", churches[1].websiteUrl)
        assertEquals(
            "https://jgpc.jimdofree.com/教会一覧/ハレルヤチャーチ岐阜/",
            churches[1].denominationChurchListDetailPage,
        )
    }

    @Test
    fun enrichesInternalDetailPagesAndAddsTheListJurisdictionToShortAddresses() {
        val church = OfficialDenominationChurch(
            name = "倉敷福音教会",
            jurisdiction = "岡山県",
            denominationChurchListDetailPage = "https://jgpc.jimdofree.com/detail/",
            ministers = ChurchMinisterParser.fromRoleAndNames("牧師", "和田 修三"),
        )
        val enriched = crawler.parseDetailPage(
            church,
            """
            <main>
              <p>牧師 和田 修三　伝道師 和田 満</p>
              <p>〒７１０－００１４ 倉敷市黒崎284－17</p>
              <p>Email: church@example.jp　電話: 086-462-2287</p>
              <a href="mailto:church@example.jp">Email</a>
            </main>
            """.trimIndent(),
        )

        assertEquals("〒710-0014 岡山県倉敷市黒崎２８４−１７", enriched.address)
        assertEquals("086-462-2287", enriched.phone)
        assertEquals("church@example.jp", enriched.email)
        assertEquals(listOf("和田 修三"), enriched.ministers.map { it.name })
    }
}
