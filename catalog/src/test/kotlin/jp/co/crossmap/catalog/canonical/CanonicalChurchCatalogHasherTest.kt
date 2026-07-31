package jp.co.crossmap.catalog.canonical

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalChurchCatalogHasherTest {
    @Test
    fun ignoresIncidentalEntityAndLocalizedNameOrdering() {
        val first = church("b", listOf(LocalizedName("en", "Bee"), LocalizedName("ja", "ビー")))
        val second = church("a", listOf(LocalizedName("ja", "エー"), LocalizedName("en", "A")))

        val left = CanonicalChurchCatalogHasher.contentHash(listOf(first, second))
        val right = CanonicalChurchCatalogHasher.contentHash(
            listOf(second.copy(localizedNames = second.localizedNames.reversed()), first.copy(localizedNames = first.localizedNames.reversed())),
        )

        assertEquals(left, right)
        assertNotEquals(left, CanonicalChurchCatalogHasher.contentHash(listOf(first.copy(address = "changed"), second)))
    }

    private fun church(id: String, names: List<LocalizedName>) = ChurchRecord(
        id = id,
        name = names.first().name,
        englishName = names.firstOrNull { it.languageCode == "en" }?.name.orEmpty(),
        localizedNames = names,
        address = "Tokyo",
        location = GeoPoint(35.0, 139.0),
        websiteUrl = "",
    )
}
