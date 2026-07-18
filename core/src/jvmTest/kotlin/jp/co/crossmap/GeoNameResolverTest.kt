package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoNameResolverTest {
    @Test
    fun japanIsNeverUsedAsAGeoFilterInsideDenominationNames() {
        val japan = GeoName(
            code = "JP",
            name = "日本",
            aliases = listOf("日本国"),
            type = GeoNameType.PREFECTURE,
            prefectureCode = "JP",
            center = GeoPoint(36.0, 138.0),
            coveringRadiusKm = 1_500.0,
            translations = mapOf(
                "en" to "Japan",
                "ko" to "일본",
                "pt" to "Japão",
                "id" to "Jepang",
            ),
        )
        val resolver = GeoNameResolver(listOf(japan))

        listOf("日本基督教団", "日本バプテスト連盟", "日本").forEach { query ->
            val resolved = resolver.resolve(query)
            assertTrue(resolved.locations.isEmpty(), query)
            assertEquals(query, resolved.textQuery, query)
        }
        assertTrue(resolver.resolve("Japan Baptist Convention", language = "en").locations.isEmpty())
    }
}
