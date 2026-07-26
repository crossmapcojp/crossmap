package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class LECCDenominationChurchListCrawlerTest {
    private val crawler = LECCDenominationChurchListCrawler("https://www.leccjapan.com/churches")

    @Test
    fun parsesTheSixChurchLinksAndKeepsTheIndependentMitoWebsite() {
        val churches = crawler.parse(
            """
            <header><a href="/about">About</a></header>
            <main>
              <a href="/tokyo">Tokyo</a>
              <a href="/utsunomiya">Utsunomiya</a>
              <a href="/chiba">Chiba</a>
              <a href="https://www.kaminomegumi.net/">Mito</a>
              <a href="/ashikaga">Ashikaga</a>
              <a href="/tsuchiura">Tsuchiura</a>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("Tokyo", "Utsunomiya", "Chiba", "Mito", "Ashikaga", "Tsuchiura"), churches.map { it.name })
        assertEquals("https://www.leccjapan.com/tokyo", churches.first().denominationChurchListDetailPage)
        assertEquals("", churches.first().websiteUrl)
        assertEquals("https://www.kaminomegumi.net/", churches[3].websiteUrl)
        assertEquals("https://www.kaminomegumi.net/", churches[3].denominationChurchListDetailPage)
    }

    @Test
    fun enrichesSquarespaceChurchDetails() {
        val church = OfficialDenominationChurch(
            name = "Tokyo",
            denominationChurchListDetailPage = "https://www.leccjapan.com/tokyo",
        )
        val enriched = crawler.parseDetailPage(
            church,
            """
            <main>
              <h1>あがないルーテル福音キリスト教会</h1>
              <h2>Tokyo Aganai Evangelical Lutheran Church</h2>
              <p>〒203-0052 東京都東久留米市幸町3-2-17</p>
              <p>3 Chome-2-17 Saiwaicho, Higashikurume, Tokyo, Japan 203-0052</p>
              <p>TEL: 042-471-1855</p>
              <p>This church is led by Pastor Daisuke Nakamoto.</p>
            </main>
            """.trimIndent(),
        )

        assertEquals("あがないルーテル福音キリスト教会", enriched.name)
        assertEquals("Tokyo Aganai Evangelical Lutheran Church", enriched.localizedNames.single().name)
        assertEquals("〒203-0052 東京都東久留米市幸町３−２−１７", enriched.address)
        assertEquals("東京都", enriched.jurisdiction)
        assertEquals("042-471-1855", enriched.phone)
        assertEquals("Daisuke Nakamoto", enriched.ministers.single().name)
    }

    @Test
    fun removesAClosingParenthesisThatBelongsToTheSurroundingContactSentence() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "Tsuchiura",
                denominationChurchListDetailPage = "https://www.leccjapan.com/tsuchiura",
            ),
            """
            <main>
              <h1>のぞみルーテル福音キリスト教会</h1>
              <p>〒300-0823 茨城県土浦市小松3-23-27</p>
              <p>詳しくは仁平牧師まで（TEL: 028-653-6353) お問い合わせください。</p>
            </main>
            """.trimIndent(),
        )

        assertEquals("028-653-6353", enriched.phone)
    }

    @Test
    fun preservesKatakanaLongVowelsInAddresses() {
        val enriched = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "Chiba",
                denominationChurchListDetailPage = "https://www.leccjapan.com/chiba",
            ),
            """
            <main>
              <h1>ともしびルーテル福音キリスト教会</h1>
              <p>〒285-0858 千葉県佐倉市ユーカリが丘1-34-5</p>
            </main>
            """.trimIndent(),
        )

        assertEquals("〒285-0858 千葉県佐倉市ユーカリが丘１−３４−５", enriched.address)
    }

    @Test
    fun enrichesTheIndependentMitoSiteAndRemovesInvisibleHeadingCharacters() {
        val church = OfficialDenominationChurch(
            name = "Mito",
            websiteUrl = "https://www.kaminomegumi.net/",
            denominationChurchListDetailPage = "https://www.kaminomegumi.net/",
        )
        val enriched = crawler.parseDetailPage(
            church,
            """
            <header><h1>​めぐみルーテル福音キリスト教会</h1></header>
            <footer>
              <p>​めぐみルーテル福音キリスト教会 〒310-0905 茨城県水戸市石川1-4022-3 ℡029-251-5204 email: hagalecc@hotmail.com</p>
              <a href="mailto:hagalecc@hotmail.com">Email</a>
              <a href="https://www.youtube.com/channel/example">YouTube</a>
              <a href="https://www.facebook.com/megumilecc">Facebook</a>
            </footer>
            """.trimIndent(),
        )

        assertEquals("めぐみルーテル福音キリスト教会", enriched.name)
        assertEquals("〒310-0905 茨城県水戸市石川１−４０２２−３", enriched.address)
        assertEquals("茨城県", enriched.jurisdiction)
        assertEquals("029-251-5204", enriched.phone)
        assertEquals("hagalecc@hotmail.com", enriched.email)
        assertEquals(2, enriched.socialProfiles.size)
    }
}
