package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DenominationNamesTest {
    @Test
    fun internalClassificationIdsAreNeverDisplayableNames() {
        assertFalse("NOT_DETERMINED".isDisplayableDenominationId())
        assertFalse("INDEPENDENT_CHURCH".isDisplayableDenominationId())
        assertFalse(null.isDisplayableDenominationId())
        assertTrue("JELC".isDisplayableDenominationId())
        assertEquals(
            "Machida Baptist Church",
            "INDEPENDENT_CHURCH Machida Baptist Church".withoutInternalDenominationMarkers(),
        )
    }
}
