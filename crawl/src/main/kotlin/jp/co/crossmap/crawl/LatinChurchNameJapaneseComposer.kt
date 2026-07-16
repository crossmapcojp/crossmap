package jp.co.crossmap.crawl

import java.text.Normalizer

internal data class LatinChurchNameJapanesePart(
    val source: String,
    val japanese: String,
)

/**
 * Composes a Japanese display name from Latin-script church-name components.
 * Reviewed concepts and geonames use longest phrase matching; only unmatched words
 * fall back to ICU transliteration.
 */
internal class LatinChurchNameJapaneseComposer(
    concepts: Map<String, String>,
    geonames: Map<String, String>,
    additionalTerms: Map<String, String> = emptyMap(),
    private val fallback: (String, String?) -> String? = LatinChurchNameJapaneseTranslator::translate,
) {
    private data class Term(val words: List<String>, val japanese: String)

    private val termsByFirstWord: Map<String, List<Term>> = buildMap {
        reverseGeonames(geonames).forEach(::put)
        STRUCTURAL_TERMS.forEach { (latin, japanese) -> put(normalizePhrase(latin), japanese) }
        // A reviewed dictionary is ordered. A later row intentionally overrides an earlier synonym.
        concepts.forEach { (japanese, latin) -> put(normalizePhrase(latin), japanese) }
        additionalTerms.forEach { (latin, japanese) -> put(normalizePhrase(latin), japanese) }
    }.map { (latin, japanese) -> Term(latin.split(' '), japanese) }
        .sortedWith(compareByDescending<Term> { it.words.size }.thenByDescending { it.words.sumOf(String::length) })
        .groupBy { it.words.first() }

    fun translate(value: String, language: String?): String? {
        if (JAPANESE_SCRIPT.containsMatchIn(value)) {
            return LATIN_FRAGMENT.replace(value) { translate(it.value, language).orEmpty() }
                .replace(Regex("""\s+"""), "")
                .takeIf(String::isNotBlank)
        }
        return translateParts(value, language).joinToString("") { it.japanese }.takeIf(String::isNotBlank)
    }

    /** Translates a Latin title without discarding the original phrase boundaries. */
    fun translateParts(value: String, language: String?): List<LatinChurchNameJapanesePart> {
        if (JAPANESE_SCRIPT.containsMatchIn(value)) {
            return listOfNotNull(translate(value, language)?.let { LatinChurchNameJapanesePart(value, it) })
        }
        val sourceWords = tokenizeSource(value)
        if (sourceWords.isEmpty()) {
            return listOfNotNull(fallback(value, language)?.let { LatinChurchNameJapanesePart(value, it) })
        }
        val normalizedWords = sourceWords.map(::normalizeWord)
        return buildList {
            var index = 0
            while (index < sourceWords.size) {
                val term = termsByFirstWord[normalizedWords[index]]?.firstOrNull { candidate ->
                    index + candidate.words.size <= normalizedWords.size &&
                        candidate.words.indices.all { offset -> candidate.words[offset] == normalizedWords[index + offset] }
                }
                if (term != null) {
                    add(
                        LatinChurchNameJapanesePart(
                            source = sourceWords.subList(index, index + term.words.size).joinToString(" "),
                            japanese = term.japanese,
                        ),
                    )
                    index += term.words.size
                } else {
                    val sourceWord = sourceWords[index]
                    val japanese = if (ACRONYM.matches(sourceWord)) {
                        sourceWord.uppercase()
                    } else {
                        fallback(sourceWord, language).orEmpty().replace(Regex("""\s+"""), "")
                    }
                    if (japanese.isNotBlank()) add(LatinChurchNameJapanesePart(sourceWord, japanese))
                    index++
                }
            }
        }
    }

    private fun tokenizeSource(value: String): List<String> {
        val withoutAcronymDots = DOTTED_ACRONYM.replace(value) { it.value.replace(".", "") }
        return WORD.findAll(CAMEL_BOUNDARY.replace(withoutAcronymDots, " "))
        .map(MatchResult::value)
        .flatMap { word ->
            val suffix = STRUCTURAL_TERMS.keys.firstOrNull { term ->
                word.length > term.length && word.endsWith(term, ignoreCase = true)
            }
            if (suffix == null) sequenceOf(word) else sequenceOf(word.dropLast(suffix.length), suffix)
        }
        .toList()
    }

    private fun reverseGeonames(geonames: Map<String, String>): Map<String, String> = geonames.entries
        .filter { (japanese, latin) -> japanese.isNotBlank() && latin.isNotBlank() }
        .groupBy { normalizePhrase(it.value) }
        .mapValues { (_, candidates) ->
            candidates.minWithOrNull(
                compareBy(
                    { candidate -> candidate.key.none { it in '\u3400'..'\u9fff' } },
                    { ADMINISTRATIVE_SUFFIX.containsMatchIn(it.key) },
                    { it.key.length },
                ),
            )!!.key
        }

    private companion object {
        val WORD = Regex("""[\p{IsLatin}]+(?:['’][\p{IsLatin}]+)?|[0-9]+""")
        val CAMEL_BOUNDARY = Regex("""(?<=\p{Ll})(?=\p{Lu})""")
        val JAPANESE_SCRIPT = Regex("""[\u3040-\u30ff\u3400-\u9fff]""")
        val LATIN_FRAGMENT = Regex("""[\p{IsLatin}0-9]+(?:[ .'-]+[\p{IsLatin}0-9]+)*""")
        val DOTTED_ACRONYM = Regex("""(?:\b[\p{Lu}]\.){2,}[\p{Lu}]?""")
        val ACRONYM = Regex("""[A-Z0-9]{2,6}""")
        val ADMINISTRATIVE_SUFFIX = Regex("""[都道府県市区町村郡]$""")
        val STRUCTURAL_TERMS = linkedMapOf(
            "Church" to "チャーチ",
            "Churches" to "チャーチ",
            "Chapel" to "チャペル",
            "Christian" to "クリスチャン",
            "Christ" to "キリスト",
            "Fellowship" to "フェローシップ",
            "Assembly" to "アッセンブリー",
            "Japan" to "ジャパン",
        )

        fun normalizePhrase(value: String): String = WORD.findAll(value)
            .joinToString(" ") { normalizeWord(it.value) }

        fun normalizeWord(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace('’', '\'')
    }
}
