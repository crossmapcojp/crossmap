package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JapanFreeEvangelicalChurchDenominationChurchListCrawlerTest {
    private val crawler = JapanFreeEvangelicalChurchDenominationChurchListCrawler("https://njfk-jp.com/")

    @Test
    fun parsesChurchHeadingsFromTheOfficialPage() {
        val churches = crawler.parse(
            """
            <h4 class="wp-block-heading has-text-align-center mb-0 is-style-vk-heading-plain" id="-dac9edf6">札幌福音教会</h4>
            <p>〒063-0054 北海道札幌市西区北１５条西３丁目１５－６</p>
            <p>℡ 011-756-1111</p>
            <h4 class="wp-block-heading has-text-align-center mb-0 is-style-vk-heading-plain" id="-55ad8fff">清田キリスト教会</h4>
            <p>〒004-0051 北海道札幌市清田区平岡１条５丁目１－１</p>
            <p>℡ 011-889-2222</p>
            """.trimIndent(),
        )

        assertEquals(listOf("札幌福音教会", "清田キリスト教会"), churches.map { it.name })
        assertEquals("北海道", churches[0].jurisdiction)
        assertEquals("011-756-1111", churches[0].phone)
    }
}