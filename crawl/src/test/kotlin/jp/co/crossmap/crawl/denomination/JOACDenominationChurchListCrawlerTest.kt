package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JOACDenominationChurchListCrawlerTest {
    @Test
    fun parsesTheCurrentOfficialChurchLinks() {
        val churches = JOACDenominationChurchListCrawler(
            "https://olivetassembly.or.jp/our-regions.html",
        ).parse(
            """
            <nav><a href="/about-us/">当教会について</a></nav>
            <ul class="church-links">
              <li>東北 <a href="https://sd-ainohikarichurch.org/">仙台山城長老教会</a></li>
              <li>東京 <a href="https://tokyosophia.org/">東京ソフィア長老教会</a></li>
              <li>九州 <a href="https://www.fukuokaagape.org/">福岡アガペ長老教会</a></li>
            </ul>
            """.trimIndent(),
        )

        assertEquals(listOf("仙台山城長老教会", "東京ソフィア長老教会", "福岡アガペ長老教会"), churches.map { it.name })
        assertEquals(listOf("宮城県", "東京都", "福岡県"), churches.map { it.jurisdiction })
        assertEquals("https://tokyosophia.org/", churches[1].websiteUrl)
    }

    @Test
    fun parsesOnlyNamedChurchesFromRegionalPresbyteryLists() {
        val churches = JOACDenominationChurchListCrawler(
            "https://olivetassembly.or.jp/our-regions.html",
        ).parse(
            """
            <h3>東北中会</h3>
            <ul>
              <li>青森県</li>
              <li>宮城県（<a href="https://sendai.example/">仙台山城長老教会</a>）</li>
              <li>福島県</li>
            </ul>
            <h3>東京中会</h3>
            <ul><li>東京都（<a href="https://tokyo.example/">東京ソフィア長老教会</a>）</li></ul>
            <h3>中部中会</h3>
            <ul>
              <li>愛知県（愛知長老教会）</li>
              <li>静岡県(小山あいのひかり長老教会)</li>
              <li>山梨県</li>
            </ul>
            """.trimIndent(),
        )

        assertEquals(
            listOf("仙台山城長老教会", "東京ソフィア長老教会", "愛知長老教会", "小山あいのひかり長老教会"),
            churches.map { it.name },
        )
        assertEquals(listOf("宮城県", "東京都", "愛知県", "静岡県"), churches.map { it.jurisdiction })
        assertEquals("https://sendai.example/", churches[0].websiteUrl)
        assertEquals("", churches[2].websiteUrl)
    }
}
