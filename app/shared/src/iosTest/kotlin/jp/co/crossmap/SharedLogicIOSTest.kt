package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicIOSTest {

    @Test
    fun realChurchCoordinatesAreAvailableOnIos() {
        val point = GeoPoint(34.6619806, 133.9231824)
        assertEquals(133.9231824, point.longitude)
    }
}
