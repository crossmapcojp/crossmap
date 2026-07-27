package jp.co.crossmap.catalog

import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledContentType
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SermonMetadata
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlin.test.Test
import kotlin.test.assertNotNull

class CoreTypeCompilationGuardTest {

    @Test
    fun allCoreTypesReferencedByCatalogModuleArePresent() {
        val types: List<Pair<String, Any?>> = listOf(
            "ChurchRecord" to ChurchRecord::class,
            "ChurchMinister" to ChurchMinister::class,
            "CrawledContentType" to CrawledContentType::class,
            "CrawledPage" to CrawledPage::class,
            "FieldDetermination" to FieldDetermination::class,
            "GeoPoint" to GeoPoint::class,
            "LocalizedName" to LocalizedName::class,
            "SermonMetadata" to SermonMetadata::class,
            "SocialPlatform" to SocialPlatform::class,
            "SocialProfile" to SocialProfile::class,
        )
        for ((name, ref) in types) {
            assertNotNull(ref, "$name from core should be resolvable")
        }
    }
}
