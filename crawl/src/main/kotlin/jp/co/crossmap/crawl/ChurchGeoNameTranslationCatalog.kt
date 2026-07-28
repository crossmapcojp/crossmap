package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.LocalizedName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChurchGeoNameTranslation(
    val ja: String,
    val translations: Map<String, String> = emptyMap(),
    val sources: List<String> = emptyList(),
)

@Serializable
data class ChurchGeoNameUsage(
    val churchId: String,
    val googlePlaceTitle: String = "",
    val title: List<String> = emptyList(),
    val address: List<String> = emptyList(),
)

data class ChurchGeoNameTranslationReport(
    val churchGeoNames: Int,
    val titleGeoNames: Int,
    val addressGeoNames: Int,
    val translatedCounts: Map<String, Int>,
    val missingCounts: Map<String, Int>,
    val titleMissingCounts: Map<String, Int> = emptyMap(),
    val addressMissingCounts: Map<String, Int> = emptyMap(),
    val geonamesBeforeCleanup: Int = churchGeoNames,
    val reviewedChurchNamesRemoved: Int = 0,
    val katakanaOnlyNamesRemoved: Int = 0,
    val addressBlocksRemoved: Int = 0,
)

/**
 * Builds the reviewed geoname translation boundary used by multilingual church-name composition.
 * Google Maps title decomposition is the source of truth for which Japanese geonames are needed.
 */
