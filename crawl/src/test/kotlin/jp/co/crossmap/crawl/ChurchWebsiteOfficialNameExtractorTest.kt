package jp.co.crossmap.crawl

import jp.co.crossmap.LocalizedNameSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ChurchWebsiteOfficialNameExtractorTest {
    @Test
    fun extractsTmcOfficialNamesFromLocalizedPageHeadings() {
        val fixtures = listOf(
            Triple("https://www.tokyochurch.org/", "Tokyo Multicultural Church", "en"),
            Triple("https://www.tokyochurch.org/j/", "東京マルチカルチャル教会", "ja"),
            Triple("https://www.tokyochurch.org/c/", "東京多元文化基督教會", "zh-Hans"),
        )

        fixtures.forEach { (url, heading, language) ->
            val result = ChurchWebsiteOfficialNameExtractor.extract(
                url,
                "<html lang='en'><head><meta property='og:site_name' content='TMC'></head><body><main><h1>$heading</h1></main></body></html>",
            )
            assertEquals(language, result?.pageLanguageCode)
            assertEquals(if (language == "en") "$heading (TMC)" else heading, result?.localizedName?.name)
            assertEquals(LocalizedNameSource.OFFICIAL, result?.localizedName?.metadata?.source)
        }
    }
}
