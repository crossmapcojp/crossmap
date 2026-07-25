package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals

class CatholicChurchNameNormalizerTest {
    @Test
    fun removesJapanOrganizationQualifierFromEverySupportedTranslation() {
        val normalized = CatholicChurchNameNormalizer.normalize(
            ChurchRecord(
                id = "hagi",
                name = "萩カトリック教会",
                englishName = "Hagi Catholic Church in Japan",
                localizedNames = listOf(
                    LocalizedName("ja", "萩カトリック教会"),
                    LocalizedName("en", "Hagi Catholic Church in Japan"),
                    LocalizedName("ko", "하기 일본 가톨릭"),
                    LocalizedName("pt", "Hagi Católica no Japão"),
                    LocalizedName("id", "Hagi Katolik di Jepang"),
                ),
                address = "萩市",
                location = GeoPoint(0.0, 0.0),
                websiteUrl = "https://example.test",
            ),
        )

        assertEquals("Hagi Catholic Church", normalized.englishName)
        assertEquals(
            mapOf(
                "ja" to "萩カトリック教会",
                "en" to "Hagi Catholic Church",
                "ko" to "하기 가톨릭교회",
                "pt" to "Igreja Católica Hagi",
                "id" to "Gereja Katolik Hagi",
            ),
            normalized.localizedNames.associate { it.languageCode to it.name },
        )
    }

    @Test
    fun movesLegacyLeadingOrganizationNameBehindTheChurchLocation() {
        assertEquals(
            "Yamashina Catholic Church",
            CatholicChurchNameNormalizer.english("Catholic Church in Japan Yamashina Church"),
        )
    }
}
