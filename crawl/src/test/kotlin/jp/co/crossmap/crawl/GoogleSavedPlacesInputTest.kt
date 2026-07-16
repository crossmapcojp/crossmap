package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleSavedPlacesInputTest {
    @Test
    fun explicitInputOverridesEveryConfiguredDefault() {
        val root = Files.createTempDirectory("crossmap-saved-input")
        try {
            val resolved = GoogleSavedPlacesInput.resolve(
                explicit = "explicit/saved",
                workingDirectory = root,
                systemProperty = "system/saved",
                environment = "environment/saved",
            )

            assertEquals(root.resolve("explicit/saved").toAbsolutePath(), resolved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun findsCrossmapPropertyFromNearestParentLocalProperties() {
        val root = Files.createTempDirectory("crossmap-saved-local-properties")
        try {
            val nested = Files.createDirectories(root.resolve("crawl/build"))
            Files.writeString(root.resolve("local.properties"), "crossmap.googleSavedPlaces=Takeout/saved\n")

            val resolved = GoogleSavedPlacesInput.resolve(
                explicit = null,
                workingDirectory = nested,
                systemProperty = null,
                environment = null,
            )

            assertEquals(root.resolve("Takeout/saved").toAbsolutePath().normalize(), resolved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenNoInputSourceIsConfigured() {
        val root = Files.createTempDirectory("crossmap-saved-input-missing")
        try {
            assertNull(
                GoogleSavedPlacesInput.resolve(
                    explicit = null,
                    workingDirectory = root,
                    systemProperty = null,
                    environment = null,
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
