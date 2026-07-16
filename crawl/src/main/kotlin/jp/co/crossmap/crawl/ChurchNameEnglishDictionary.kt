package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path

enum class ChurchNameDictionaryCategory { CHURCHNAME, CONCEPT, GEONAME }

data class ChurchNameDictionaryKey(
    val sourceLanguage: String,
    val targetLanguage: String,
    val category: ChurchNameDictionaryCategory,
)

class MultilingualChurchNameDictionary internal constructor(
    private val entriesByKey: Map<ChurchNameDictionaryKey, Map<String, String>>,
) {
    fun entries(
        sourceLanguage: String,
        targetLanguage: String,
        category: ChurchNameDictionaryCategory,
    ): Map<String, String> = entriesByKey[
        ChurchNameDictionaryKey(sourceLanguage.lowercase(), targetLanguage.lowercase(), category),
    ].orEmpty()

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        category: ChurchNameDictionaryCategory,
    ): String? = entries(sourceLanguage, targetLanguage, category)[text]

    internal companion object {
        fun from(entries: Map<ChurchNameDictionaryKey, Map<String, String>>): MultilingualChurchNameDictionary =
            MultilingualChurchNameDictionary(entries)

        fun fromJapaneseEnglish(concepts: Map<String, String>, geonames: Map<String, String>) = from(
            mapOf(
                ChurchNameDictionaryKey("ja", "en", ChurchNameDictionaryCategory.CONCEPT) to concepts,
                ChurchNameDictionaryKey("ja", "en", ChurchNameDictionaryCategory.GEONAME) to geonames,
            ),
        )
    }
}

data class ChurchNameEnglishDictionaries(
    val concepts: Map<String, String>,
    val geonames: Map<String, String>,
    val multilingual: MultilingualChurchNameDictionary =
        MultilingualChurchNameDictionary.fromJapaneseEnglish(concepts, geonames),
) {
    val entries: Set<String> get() = concepts.keys + geonames.keys
}

/** Loads reviewed `<source>-<target>-<category>-dictionary.csv` files. */
object ChurchNameEnglishDictionary {
    fun load(resourcesRoot: Path): ChurchNameEnglishDictionaries {
        val directory = resourcesRoot.resolve("dictionary")
        require(Files.isDirectory(directory)) { "Required church-name dictionary directory is missing: $directory" }
        val multilingualEntries = linkedMapOf<ChurchNameDictionaryKey, Map<String, String>>()
        Files.list(directory).use { paths ->
            paths.filter(Files::isRegularFile).sorted().forEach { path ->
                val match = DICTIONARY_FILE.matchEntire(path.fileName.toString()) ?: return@forEach
                val key = ChurchNameDictionaryKey(
                    sourceLanguage = match.groupValues[1],
                    targetLanguage = match.groupValues[2],
                    category = ChurchNameDictionaryCategory.valueOf(match.groupValues[3].uppercase()),
                )
                multilingualEntries[key] = read(path)
            }
        }
        val directEntries = multilingualEntries.toMap()
        directEntries.forEach { (key, values) ->
            val reverseKey = key.copy(
                sourceLanguage = key.targetLanguage,
                targetLanguage = key.sourceLanguage,
            )
            if (reverseKey !in directEntries) {
                multilingualEntries[reverseKey] = buildMap {
                    values.forEach { (source, target) -> putIfAbsent(target, source) }
                }
            }
        }
        val multilingual = MultilingualChurchNameDictionary.from(multilingualEntries)
        return ChurchNameEnglishDictionaries(
            concepts = multilingual.entries("ja", "en", ChurchNameDictionaryCategory.CONCEPT),
            geonames = multilingual.entries("ja", "en", ChurchNameDictionaryCategory.GEONAME),
            multilingual = multilingual,
        )
    }

    internal fun read(path: Path): Map<String, String> {
        require(Files.isRegularFile(path)) { "Required church-name dictionary is missing: $path" }
        val entries = linkedMapOf<String, String>()
        Files.readAllLines(path).forEachIndexed { index, rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val columns = line.split(',', limit = 2).map(String::trim)
            require(columns.size == 2 && columns.all(String::isNotBlank)) {
                "Invalid dictionary row ${index + 1} in $path: $rawLine"
            }
            val previous = entries.putIfAbsent(columns[0], columns[1])
            require(previous == null) {
                "Duplicate dictionary entry for ${columns[0]} in $path: $previous / ${columns[1]}"
            }
        }
        return entries
    }

    private val DICTIONARY_FILE = Regex(
        """([a-z]{2})-([a-z]{2})-(churchname|concept|geoname)-dictionary\.csv""",
    )
}
