package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class HPBCDenominationChurchListCrawlerTest {
    private val crawler = HPBCDenominationChurchListCrawler("https://www.hpbaptist.net/location/asia/")

    @Test
    fun parsesJapanChurchLinksAndExcludesVerifiedNonJapanEntries() {
        val churches = crawler.parse(
            """
            <article class="church location-asia entry">
              <h2 class="entry-title"><a href="/church/himeji-baptist-church/">Himeji Baptist Church</a></h2>
            </article>
            <article class="church location-asia entry">
              <h2 class="entry-title"><a href="/church/international-baptist-church-of-manila/">International Baptist Church of Manila</a></h2>
            </article>
            <article class="church location-asia entry">
              <h2 class="entry-title"><a href="/church/songtan-central-baptist-church/">Songtan Central Baptist Church</a></h2>
            </article>
            """.trimIndent(),
        )

        val church = churches.single()
        assertEquals("Himeji Baptist Church", church.name)
        assertEquals("Himeji Baptist Church", church.localizedNames.single().name)
        assertEquals(
            "https://www.hpbaptist.net/church/himeji-baptist-church/",
            church.denominationChurchListDetailPage,
        )
    }

    @Test
    fun enrichesAChurchFromItsOfficialDetailPage() {
        val church = OfficialDenominationChurch(
            name = "Tokyo Baptist Church",
            denominationChurchListDetailPage = "https://www.hpbaptist.net/church/tokyo-baptist-church/",
        )

        val enriched = crawler.parseDetailPage(
            church,
            """
            <main class="content">
              <p>Church Info</p>
              <p>Pastor: Takeshi Yozawa</p>
              <p>Pastors Spouse: Miki</p>
              <p>Phone Numbers: Church: 03-3461-8425</p>
              <p>Church Address Address: 9-2 Hachiyama-cho, Shibuya-Ku Tokyo Japan 1500035</p>
              <p>Mailing: Same as above.</p>
            </main>
            """.trimIndent(),
        )

        assertEquals("9-2 Hachiyama-cho, Shibuya-Ku Tokyo Japan 1500035", enriched.address)
        assertEquals("03-3461-8425", enriched.phone)
        assertEquals("Takeshi Yozawa", enriched.ministers.single().name)
    }
}
