package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JVCFDenominationChurchListCrawlerTest {
    private val crawler = JVCFDenominationChurchListCrawler(
        "https://worship.vcfkani.org/introduction/",
    )

    @Test
    fun parsesAllSixMembersFromTheOfficialAffiliationStatement() {
        val churches = crawler.parse(
            """
            <p>現在では、岐阜県の可児福音教会とその枝教会である扶桑ゴスペルセンター、
              多治見ビンヤード、埼玉県のVCF所沢、栃木県のVCF矢板および横浜で開拓している
              パールヴィンヤードがこの集りに参加しています。</p>
            <a href="https://www.fvc-kani.jp/">可児福音教会</a>
            """.trimIndent(),
        )

        assertEquals(
            listOf("可児福音教会", "扶桑ゴスペルセンター", "多治見ビンヤード", "VCF所沢", "VCF矢板", "パールヴィンヤード"),
            churches.map { it.name },
        )
        assertEquals("https://www.fvc-kani.jp/", churches[0].denominationChurchListDetailPage)
        assertEquals("", churches[3].denominationChurchListDetailPage)
    }

    @Test
    fun enrichesTheThreeChurchesPublishedByKani() {
        val html = """
            <body>
              可児福音教会岐阜県可児市今渡1732-1 Tel:0574-62-6272 主任牧師 細江誠貢
              Family Church 扶桑ゴスペルチャーチ 愛知県丹羽郡扶桑町大字斎藤字北屋敷143
              TEL 058-92-9189 牧師 沢田満
              多治見ビンヤードチャーチ 岐阜県多治見市池田町1-17 TEL 0572-26-9898 牧師 川原聡
              可児福音教会 〒509-0207 岐阜県可児市今渡1732-1 TEL 0574-62-6272 FAX 0574-63-6304
            </body>
        """.trimIndent()
        val source = "https://www.fvc-kani.jp/"
        val kani = crawler.parseDetailPage(
            OfficialDenominationChurch(name = "可児福音教会", denominationChurchListDetailPage = source),
            html,
        )
        val fuso = crawler.parseDetailPage(
            OfficialDenominationChurch(name = "扶桑ゴスペルセンター", denominationChurchListDetailPage = source),
            html,
        )
        val tajimi = crawler.parseDetailPage(
            OfficialDenominationChurch(name = "多治見ビンヤード", denominationChurchListDetailPage = source),
            html,
        )

        assertEquals("〒509-0207 岐阜県可児市今渡１７３２−１", kani.address)
        assertEquals("0574-62-6272", kani.phone)
        assertEquals("0574-63-6304", kani.fax)
        assertEquals(listOf("細江誠貢"), kani.ministers.map { it.name })
        assertEquals("扶桑ゴスペルチャーチ", fuso.name)
        assertEquals("愛知県丹羽郡扶桑町大字斎藤字北屋敷１４３", fuso.address)
        assertEquals(listOf("沢田満"), fuso.ministers.map { it.name })
        assertEquals("多治見ビンヤードチャーチ", tajimi.name)
        assertEquals("0572-26-9898", tajimi.phone)
    }
}
