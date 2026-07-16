package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class CongregationTerm(
    val id: String,
    val names: Map<String, List<String>>,
)

class CongregationTermDictionary private constructor(
    val terms: List<CongregationTerm>,
) {
    fun translations(sourceLanguage: String, targetLanguage: String): Map<String, String> = buildMap {
        terms.forEach { term ->
            val target = term.names[targetLanguage.lowercase()]?.firstOrNull() ?: return@forEach
            term.names[sourceLanguage.lowercase()].orEmpty().forEach { source -> put(source, target) }
        }
    }

    fun languageOfExactName(name: String): String? = terms.asSequence()
        .flatMap { term -> term.names.asSequence() }
        .firstOrNull { (_, aliases) -> aliases.any { it.equals(name, ignoreCase = true) } }
        ?.key

    companion object {
        fun load(resourcesRoot: Path): CongregationTermDictionary {
            val path = resourcesRoot.resolve("dictionary/congregation-terms.json")
            require(Files.isRegularFile(path)) { "Required congregation term dictionary is missing: $path" }
            val terms = Json { ignoreUnknownKeys = true }.decodeFromString<List<CongregationTerm>>(Files.readString(path))
            require(terms.map(CongregationTerm::id).distinct().size == terms.size) {
                "Duplicate congregation term IDs in $path"
            }
            terms.forEach { term ->
                require(term.id.isNotBlank() && term.names.size >= 2 && term.names.values.flatten().none(String::isBlank)) {
                    "Invalid congregation term ${term.id} in $path"
                }
            }
            return CongregationTermDictionary(terms)
        }
    }
}