class ChurchGeoNameTranslationCatalog(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
    private val cleaner: JapaneseGeoNameCleaner = JapaneseGeoNameCleaner(),
) {
    fun build(
        candidatesFile: Path,
        resourcesRoot: Path,
        officialTranslations: Map<String, Map<String, String>> = emptyMap(),
    ): ChurchGeoNameTranslationReport {
        val candidates = json.decodeFromString<List<GooglePlaceChurchCandidate>>(Files.readString(candidatesFile))
        val geonamesDirectory = resourcesRoot.resolve("geonames")
        Files.createDirectories(geonamesDirectory)

        val programmatic = linkedMapOf<String, MutableMap<String, String>>()
        val sources = linkedMapOf<String, MutableSet<String>>()
        val usages = mutableListOf<ChurchGeoNameUsage>()
        val rawNames = linkedSetOf<String>()
        val cleanedOfficialTranslations = officialTranslations.filterKeys(cleaner::isUsable)
        val addressMatcher = LongestNameMatcher(cleanedOfficialTranslations.keys)
        val rawAddressMatcher = LongestNameMatcher(officialTranslations.keys)
        candidates.sortedBy { it.id }.forEach { candidate ->
            val rawTitleGeoNameComponents = candidate.nameComponents
                .filter { it.role == MultilingualNameComponentRole.GEONAME }
            rawNames += rawTitleGeoNameComponents.map { it.translations["ja"] ?: it.source }
            rawNames += rawAddressMatcher.detect(candidate.address)
            val titleGeoNameComponents = rawTitleGeoNameComponents.filter { component ->
                cleaner.isUsable(component.translations["ja"] ?: component.source)
            }
            val titleGeoNames = titleGeoNameComponents
                .map { it.translations["ja"] ?: it.source }
                .distinct()
            titleGeoNames.forEach { source -> sources.getOrPut(source) { linkedSetOf() }.add("TITLE") }
            titleGeoNameComponents.forEach { component ->
                val japanese = component.translations["ja"] ?: component.source
                val translations = programmatic.getOrPut(japanese) { linkedMapOf() }
                component.translations.toSortedMap().forEach { (language, translation) ->
                    val normalizedLanguage = language.normalizedLanguage()
                    if (normalizedLanguage != "ja") {
                        translation.takeIf(String::isNotBlank)?.let {
                            translations.putIfAbsent(normalizedLanguage, it)
                        }
                    }
                }
            }
            val addressGeoNames = addressMatcher.detect(candidate.address)
            addressGeoNames.forEach { japanese ->
                programmatic.getOrPut(japanese) { linkedMapOf() }
                sources.getOrPut(japanese) { linkedSetOf() }.add("ADDRESS")
            }
            usages += ChurchGeoNameUsage(
                churchId = candidate.id,
                googlePlaceTitle = candidate.name,
                title = titleGeoNames.sorted(),
                address = addressGeoNames.sorted(),
            )
        }
        programmatic.forEach { (japanese, translations) ->
            cleanedOfficialTranslations[japanese].orEmpty().forEach { (language, translation) ->
                if (language.normalizedLanguage() in TARGET_LANGUAGES && translation.isNotBlank()) {
                    translations[language.normalizedLanguage()] = translation
                }
            }
        }

        val reviewed = TARGET_LANGUAGES.associateWith { language ->
            mergeReviewFiles(
                legacyReviewFile(geonamesDirectory, language),
                reviewFile(geonamesDirectory, language, GeoNameUsageKind.TITLE),
                reviewFile(geonamesDirectory, language, GeoNameUsageKind.ADDRESS),
            )
        }
        val entries = programmatic.keys.sorted().map { japanese ->
            val translations = buildMap {
                putAll(programmatic.getValue(japanese))
                TARGET_LANGUAGES.forEach { language ->
                    val reviewedTranslation = reviewed.getValue(language)[japanese]?.takeIf(String::isNotBlank)
                    if (language == "ko") {
                        // A Korean value already present here came from GeoNames/JMA and is retained.
                        // Only their missing entries use deterministic Japanese-pronunciation
                        // transliteration from romaji; reviewed Hanja-style guesses are not reused.
                        val wasMissingFromOfficialSources = japanese in reviewed.getValue("ko")
                        if (get("ko") == null || wasMissingFromOfficialSources) {
                            val transliterated = romajiToHangul(get("en").orEmpty())
                            if (transliterated != null) {
                                put("ko", transliterated)
                            } else if (reviewedTranslation != null) {
                                put("ko", reviewedTranslation)
                            }
                        }
                    } else {
                        reviewedTranslation?.let { put(language, it) }
                    }
                }
            }.toSortedMap()
            ChurchGeoNameTranslation(japanese, translations, sources[japanese].orEmpty().sorted())
        }
        atomicWrite(
            geonamesDirectory.resolve("church-ja-all.json"),
            json.encodeToString(entries),
        )
        atomicWrite(
            geonamesDirectory.resolve("church-usage.json"),
            json.encodeToString(usages),
        )

        TARGET_LANGUAGES.forEach { language ->
            GeoNameUsageKind.entries.forEach { kind ->
                val rows = entries.asSequence()
                    .filter { entry -> kind.includes(entry.sources) }
                    .filter { language !in programmatic.getValue(it.ja) }
                    .map { entry ->
                        entry.ja to if (language == "ko") {
                            entry.translations[language].orEmpty()
                        } else {
                            reviewed.getValue(language)[entry.ja].orEmpty()
                        }
                    }
                    .toList()
                atomicWrite(reviewFile(geonamesDirectory, language, kind), rows.toReviewCsv())
            }
            Files.deleteIfExists(legacyReviewFile(geonamesDirectory, language))
        }

        val cleanup = cleaner.report(rawNames)
        return ChurchGeoNameTranslationReport(
            churchGeoNames = entries.size,
            titleGeoNames = sources.count { "TITLE" in it.value },
            addressGeoNames = sources.count { "ADDRESS" in it.value },
            translatedCounts = TARGET_LANGUAGES.associateWith { language ->
                entries.count { it.translations[language].isNullOrBlank().not() }
            },
            missingCounts = TARGET_LANGUAGES.associateWith { language ->
                entries.count { it.translations[language].isNullOrBlank() }
            },
            titleMissingCounts = TARGET_LANGUAGES.associateWith { language ->
                entries.count { entry ->
                    GeoNameUsageKind.TITLE.includes(entry.sources) && entry.translations[language].isNullOrBlank()
                }
            },
            addressMissingCounts = TARGET_LANGUAGES.associateWith { language ->
                entries.count { entry ->
                    GeoNameUsageKind.ADDRESS.includes(entry.sources) && entry.translations[language].isNullOrBlank()
                }
            },
            geonamesBeforeCleanup = cleanup.inputNames,
            reviewedChurchNamesRemoved = cleanup.reviewedChurchNamesRemoved,
            katakanaOnlyNamesRemoved = cleanup.katakanaOnlyNamesRemoved,
            addressBlocksRemoved = cleanup.addressBlocksRemoved,
        )
    }

    private class LongestNameMatcher(candidates: Set<String>) {
        private class Node {
            val children = linkedMapOf<Char, Node>()
            var value: String? = null
        }

        private val root = Node()

        init {
            candidates.filter { it.length >= 2 }.forEach { candidate ->
                var node = root
                candidate.forEach { character -> node = node.children.getOrPut(character, ::Node) }
                node.value = candidate
            }
        }

        fun detect(text: String): List<String> {
            val found = linkedSetOf<String>()
            var start = 0
            while (start < text.length) {
                var node = root
                var cursor = start
                var longest: String? = null
                var longestEnd = start
                while (cursor < text.length) {
                    node = node.children[text[cursor]] ?: break
                    cursor++
                    node.value?.let { longest = it; longestEnd = cursor }
                }
                if (longest != null) {
                    found += longest
                    start = longestEnd
                } else {
                    start++
                }
            }
            return found.toList()
        }
    }

    private fun readReviewFile(path: Path): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        return buildMap {
            Files.readAllLines(path).forEachIndexed { index, raw ->
                val line = raw.removePrefix("\uFEFF").trim()
                if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
                val delimiter = line.indexOf(',')
                require(delimiter >= 1) { "Invalid geoname review row ${index + 1} in $path: $raw" }
                val japanese = line.substring(0, delimiter).csvValue()
                val translation = line.substring(delimiter + 1).csvValue()
                require(put(japanese, translation) == null) {
                    "Duplicate geoname review entry for $japanese in $path"
                }
            }
        }
    }

    private fun mergeReviewFiles(vararg paths: Path): Map<String, String> = buildMap {
        paths.forEach { path ->
            readReviewFile(path).forEach { (japanese, translation) ->
                val previous = this[japanese].orEmpty()
                require(previous.isBlank() || translation.isBlank() || previous == translation) {
                    "Conflicting reviewed translations for $japanese: '$previous' and '$translation'"
                }
                if (translation.isNotBlank() || japanese !in this) put(japanese, translation)
            }
        }
    }

    private fun reviewFile(directory: Path, language: String, kind: GeoNameUsageKind): Path =
        directory.resolve("church-ja-$language-${kind.filePart}-missing.csv")

    private fun legacyReviewFile(directory: Path, language: String): Path =
        directory.resolve("church-ja-$language-missing.csv")

    private fun List<Pair<String, String>>.toReviewCsv(): String =
        joinToString(separator = "\n", postfix = if (isEmpty()) "" else "\n") { (ja, translated) ->
            "${ja.csvField()},${translated.csvField()}"
        }

    private enum class GeoNameUsageKind(val filePart: String) {
        TITLE("title") {
            override fun includes(sources: List<String>) = "TITLE" in sources
        },
        ADDRESS("address") {
            // A place used in both belongs to the higher-priority title queue only.
            override fun includes(sources: List<String>) = "ADDRESS" in sources && "TITLE" !in sources
        };

        abstract fun includes(sources: List<String>): Boolean
    }

    private fun String.normalizedLanguage(): String = substringBefore('-').lowercase()

    private fun String.csvField(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else this

    private fun String.csvValue(): String {
        val value = trim()
        return if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1).replace("\"\"", "\"")
        } else value
    }

    private fun atomicWrite(destination: Path, content: String) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        val TARGET_LANGUAGES = listOf("en", "ko", "pt", "id", "vi")
    }
}
