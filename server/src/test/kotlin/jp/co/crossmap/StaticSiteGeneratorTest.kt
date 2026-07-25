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
    fun catholicChurchOmitsObviousDenominationPrefix() {
        assertEquals(
            "taman-ra-catholic-church",
            StaticSiteGenerator().pageSlug("Catholic Church in Japan", "Catholic Taman-ra Church"),
        )
    }

    @Test
    fun legacyCatholicEnglishNameAlsoDropsJapanPrefix() {
        assertEquals(
            "yamashina-catholic-church",
            StaticSiteGenerator().pageSlug("Catholic Church in Japan", "Catholic Church in Japan Yamashina Church"),
        )
    }

    @Test
    fun independentChristianAssemblyKeepsAssemblyAndOmitsDenomination() {
        assertEquals("kyodo-christian-assembly", StaticSiteGenerator().pageSlug(null, "Kyodo Christian Assembly"))
    }
}
