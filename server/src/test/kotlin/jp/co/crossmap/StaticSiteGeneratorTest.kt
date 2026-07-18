package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class StaticSiteGeneratorTest {
    @Test
    fun apostropheIsDeletedInsteadOfBecomingUrlSeparator() {
        assertEquals("lords-church", StaticSiteGenerator().pageSlug(null, "Lord's Church"))
    }

    @Test
    fun realNskkSaintLuciaNameUsesAuthoritativeUrlSpelling() {
        assertEquals("nskk-st-lucia-church", StaticSiteGenerator().pageSlug("NSKK", "St. Lucia Church"))
    }

    @Test
    fun denominationAlreadyPresentInEnglishChurchNameIsNotDuplicatedInUrl() {
        assertEquals("jelc-glory-church", StaticSiteGenerator().pageSlug("jelc", "JELC Glory Church"))
    }

    @Test
    fun independentChristianAssemblyKeepsAssemblyAndOmitsDenomination() {
        assertEquals("kyodo-christian-assembly", StaticSiteGenerator().pageSlug(null, "Kyodo Christian Assembly"))
    }
}
