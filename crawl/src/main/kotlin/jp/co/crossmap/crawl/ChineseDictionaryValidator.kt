package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChineseScriptNormalizer
import kotlinx.serialization.Serializable

@Serializable
data class ChineseDictionaryValidationReport(
    val filesChecked: Int,
    val entriesChecked: Int,
    val errors: List<String>,
    val reviewSignals: List<String>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

/** Validates paired JA -> zh-Hans/zh-Hant dictionaries without treating legitimate identical terms as errors. */
object ChineseDictionaryValidator {
    fun validate(resourcesRoot: Path): ChineseDictionaryValidationReport {
        val directory = resourcesRoot.resolve("dictionary")
        val candidates = Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().contains("zh-", ignoreCase = true) }
                .sorted()
                .toList()
        }
        val errors = mutableListOf<String>()
        val signals = mutableListOf<String>()
        val parsed = linkedMapOf<Pair<String, String>, Map<String, String>>()
        var entries = 0
        candidates.forEach { path ->
            val match = dictionaryFile.matchEntire(path.fileName.toString())
            if (match == null) {
                errors += "Malformed Chinese dictionary filename or locale: ${path.fileName}"
                return@forEach
            }
            val locale = match.groupValues[1]
            val category = match.groupValues[2]
            val values = linkedMapOf<String, String>()
            Files.readAllLines(path).forEachIndexed { index, raw ->
                if (raw.isBlank() || raw.trimStart().startsWith('#')) return@forEachIndexed
                if (raw != raw.trim()) errors += "${path.fileName}:${index + 1}: leading or trailing whitespace"
                val columns = raw.removePrefix("\uFEFF").split(',', limit = 2)
                if (columns.size != 2 || columns.any { it.trim().isBlank() }) {
                    errors += "${path.fileName}:${index + 1}: expected nonblank source,target"
                    return@forEachIndexed
                }
                val source = columns[0].trim()
                val target = columns[1].trim()
                val previous = values.putIfAbsent(source, target)
                if (previous != null) errors += "${path.fileName}:${index + 1}: duplicate source '$source'"
                entries++
            }
            values.entries.groupBy { normalize(it.value) }.filterValues { it.size > 1 }.forEach { (target, duplicates) ->
                signals += "${path.fileName}: duplicate normalized target '$target' from ${duplicates.joinToString { it.key }}"
            }
            values.forEach { (source, target) ->
                if (target != source && values[target] == source) {
                    errors += "${path.fileName}: cyclic aliases '$source' and '$target'"
                }
                val shinjitai = suspiciousShinjitai.firstOrNull(target::contains)
                if (shinjitai != null) signals += "${path.fileName}: '$source' uses reviewable Japanese Shinjitai '$shinjitai' in '$target'"
            }
            parsed[locale to category] = values
        }
        val categories = parsed.keys.map(Pair<String, String>::second).toSet()
        categories.forEach { category ->
            val hans = parsed["zh-Hans" to category]
            val hant = parsed["zh-Hant" to category]
            if (hans == null || hant == null) {
                errors += "Chinese dictionary category '$category' must have both zh-Hans and zh-Hant files"
                return@forEach
            }
            (hans.keys + hant.keys).distinct().forEach { source ->
                val simplified = hans[source]
                val traditional = hant[source]
                if (simplified == null || traditional == null) {
                    errors += "Chinese dictionary category '$category' has unpaired source '$source'"
                } else if (simplified == traditional) {
                    signals += "$category '$source': Simplified and Traditional targets are identical ('$simplified')"
                } else if (ChineseScriptNormalizer.toSimplified(traditional) != simplified) {
                    signals += "$category '$source': locale targets differ lexically ('$simplified' / '$traditional')"
                }
            }
        }
        return ChineseDictionaryValidationReport(
            filesChecked = candidates.size,
            entriesChecked = entries,
            errors = errors.distinct().sorted(),
            reviewSignals = signals.distinct().sorted(),
        )
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), "").lowercase()

    private val dictionaryFile = Regex("ja-(zh-Hans|zh-Hant)-(churchname|concept|geoname)-dictionary\\.csv")
    private val suspiciousShinjitai = listOf("恵", "沢", "浜", "辺", "広", "竜")
}
