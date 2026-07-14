package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicAndroidHostTest {

    @Test
    fun realChurchCoordinatesAreAvailableOnAndroid() {
        val point = GeoPoint(35.6601808, 139.743601)
        assertEquals(35.6601808, point.latitude)
    }
}
