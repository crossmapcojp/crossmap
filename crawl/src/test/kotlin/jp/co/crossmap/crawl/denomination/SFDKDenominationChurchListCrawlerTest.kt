package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class SFDKDenominationChurchListCrawlerTest {
    private val crawler = SFDKDenominationChurchListCrawler("https://www.sfdk.org/")

    @Test
    fun parsesChurchDetailLinksAndExcludesHeadquartersAndOssuary() {
        val churches = crawler.parse(
            """
            <ul class="wp-block-page-list">
              <li class="wp-block-pages-list__item"><a class="wp-block-pages-list__item__link" href="/団体施設/honbu/">本部</a></li>
              <li class="wp-block-pages-list__item"><a class="wp-block-pages-list__item__link" href="/団体施設/本部納骨堂/">本部納骨堂</a></li>
              <li class="wp-block-pages-list__item"><a class="wp-block-pages-list__item__link" href="/北ブロック/kinomoto/">木之本キリスト教会</a></li>
            </ul>
            """.trimIndent(),
        )

        val church = churches.single()
        assertEquals("木之本キリスト教会", church.name)
        assertEquals("https://www.sfdk.org/北ブロック/kinomoto/", church.denominationChurchListDetailPage)
    }

    @Test
    fun parsesAddressContactWebsiteAndMultiplePastorsFromDetailPage() {
        val church = OfficialDenominationChurch(
            name = "木之本キリスト教会",
            denominationChurchListDetailPage = "https://www.sfdk.org/kinomoto/",
        )
        val parsed = crawler.parseDetailPage(
            church,
            """
            <main><h1>木之本キリスト教会</h1><p>
              〒529-0425 滋賀県長浜市木之本町木之本846<br>
              （電話）0749-82-3035<br>（ファックス）0749-82-3036<br>
              （牧師）北村修一、北村イレイン、カーラ・バロス<br>
              （ホームページ）http://www.ex.biwa.ne.jp/~shu-ela/
            </p></main>
            """.trimIndent(),
        )

        assertEquals("〒529-0425 滋賀県長浜市木之本町木之本８４６", parsed.address)
        assertEquals("滋賀県", parsed.jurisdiction)
        assertEquals("0749-82-3035", parsed.phone)
        assertEquals("0749-82-3036", parsed.fax)
        assertEquals("http://www.ex.biwa.ne.jp/~shu-ela/", parsed.websiteUrl)
        assertEquals(listOf("北村修一", "北村イレイン", "カーラ・バロス"), parsed.ministers.map { it.name })
    }

    @Test
    fun treatsPastoralStaffAsMinistersAndContactAsPhone() {
        val parsed = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "めぐみキリスト教会",
                denominationChurchListDetailPage = "https://www.sfdk.org/北ブロック/megumi/",
            ),
            """
            <main><p>〒522-0086 滋賀県彦根市後三条町602番地
              （連絡先）0749-26-1839 （牧会スタッフ）田中隆裕、田中玲子
              https://www.sfdk.org/megumi/ 集いのご案内
            </p></main>
            """.trimIndent(),
        )

        assertEquals("0749-26-1839", parsed.phone)
        assertEquals(listOf("田中隆裕", "田中玲子"), parsed.ministers.map { it.name })
        assertEquals("https://www.sfdk.org/megumi/", parsed.websiteUrl)
    }
}
