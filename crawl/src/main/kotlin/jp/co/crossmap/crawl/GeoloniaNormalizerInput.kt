package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Resolves the machine-local Geolonia checkout without committing developer-specific paths. */
internal object GeoloniaNormalizerInput {
    const val PROPERTY = "crossmap.geoloniaNormalizerDir"
    const val ENVIRONMENT = "CROSSMAP_GEOLONIA_NORMALIZER_DIR"

    fun resolve(
        explicit: String?,
        workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        systemProperty: String? = System.getProperty(PROPERTY),
        environment: String? = System.getenv(ENVIRONMENT),
    ): Path? {
        sequenceOf(explicit, systemProperty, environment)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.let { return workingDirectory.resolve(it).normalize().toAbsolutePath() }
        val properties = generateSequence(workingDirectory) { it.parent }
            .map { it.resolve("local.properties") }
            .firstOrNull(Files::isRegularFile)
            ?: return null
        val configured = Properties().apply { Files.newInputStream(properties).use(::load) }
            .getProperty(PROPERTY)?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        return properties.parent.resolve(configured).normalize().toAbsolutePath()
    }
}
