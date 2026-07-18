package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JapaneseAddressNormalizerTest {
    private val geonames = listOf(
        geo("13", "東京都", GeoNameType.PREFECTURE, "13"),
        geo("131130", "渋谷区", GeoNameType.WARD, "13"),
        geo("132292", "西東京市", GeoNameType.MUNICIPALITY, "13"),
        geo("40", "福岡県", GeoNameType.PREFECTURE, "40"),
        geo("401307", "福岡市", GeoNameType.MUNICIPALITY, "40"),
        geo("401323", "博多区", GeoNameType.WARD, "40"),
        geo("22", "静岡県", GeoNameType.PREFECTURE, "22"),
        geo("223441", "小山町", GeoNameType.MUNICIPALITY, "22"),
        geo("26", "京都府", GeoNameType.PREFECTURE, "26"),
        geo("261009", "京都市", GeoNameType.MUNICIPALITY, "26"),
        geo("261041", "中京区", GeoNameType.WARD, "26"),
    )

    @Test
    fun decomposesTokyoWardLocalityAndNumber() {
        val result = JapaneseAddressNormalizer.normalize(
            "〒150-0035 東京都渋谷区鉢山町９−２",
            geonames,
        )

        assertEquals("150-0035", result.postalCode)
        assertEquals("東京都", result.prefecture)
        assertEquals("13", result.prefectureCode)
        assertEquals("渋谷区", result.municipality)
        assertEquals("131130", result.municipalityCode)
        assertNull(result.cityWard)
        assertEquals("鉢山町", result.locality)
        assertEquals("9-2", result.addressNumber)
        assertNull(result.building)
    }

    @Test
    fun decomposesDesignatedCityWard() {
        val result = JapaneseAddressNormalizer.normalize(
            "〒812-0041 福岡県福岡市博多区吉塚５丁目１７−４０",
            geonames,
        )

        assertEquals("福岡県", result.prefecture)
        assertEquals("福岡市", result.municipality)
        assertEquals("401307", result.municipalityCode)
        assertEquals("博多区", result.cityWard)
        assertEquals("401323", result.cityWardCode)
        assertEquals("吉塚", result.locality)
        assertEquals("5丁目17-40", result.addressNumber)
    }

    @Test
    fun decomposesCountyTownAndBuilding() {
        val county = JapaneseAddressNormalizer.normalize(
            "〒410-1305 静岡県駿東郡小山町湯船１０２−１",
            geonames,
        )
        val building = JapaneseAddressNormalizer.normalize(
            "〒202-0021 東京都西東京市東伏見２丁目４−７ 富士ビル",
            geonames,
        )

        assertEquals("駿東郡", county.county)
        assertEquals("小山町", county.municipality)
        assertEquals("湯船", county.locality)
        assertEquals("102-1", county.addressNumber)
        assertEquals("東伏見", building.locality)
        assertEquals("2丁目4-7", building.addressNumber)
        assertEquals("富士ビル", building.building)
    }

    @Test
    fun preservesKyotoStreetDirectionAsSeparateEntity() {
        val result = JapaneseAddressNormalizer.normalize(
            "〒604-8043 京都府京都市中京区寺町通錦小路下る東大文字町２９２",
            geonames,
        )

        assertEquals("京都府", result.prefecture)
        assertEquals("京都市", result.municipality)
        assertEquals("中京区", result.cityWard)
        assertEquals("寺町通錦小路下る", result.kyotoStreet)
        assertEquals("東大文字町", result.locality)
        assertEquals("292", result.addressNumber)
    }

    private fun geo(code: String, name: String, type: GeoNameType, prefectureCode: String) = GeoName(
        code = code,
        name = name,
        type = type,
        prefectureCode = prefectureCode,
        center = GeoPoint(0.0, 0.0),
        coveringRadiusKm = 15.0,
    )
}
