package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Resolves the personal Takeout directory consistently for direct CLI and Gradle entry points. */
internal object GoogleSavedPlacesInput {
    const val PROPERTY = "crossmap.googleSavedPlaces"
    const val ENVIRONMENT = "CROSSMAP_GOOGLE_SAVED_PLACES"

    fun resolve(
        explicit: String?,
        workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        systemProperty: String? = System.getProperty(PROPERTY),
        environment: String? = System.getenv(ENVIRONMENT),
    ): Path? {
        val configured = sequenceOf(
            explicit,
            systemProperty,
            environment,
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
        if (configured != null) return workingDirectory.resolve(configured).normalize().toAbsolutePath()
        return localProperty(workingDirectory)
    }

    private fun localProperty(workingDirectory: Path): Path? {
        val file = generateSequence(workingDirectory.toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve("local.properties") }
            .firstOrNull(Files::isRegularFile)
            ?: return null
        val configured = Properties().apply {
            Files.newInputStream(file).use(::load)
        }.getProperty(PROPERTY)?.trim()?.takeIf(String::isNotBlank) ?: return null
        return file.parent.resolve(configured).normalize().toAbsolutePath()
    }
}
