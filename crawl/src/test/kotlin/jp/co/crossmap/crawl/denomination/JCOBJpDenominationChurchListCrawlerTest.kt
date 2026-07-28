package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JCOBJpDenominationChurchListCrawlerTest {
    @Test
    fun parsesJapaneseChurchRowsAndTheirOfficialContactFields() {
        val churches = JCOBJpDenominationChurchListCrawler("https://jcobjapan.com/churches.html").parse(
            """
            <div id="desktop-directory">
              <p>CHURCHES | <a href="https://www.google.com/maps/search/jcob/@36,137,8z">Map</a></p>
              <p>TOKYO-SAITAMA-CHIBA</p>
              <p><span>AGEO</span> | <a href="https://www.google.com/maps/place/JCOB+Ageo/">Ageo Bunka Center, 750 Futatsumiya, Ageo-shi, Saitama-ken 362-0017</a> | Sundays: 1:00PM–4:30PM | Ptr. Genesis Fajardo: 090 5542 9940</p>
              <p>KAWAGOE | <a href="https://www.google.com/maps/place/JCOB+Kawagoe/">143-5 Sunashinden, Kawagoe-shi, Saitama-ken 350-1133</a> | Phone: 049 (123) 4567 | Ptr. Herbert Rodriguez: 090 9674 0768 | Website: <a href="https://www.jcobkawagoe.com/">jcobkawagoe.com</a></p>
            </div>
            <div id="mobile-directory">
              <p>AGEO | <a href="https://www.google.com/maps/place/JCOB+Ageo/">Ageo Bunka Center, 750 Futatsumiya, Ageo-shi, Saitama-ken 362-0017</a> | Ptr. Genesis Fajardo: 090 5542 9940</p>
            </div>
            """.trimIndent(),
        )

        assertEquals(listOf("JCOB Ageo", "JCOB Kawagoe"), churches.map { it.name })
        assertEquals("埼玉県", churches.first().jurisdiction)
        assertEquals("Genesis Fajardo", churches.first().ministers.single().name)
        assertEquals("牧師", churches.first().ministers.single().roleName)
        assertEquals("049-123-4567", churches.last().phone)
        assertEquals("https://www.jcobkawagoe.com/", churches.last().websiteUrl)
    }
}
