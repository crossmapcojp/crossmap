package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class IECDenominationChurchListCrawlerTest {
    @Test
    fun parsesRegionalChurchCardsAndIndonesianMinisterRoles() {
        val churches = IECDenominationChurchListCrawler("https://www.giii-japan.org/gereja-wilayah").parse(
            """
            <div data-testid="richTextElement">
              <h6 class="font_6">GIII Tokyo</h6>
              <p class="font_8">Alamat Gereja: Gd. NACTVA Lt. 2 25-13 Ichibancho, Chiyoda City, Tokyo 102-0082
                Hamba Tuhan: Pdt. Henry Mimbar H. S., M.Th. Email: henry@giii-japan.org
                Ev. Johannes Sukardi, S.Th. Email: johannes.sukardi@giii-japan.org
                Website: http://tokyo.giii-japan.org/</p>
            </div>
            <div data-testid="richTextElement">
              <h6 class="font_6">POS PI Shonan</h6>
              <p class="font_8">Alamat Gereja: 3 Chome-5-58 Hamatake, Chigasaki, Kanagawa 253-0021
                Hamba Tuhan: Ev. Samuel Parimpasa, S.Th. Email: samuel.parimpasa@giii-japan.org</p>
            </div>
            <div data-testid="richTextElement"><h6 class="font_6">Gereja Wilayah</h6></div>
            """.trimIndent(),
        )

        assertEquals(listOf("GIII Tokyo", "POS PI Shonan"), churches.map { it.name })
        assertEquals("Gd. NACTVA Lt. 2 25-13 Ichibancho, Chiyoda City, Tokyo 102-0082", churches[0].address)
        assertEquals("東京都", churches[0].jurisdiction)
        assertEquals("http://tokyo.giii-japan.org/", churches[0].websiteUrl)
        assertEquals("henry@giii-japan.org", churches[0].email)
        assertEquals(listOf("Henry Mimbar H. S.", "Johannes Sukardi"), churches[0].ministers.map { it.name })
        assertEquals("神奈川県", churches[1].jurisdiction)
        assertEquals("Samuel Parimpasa", churches[1].ministers.single().name)
    }
}
