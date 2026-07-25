package jp.co.crossmap.catalog

import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogDomainTest {
    @Test
    fun multilingualTextNormalizesRegionalLanguageCodes() {
        val text = MultilingualText.from(
            listOf(
                LocalizedName("ja-JP", "淀橋教会"),
                LocalizedName("en", "Yodobashi Church"),
            )
        )

        assertEquals("淀橋教会", text["ja"])
        assertEquals("Yodobashi Church", text["en-US"])
    }

    @Test
    fun stableIdsAndPaginationRejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { ChurchId(" ") }
        assertFailsWith<IllegalArgumentException> { PageRequest(limit = 101) }
    }
}
