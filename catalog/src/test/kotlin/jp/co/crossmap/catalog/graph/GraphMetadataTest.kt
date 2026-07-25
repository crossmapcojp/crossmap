package jp.co.crossmap.catalog.graph

import jp.co.crossmap.GeoPoint
import jp.co.crossmap.catalog.Church
import jp.co.crossmap.catalog.ChurchId
import jp.co.crossmap.catalog.MultilingualText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraphMetadataTest {
    private val church = Church(
        id = ChurchId("google:1"),
        googlePlaceId = "1",
        names = MultilingualText(mapOf("ja" to "淀橋教会", "en" to "Yodobashi Church")),
        primaryName = "淀橋教会",
        englishName = "Yodobashi Church",
        titleLanguages = listOf("ja"),
        category = "教会",
        address = "東京都新宿区",
        location = GeoPoint(35.7, 139.7),
        email = null,
        updatedAt = "2026-07-25T00:00:00Z",
        denomination = null,
    )

    @Test
    fun mapsOnlyBoundedScalarPropertiesAndFlattensTranslations() {
        val properties = DefaultCrossmapGraphMapper().toNodeProperties(church, ChurchGraphMetadata)

        assertEquals("google:1", properties["id"])
        assertEquals("淀橋教会", properties["name_ja"])
        assertEquals("Yodobashi Church", properties["name_en"])
        assertEquals(listOf("ja"), properties["titleLanguages"])
    }

    @Test
    fun rejectsNestedObjects() {
        val metadata = object : NodeMetadata<Church> {
            override val type = Church::class
            override val label = "Church"
            override val idProperty = "id"
            override fun id(value: Church) = value.id.value
            override fun toProperties(value: Church) = mapOf("id" to value.id.value, "nested" to mapOf("bad" to true))
        }

        assertFailsWith<IllegalArgumentException> {
            DefaultCrossmapGraphMapper().toNodeProperties(church, metadata)
        }
    }

    @Test
    fun registryRejectsDuplicateMetadata() {
        assertFailsWith<IllegalArgumentException> {
            GraphMetadataRegistry(listOf(ChurchGraphMetadata, ChurchGraphMetadata))
        }
    }
}
