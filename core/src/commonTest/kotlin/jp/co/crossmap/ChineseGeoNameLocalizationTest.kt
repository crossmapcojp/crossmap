package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class ChineseGeoNameLocalizationTest {
    @Test
    fun storesBothChineseScriptsAndPreservesReviewedVariants() {
        val geoName = GeoName(
            code = "34100",
            name = "広島市",
            type = GeoNameType.MUNICIPALITY,
            prefectureCode = "34",
            center = GeoPoint(34.3853, 132.4553),
            coveringRadiusKm = 20.0,
            translations = mapOf("en" to "Hiroshima", "zh-TW" to "廣島市"),
        ).withChineseTranslations()

        assertEquals("广岛市", geoName.translations["zh-Hans"])
        assertEquals("廣島市", geoName.translations["zh-Hant"])
        assertEquals("Hiroshima", geoName.translations["en"])
        assertEquals(null, geoName.translations["zh-TW"])
    }
}
