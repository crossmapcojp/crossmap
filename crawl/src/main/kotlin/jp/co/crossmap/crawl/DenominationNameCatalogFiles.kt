package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.Language
import kotlinx.serialization.json.Json

/** Loads the committed per-language denomination-name catalogs. */
object DenominationNameCatalogFiles {
    private val json = Json { ignoreUnknownKeys = true }

    fun path(resourcesRoot: Path, language: Language): Path =
        resourcesRoot.resolve("catalog/denomination-${language.code}-names.json")

    fun load(resourcesRoot: Path): Map<Language, Map<String, String>> =
        Language.entries.associateWith { language ->
            json.decodeFromString<Map<String, String>>(Files.readString(path(resourcesRoot, language)))
                .also { names ->
                    require(names.values.none(String::isBlank)) {
                        "Blank denomination name in ${path(resourcesRoot, language)}"
                    }
                }
        }
}
