package jp.co.crossmap.crawl

import com.ibm.icu.text.Transliterator
import java.text.Normalizer

/** Korean pronunciation fallback derived from the authoritative English romaji geoname. */
internal object JapaneseRomajiToHangul {
    private val latinToHangul by lazy { Transliterator.getInstance("Latin-Hangul") }
    private val latinLetter = Regex("""[A-Za-z]""")
    private val combiningMark = Regex("""\p{M}+""")
    private val initialJapaneseVoicing = mapOf(
        "카" to "가", "키" to "기", "쿠" to "구", "케" to "게", "코" to "고", "쿄" to "교",
        "타" to "다", "티" to "디", "투" to "두", "테" to "데", "토" to "도", "치" to "지",
    )
    private val koreanLetterNames = mapOf(
        'A' to "에이", 'B' to "비", 'C' to "시", 'D' to "디", 'E' to "이", 'F' to "에프",
        'G' to "지", 'H' to "에이치", 'I' to "아이", 'J' to "제이", 'K' to "케이", 'L' to "엘",
        'M' to "엠", 'N' to "엔", 'O' to "오", 'P' to "피", 'Q' to "큐", 'R' to "알",
        'S' to "에스", 'T' to "티", 'U' to "유", 'V' to "브이", 'W' to "더블유",
        'X' to "엑스", 'Y' to "와이", 'Z' to "지",
    )

    @Synchronized
    fun transliterate(romaji: String): String? {
        val normalized = Normalizer.normalize(romaji.trim(), Normalizer.Form.NFKD)
            .replace(combiningMark, "")
        if (normalized.isBlank() || !latinLetter.containsMatchIn(normalized)) return null
        val hepburn = normalized.lowercase()
            .replace(Regex("""sh([auo])"""), "sy$1")
            .replace("shi", "si")
            .replace("fu", "hu")
        var hangul = latinToHangul.transliterate(hepburn)
            .replace("츠", "쓰")
            .replace(Regex("""\s+"""), " ")
            .trim()
        initialJapaneseVoicing.entries.firstOrNull { hangul.startsWith(it.key) }?.let { (from, to) ->
            hangul = to + hangul.removePrefix(from)
        }
        if (hepburn.startsWith('z') && hangul.firstOrNull()?.let(::initialConsonantIndex) == 9) {
            hangul = replaceInitialConsonant(hangul, 12)
        }
        return hangul.takeIf { value -> value.any(::isHangul) && !latinLetter.containsMatchIn(value) }
    }

    fun hasCompatibleInitial(romaji: String, hangul: String): Boolean {
        val latinInitial = romaji.firstOrNull(Char::isLetter)?.uppercaseChar() ?: return false
        val hangulInitial = hangul.firstOrNull(::isHangul)?.let(::initialConsonantIndex) ?: return false
        val allowed = when (latinInitial) {
            'A', 'E', 'I', 'O', 'U', 'W', 'Y' -> setOf(11) // ㅇ
            'G' -> setOf(0) // ㄱ
            'K', 'Q' -> setOf(0, 15) // ㄱ or ㅋ
            'N' -> setOf(2) // ㄴ
            'D' -> setOf(3) // ㄷ
            'T' -> setOf(3, 16) // ㄷ or ㅌ
            'R', 'L' -> setOf(5) // ㄹ
            'M' -> setOf(6) // ㅁ
            'B', 'V' -> setOf(7) // ㅂ
            'P' -> setOf(17) // ㅍ
            'F' -> setOf(17, 18) // ㅍ, or ㅎ for Japanese fu -> hu
            'S' -> setOf(9) // ㅅ
            'J', 'Z' -> setOf(12) // ㅈ
            'C' -> setOf(12, 14) // ㅈ or ㅊ
            'H' -> setOf(18) // ㅎ
            else -> return true
        }
        return hangulInitial in allowed
    }

    fun churchAbbreviations(value: String): Set<String> = churchAbbreviation.findAll(value)
        .map(MatchResult::value)
        .filterNot { it in uppercaseWordsThatAreNotAbbreviations }
        .toSet()

    fun transliterateLatinFragments(
        value: String,
        preservedAbbreviations: Set<String> = emptySet(),
    ): String = latinLetterSequence.replace(value) { match ->
        val token = match.value
        if (token in preservedAbbreviations) {
            token
        } else if (token.length > 1 && token.all(Char::isUpperCase)) {
            token.mapNotNull { koreanLetterNames[it] }.joinToString("")
        } else {
            transliterate(token) ?: token
        }
    }

    private fun isHangul(char: Char): Boolean = char in '\uac00'..'\ud7af'

    private fun initialConsonantIndex(char: Char): Int = (char.code - 0xAC00) / (21 * 28)

    private fun replaceInitialConsonant(value: String, initialIndex: Int): String {
        val first = value.firstOrNull()?.takeIf(::isHangul) ?: return value
        val vowelAndFinal = (first.code - 0xAC00) % (21 * 28)
        return (0xAC00 + initialIndex * 21 * 28 + vowelAndFinal).toChar() + value.drop(1)
    }

    private val latinLetterSequence = Regex("""[A-Za-z]+""")
    private val churchAbbreviation = Regex("""(?<![A-Z])[A-Z]{3,4}(?![A-Z])""")
    private val uppercaseWordsThatAreNotAbbreviations = setOf(
        "ABBA", "AND", "COM", "DAS", "DEL", "DEUS", "DOS", "FOR", "GOD", "LAS", "LORD", "LOS", "THE",
    )
}
