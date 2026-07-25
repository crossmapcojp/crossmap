package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureDenominationChurchListCrawlerTest {
    @Test
    fun jmaParsesRfc4180RowsAndAddsReviewedKoreanMinisterNames() {
        val csv = """
            教会名,郵便番号,住所,担任教師
            秋田希望キリスト教会,010-0041,秋田県秋田市広面堰塚32-5,申吹錫
            "証し,希望教会",123-4567,東京都足立区一丁目2-3,松原信幸
        """.trimIndent()

        val churches = JMADenominationChurchListCrawler().parse(csv)

        assertEquals(listOf("秋田希望キリスト教会", "証し,希望教会"), churches.map { it.name })
        assertEquals("〒010-0041 秋田県秋田市広面堰塚３２−５", churches.first().address)
        assertEquals("秋田県", churches.first().jurisdiction)
        assertEquals("신취석", churches.first().ministers.single().localizedNames.single().name)
        assertTrue(churches.last().ministers.single().localizedNames.isEmpty())
    }

    @Test
    fun whcjTracksDistrictsRestoresArchivedUrlsAndStopsBeforeOverseasSections() {
        val html = """
            <table>
              <tr><td><strong>北海道教区</strong></td></tr>
              <tr><td><a href="https://web.archive.org/web/20240725121250/http://atubetuch.example/">厚別キリスト教会</a>
                <a href="https://web.archive.org/web/20240725121250/https://www.facebook.com/atubetu/">フェイス・ブック</a></td></tr>
              <tr><td>浜松ウェスレアン教会</td></tr>
              <tr><td><strong>海外宣教</strong></td></tr>
              <tr><td><a href="https://seoul.example/">ソウル日本人教会</a></td></tr>
            </table>
        """.trimIndent()

        val churches = WHCJDenominationChurchListCrawler().parse(html)

        assertEquals(listOf("厚別キリスト教会", "浜松ウェスレアン教会"), churches.map { it.name })
        assertEquals("北海道教区", churches.first().jurisdiction)
        assertEquals("http://atubetuch.example/", churches.first().websiteUrl)
        assertEquals("https://facebook.com/atubetu", churches.first().socialProfiles.single().url)
    }

    @Test
    fun runnerLoadsCommittedResourceWithoutCallingHttpLoader() {
        val root = createTempDirectory("crossmap-fixture-crawler")
        val resources = root.resolve("resources")
        Files.createDirectories(resources.resolve("crawl"))
        Files.writeString(
            resources.resolve("crawl/jma-churches.csv"),
            "教会名,郵便番号,住所,担任教師\n秋田希望キリスト教会,010-0041,秋田県秋田市広面堰塚32-5,申吹錫\n",
        )
        val runner = DenominationChurchListCrawlerRunner(
            pageLoader = DenominationChurchPageLoader { _, _ -> error("HTTP loader must not be used for resource fixtures") },
        )

        val result = runner.crawl(JMADenominationChurchListCrawler(), resources, root.resolve("cache"), forceRefresh = true)

        assertEquals(1, result.list.churches.size)
        assertEquals(1, result.pageCount)
        assertTrue(result.cacheHit)
    }
}
