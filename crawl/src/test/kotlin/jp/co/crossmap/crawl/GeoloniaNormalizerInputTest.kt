package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoloniaNormalizerInputTest {
    @Test
    fun resolvesMachineSpecificCheckoutFromNearestLocalProperties() {
        val root = Files.createTempDirectory("crossmap-geolonia-property")
        try {
            Files.writeString(
                root.resolve("local.properties"),
                "${GeoloniaNormalizerInput.PROPERTY}=../normalize-japanese-addresses\n",
            )

            assertEquals(
                root.parent.resolve("normalize-japanese-addresses").normalize().toAbsolutePath(),
                GeoloniaNormalizerInput.resolve(
                    explicit = null,
                    workingDirectory = root.resolve("crawl"),
                    systemProperty = null,
                    environment = null,
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
