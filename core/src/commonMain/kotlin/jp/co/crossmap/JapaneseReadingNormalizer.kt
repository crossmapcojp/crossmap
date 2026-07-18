package jp.co.crossmap

import org.gnit.lucenekmp.analysis.Analyzer
import org.gnit.lucenekmp.analysis.Tokenizer
import org.gnit.lucenekmp.analysis.ja.JapaneseReadingFormFilter
import org.gnit.lucenekmp.analysis.ja.JapaneseTokenizer
import org.gnit.lucenekmp.analysis.tokenattributes.CharTermAttribute

/** Produces deterministic hiragana search terms from Japanese text using Kuromoji readings. */
object JapaneseReadingNormalizer {
    private const val READING_FIELD = "_japanese_reading"
    // JapaneseAnalyzer applies stop-word filtering. That is useful for prose search but corrupts
    // short proper names such as さぬき市 by dropping the leading さ and producing ぬきし.
    private val analyzer by lazy {
        object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val tokenizer: Tokenizer = JapaneseTokenizer(null, true, JapaneseTokenizer.Mode.SEARCH)
                return TokenStreamComponents(tokenizer, JapaneseReadingFormFilter(tokenizer, false))
            }
        }
    }

    fun reading(value: String): String {
        if (value.isBlank()) return ""
        return buildList {
            JapaneseReadingFormFilter(analyzer.tokenStream(READING_FIELD, value), false).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class)
                stream.reset()
                while (stream.incrementToken()) {
                    term.toString().takeIf(String::isNotBlank)?.let(::add)
                }
                stream.end()
            }
        }.joinToString(" ") { it.katakanaToHiragana() }
    }

    fun compactReading(value: String): String = reading(value).replace(" ", "")

    /** Includes common alternative readings that Kuromoji intentionally chooses only one of. */
    fun searchReadings(value: String): List<String> {
        val primary = reading(value)
        if (primary.isBlank()) return emptyList()
        return buildList {
            add(primary)
            if (value.contains("日本") && primary.contains("にっぽん")) {
                add(primary.replace("にっぽん", "にほん"))
            }
        }.distinct()
    }

    private fun String.katakanaToHiragana(): String = buildString(length) {
        this@katakanaToHiragana.forEach { char ->
            append(
                if (char.code in 0x30A1..0x30F6) (char.code - 0x60).toChar() else char,
            )
        }
    }
}
