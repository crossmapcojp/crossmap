package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.ChurchWebsitePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ExcludedChurchListingDomainsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun canonicalCatalogContainsNoExcludedPublicOrCrawledUrls() {
        val resources = resourcesRoot()
        val policy = ChurchWebsitePolicy(
            ChurchWebsitePolicy.parse(
                Files.readString(resources.resolve("catalog/excludedChurchListingDomains.txt")),
            ),
        )
        val churches = json.decodeFromString<List<ChurchRecord>>(
            Files.readString(resources.resolve("catalog/churches.json")),
        )

        churches.forEach { church ->
            assertFalse(policy.isExcluded(church.websiteUrl), "${church.name}: ${church.websiteUrl}")
            assertTrue(
                church.pages.none { policy.isExcluded(it.url) },
                "${church.name} retains an excluded crawled page",
            )
            if (church.googleCid != null) {
                assertTrue(church.websiteUrl.isNotBlank(), "${church.name} must have a church or Google Maps URL")
            }
        }
    }

    private fun resourcesRoot(): Path = sequenceOf(Path.of("resources"), Path.of("../resources"))
        .map { it.toAbsolutePath().normalize() }
        .first { Files.exists(it.resolve("catalog/churches.json")) }
}
