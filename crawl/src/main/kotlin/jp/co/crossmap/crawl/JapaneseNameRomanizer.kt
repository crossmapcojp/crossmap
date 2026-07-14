package jp.co.crossmap.crawl

import org.gnit.lucenekmp.analysis.Analyzer
import org.gnit.lucenekmp.analysis.Tokenizer
import org.gnit.lucenekmp.analysis.ja.JapaneseReadingFormFilter
import org.gnit.lucenekmp.analysis.ja.JapaneseTokenizer
import org.gnit.lucenekmp.analysis.tokenattributes.CharTermAttribute

/** Deterministic Hepburn-style romanization backed by lucene-kmp Kuromoji readings. */
object JapaneseNameRomanizer {
    private val analyzer = object : Analyzer() {
        override fun createComponents(fieldName: String): TokenStreamComponents {
            val tokenizer: Tokenizer = JapaneseTokenizer(null, true, JapaneseTokenizer.Mode.SEARCH)
            return TokenStreamComponents(tokenizer, JapaneseReadingFormFilter(tokenizer, true))
        }
    }

    @Synchronized
    fun romanize(value: String): String? {
        if (value.isBlank()) return null
        val terms = buildList {
            analyzer.tokenStream("church-name", value).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class)
                stream.reset()
                while (stream.incrementToken()) add(term.toString())
                stream.end()
            }
        }
        return terms.joinToString(" ")
            .replace(Regex("""[^A-Za-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .split(' ')
            .filter(String::isNotBlank)
            .joinToString(" ") { token -> token.lowercase().replaceFirstChar(Char::uppercase) }
            .takeIf(String::isNotBlank)
    }
}
