package jp.co.crossmap.crawl

import java.text.Normalizer
import com.cybozu.labs.langdetect.DetectorFactory
import com.ibm.icu.text.Transliterator
import jp.co.crossmap.LocalizedName
import kotlinx.serialization.Serializable

internal data class DecomposedChurchName(
    val originalName: String,
    val japaneseName: String?,
    val latinName: String?,
    val localizedNames: List<LocalizedName> = emptyList(),
    val pattern: ChurchNamePattern = ChurchNamePattern.SINGLE_NAME,
)

@Serializable
enum class ChurchNamePattern {
    SINGLE_NAME,
    LATIN_NAME_WITH_JAPANESE_PARENTHETICAL,
    LATIN_NAME_WITH_JAPANESE_WHITESPACE_ALIAS,
    LATIN_NAME_WITH_JAPANESE_CONGREGATION_SUFFIX,
    LATIN_ABBREVIATION_WITH_FULL_NAME,
    JAPANESE_NAME_WITH_LATIN_PIPE_ALIAS,
    JAPANESE_NAME_WITH_KOREAN_PARENTHETICAL,
    KOREAN_NAME_WITH_JAPANESE_PARENTHETICAL,
    JAPANESE_NAME_WITH_BRANCH_PARENTHETICAL,
    JAPANESE_NAME_WITH_KANA_READING,
    JAPANESE_NAME_WITH_CHURCH_DESCRIPTOR,
    JAPANESE_ABBREVIATION_WITH_FULL_NAME_PARENTHETICAL,
    JAPANESE_ABBREVIATION_WITH_FULL_NAME_WHITESPACE,
    JAPANESE_NAME_WITH_JAPANESE_ALIAS,
    MULTILINGUAL_SLASH_ALIASES,
    JAPANESE_NAME_WITH_LATIN_PARENTHETICAL,
    LATIN_PIPE_ALIASES,
    JAPANESE_NAME_WITH_LATIN_ABBREVIATION,
    LATIN_NAME_WITH_ABBREVIATION,
    JAPANESE_NAME_WITH_LATIN_WHITESPACE_ALIAS,
    LATIN_ABBREVIATION_JAPANESE_NAME_TRAILING_GEONAME_BRANCH,
    LATIN_DENOMINATION_ABBREVIATION_WITH_JAPANESE_NAME,
    LATIN_NAME_COMPOSED_TO_JAPANESE,
}

internal class ChurchNameDecomposer(
    private val languageIdentifier: (String) -> String? = CybozuChurchNameLanguageIdentifier::detect,
    private val latinToJapanese: (String, String?) -> String? = LatinChurchNameJapaneseTranslator::translate,
    branchGeonames: Set<String> = emptySet(),
    knownLatinAbbreviations: Set<String> = emptySet(),
) {
    private val branchGeonames = branchGeonames.filter(String::isNotBlank).sortedByDescending(String::length)
    private val knownLatinAbbreviations = knownLatinAbbreviations.map(String::uppercase).toSet()

    fun decompose(value: String): DecomposedChurchName {
        val original = ChurchPublicNameNormalizer.normalize(value)
        require(original.isNotBlank()) { "Church name must not be blank" }

        fun complete(result: DecomposedChurchName): DecomposedChurchName {
            val completed = if (result.japaneseName == null) {
                val source = result.latinName ?: result.originalName
                result.copy(japaneseName = latinToJapanese(source, languageIdentifier(source)))
            } else {
                result
            }
            val canonicalNames = buildList {
                completed.japaneseName?.let { add(LocalizedName("ja", it)) }
                completed.latinName?.let { latinName ->
                    add(LocalizedName(languageIdentifier(latinName)?.lowercase() ?: "en", latinName))
                }
            }
            return completed.copy(
                localizedNames = (canonicalNames + completed.localizedNames)
                    .distinctBy { it.languageCode.lowercase() to it.name },
            )
        }

        latinWithJapaneseParenthetical(original)?.let { return complete(it) }
        latinWithJapaneseWhitespaceAlias(original)?.let { return complete(it) }
        latinAbbreviationWithFullName(original)?.let { return complete(it) }
        japaneseWithLatinPipeAlias(original)?.let { return complete(it) }
        japaneseWithKoreanParenthetical(original)?.let { return complete(it) }
        koreanWithJapaneseParenthetical(original)?.let { return complete(it) }
        japaneseWithBranchParenthetical(original)?.let { return complete(it) }
        japaneseWithKanaReading(original)?.let { return complete(it) }
        japaneseWithChurchDescriptor(original)?.let { return complete(it) }
        japaneseAbbreviationWithFullNameParenthetical(original)?.let { return complete(it) }
        japaneseAbbreviationWithFullNameWhitespace(original)?.let { return complete(it) }
        japaneseWithJapaneseAlias(original)?.let { return complete(it) }
        japaneseWithLatinParenthetical(original)?.let { return complete(it) }
        multilingualSlashAliases(original)?.let { return complete(it) }
        latinPipeAliases(original)?.let { return complete(it) }
        japaneseWithLatinAbbreviation(original)?.let { return complete(it) }
        latinWithAbbreviation(original)?.let { return complete(it) }
        japaneseWithLatinWhitespaceAlias(original)?.let { return complete(it) }
        latinAbbreviationJapaneseNameTrailingGeonameBranch(original)?.let { return complete(it) }
        latinDenominationAbbreviationWithJapaneseName(original)?.let { return complete(it) }

        val hasJapanese = containsJapanese(original)
        val hasLatin = containsLatin(original)
        val detectedLanguage = languageIdentifier(original)?.lowercase()
        val latin = original.takeIf { hasLatin && !hasJapanese }
        val japanese = when {
            hasJapanese && hasLatin -> latinToJapanese(original, detectedLanguage)
            hasJapanese -> original
            latin != null -> latinToJapanese(latin, detectedLanguage)
            else -> latinToJapanese(original, detectedLanguage)
        }
        val localizedNames = when {
            latin != null -> latin.toLocalizedName()
            detectedLanguage != null && detectedLanguage !in setOf("ja", "en") ->
                listOf(LocalizedName(detectedLanguage, original))
            else -> emptyList()
        }
        return complete(DecomposedChurchName(
            originalName = original,
            japaneseName = japanese,
            latinName = latin,
            localizedNames = localizedNames,
            pattern = if (latin != null) ChurchNamePattern.LATIN_NAME_COMPOSED_TO_JAPANESE else ChurchNamePattern.SINGLE_NAME,
        ))
    }

    private fun latinWithJapaneseParenthetical(value: String): DecomposedChurchName? {
        val matches = PARENTHETICAL.findAll(value).toList()
        val japaneseMatch = matches.lastOrNull { containsJapanese(it.groupValues[1]) } ?: return null
        val beforeJapanese = value.substring(0, japaneseMatch.range.first).trim()
        if (!containsLatin(beforeJapanese) || containsJapanese(beforeJapanese)) return null

        // A Latin abbreviation/location between the full Latin name and Japanese alias is metadata,
        // not part of the canonical Latin name: `Gereja ... Indonesia (GIII) Oarai (大洗...)`.
        val latinName = beforeJapanese.substringBefore('(').substringBefore('（').trim().trimEnd('-', ' ')
        if (latinName.isBlank()) return null
        val japaneseName = japaneseMatch.groupValues[1].split(Regex("""[/／]""")).first().trim()
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_PARENTHETICAL,
        )
    }

    private fun latinAbbreviationJapaneseNameTrailingGeonameBranch(value: String): DecomposedChurchName? {
        val abbreviation = LEADING_LATIN_ABBREVIATION.find(value)?.value ?: return null
        if (abbreviation !in knownLatinAbbreviations) return null
        val japanesePart = value.removePrefix(abbreviation)
        if (!containsJapanese(japanesePart)) return null
        val branchGeoname = branchGeonames.firstOrNull(japanesePart::endsWith) ?: return null
        val churchName = japanesePart.removeSuffix(branchGeoname)
        if (JAPANESE_CONGREGATION_WORDS.none(churchName::contains)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = value,
            latinName = null,
            pattern = ChurchNamePattern.LATIN_ABBREVIATION_JAPANESE_NAME_TRAILING_GEONAME_BRANCH,
        )
    }

    private fun latinDenominationAbbreviationWithJapaneseName(value: String): DecomposedChurchName? {
        val abbreviation = LEADING_LATIN_ABBREVIATION.find(value)?.value ?: return null
        if (abbreviation !in knownLatinAbbreviations) return null
        if (!containsJapanese(value.removePrefix(abbreviation))) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = value,
            latinName = null,
            pattern = ChurchNamePattern.LATIN_DENOMINATION_ABBREVIATION_WITH_JAPANESE_NAME,
        )
    }

    private fun latinWithJapaneseWhitespaceAlias(value: String): DecomposedChurchName? {
        val boundaries = value.indices.filter { value[it].isWhitespace() }
        val (latinName, japaneseName) = boundaries.asSequence()
            .map { value.substring(0, it).trim() to value.substring(it + 1).trim() }
            .firstOrNull { (latin, japanese) ->
                latin.count(Char::isLetter) >= 3 &&
                    containsLatin(latin) &&
                    !containsJapanese(latin) &&
                    containsJapanese(japanese) &&
                    !containsLatin(japanese) &&
                    !containsKorean(japanese) &&
                    japanese.firstOrNull() !in setOf('/', '／', '|')
            } ?: return null

        if (japaneseName in JAPANESE_CONGREGATION_WORDS) {
            val language = languageIdentifier(latinName)?.lowercase()
            val translatedName = latinToJapanese(latinName, language)?.takeIf(String::isNotBlank) ?: return null
            return DecomposedChurchName(
                originalName = value,
                japaneseName = translatedName + japaneseName,
                latinName = latinName,
                localizedNames = latinName.toLocalizedName(),
                pattern = ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_CONGREGATION_SUFFIX,
            )
        }

        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.LATIN_NAME_WITH_JAPANESE_WHITESPACE_ALIAS,
        )
    }

    private fun latinAbbreviationWithFullName(value: String): DecomposedChurchName? {
        val match = LATIN_ABBREVIATION_WITH_FULL_NAME.matchEntire(value) ?: return null
        val latinName = match.groupValues[2].trim()
        val language = languageIdentifier(latinName)?.lowercase()
        return DecomposedChurchName(
            originalName = value,
            japaneseName = latinToJapanese(latinName, language),
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.LATIN_ABBREVIATION_WITH_FULL_NAME,
        )
    }

    private fun japaneseWithLatinPipeAlias(value: String): DecomposedChurchName? {
        val parts = value.split('|').map(String::trim).filter(String::isNotBlank)
        if (parts.size != 2) return null
        val japaneseName = parts.firstOrNull { containsJapanese(it) && !containsLatin(it) } ?: return null
        val latinName = parts.firstOrNull { containsLatin(it) && !containsJapanese(it) } ?: return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_PIPE_ALIAS,
        )
    }

    private fun japaneseWithKoreanParenthetical(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val koreanName = match.groupValues[1].trim()
        if (!containsKorean(koreanName)) return null
        val japaneseName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(japaneseName) || containsKorean(japaneseName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            localizedNames = listOf(LocalizedName("ko", koreanName)),
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_KOREAN_PARENTHETICAL,
        )
    }

    private fun koreanWithJapaneseParenthetical(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val japaneseName = match.groupValues[1].trim()
        if (!containsJapanese(japaneseName) || containsKorean(japaneseName)) return null
        val koreanName = value.substring(0, match.range.first).trim()
        if (!containsKorean(koreanName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            localizedNames = listOf(LocalizedName("ko", koreanName)),
            pattern = ChurchNamePattern.KOREAN_NAME_WITH_JAPANESE_PARENTHETICAL,
        )
    }

    private fun japaneseWithBranchParenthetical(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val branchName = match.groupValues[1].trim()
        if (!containsJapanese(branchName) || BRANCH_BUILDING_TERMS.none(branchName::endsWith)) return null
        val primaryName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(primaryName) || containsLatin(primaryName) || containsKorean(primaryName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = "$primaryName $branchName",
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_BRANCH_PARENTHETICAL,
        )
    }

    /**
     * Preserves the kanji spelling while retaining a parenthetical kana reading as a searchable alias.
     *
     * Examples:
     * - `香貫(かぬき)教会` -> canonical `香貫教会`, alias `かぬき教会`
     * - `日本基督(キリスト)教団 香貫教会` -> canonical `日本基督教団 香貫教会`,
     *   alias `日本キリスト教団 香貫教会`
     */
    private fun japaneseWithKanaReading(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val reading = match.groupValues[1].trim()
        if (!KANA_READING.matches(reading)) return null

        val before = value.substring(0, match.range.first)
        val after = value.substring(match.range.last + 1)
        TRAILING_KANJI.find(before)?.value ?: return null
        val canonicalName = (before + after).replace(Regex("""\s+"""), " ").trim()
        // Keep the reading alias deliberately narrow. Lucene fields are multi-valued, so the
        // canonical value supplies the prefix tokens while this value supplies the hard reading.
        // Guessing how many preceding kanji the annotation covers would corrupt names such as
        // `日本キリスト教団世真留(せまる)教会`.
        val readingName = (reading + after).replace(Regex("""\s+"""), " ").trim()
        if (!containsJapanese(canonicalName) || canonicalName == readingName) return null

        return DecomposedChurchName(
            originalName = value,
            japaneseName = canonicalName,
            latinName = null,
            localizedNames = listOf(LocalizedName("ja", readingName)),
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_KANA_READING,
        )
    }

    private fun japaneseWithChurchDescriptor(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        if (match.groupValues[1].trim() !in CHURCH_DESCRIPTORS) return null
        val japaneseName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(japaneseName) || containsLatin(japaneseName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_CHURCH_DESCRIPTOR,
        )
    }

    private fun japaneseAbbreviationWithFullNameParenthetical(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val japaneseName = match.groupValues[1].trim()
        val abbreviatedName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(japaneseName) || containsLatin(japaneseName)) return null
        if (!containsJapanese(abbreviatedName) || !LATIN_ABBREVIATION.containsMatchIn(abbreviatedName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_ABBREVIATION_WITH_FULL_NAME_PARENTHETICAL,
        )
    }

    private fun japaneseAbbreviationWithFullNameWhitespace(value: String): DecomposedChurchName? {
        val boundary = value.indices.firstOrNull { index ->
            if (!value[index].isWhitespace()) return@firstOrNull false
            val abbreviatedName = value.substring(0, index).trim()
            val fullName = value.substring(index + 1).trim()
            containsJapanese(abbreviatedName) &&
                LATIN_ABBREVIATION.containsMatchIn(abbreviatedName) &&
                containsJapanese(fullName) &&
                !containsLatin(fullName)
        } ?: return null
        val japaneseName = value.substring(boundary + 1).trim()
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_ABBREVIATION_WITH_FULL_NAME_WHITESPACE,
        )
    }

    private fun japaneseWithJapaneseAlias(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val alias = match.groupValues[1].trim()
        val japaneseName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(alias) || containsLatin(alias) || containsKorean(alias)) return null
        if (!containsJapanese(japaneseName) || containsLatin(japaneseName) || containsKorean(japaneseName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_JAPANESE_ALIAS,
        )
    }

    private fun multilingualSlashAliases(value: String): DecomposedChurchName? {
        val parts = value.split(Regex("""\s*[/／]\s*""")).map(String::trim).filter(String::isNotBlank)
        if (parts.size < 2) return null
        val japaneseName = parts.firstOrNull { containsJapanese(it) && !containsLatin(it) && !containsKorean(it) }
        val latinName = parts.firstOrNull { containsLatin(it) && !containsJapanese(it) && !containsKorean(it) }
        val localizedNames = buildList {
            latinName?.let { addAll(it.toLocalizedName()) }
            parts.filter(::containsKorean).forEach { add(LocalizedName("ko", it)) }
        }.distinct()
        if (japaneseName == null && latinName == null) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = localizedNames,
            pattern = ChurchNamePattern.MULTILINGUAL_SLASH_ALIASES,
        )
    }

    private fun japaneseWithLatinParenthetical(value: String): DecomposedChurchName? {
        val match = PARENTHETICAL.find(value) ?: return null
        val latinName = match.groupValues[1].trim()
        if (!containsLatin(latinName) || containsJapanese(latinName) || latinName.split(Regex("""\s+""")).size < 2) return null
        val outer = value.substring(0, match.range.first).trim()
        val japaneseName = outer.substring(outer.indexOfFirst(::isJapaneseCharacter).coerceAtLeast(0)).trim()
        if (!containsJapanese(japaneseName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = buildList {
                addAll(latinName.toLocalizedName())
                outer.takeIf(::containsKorean)?.let { add(LocalizedName("ko", it.substringBefore(japaneseName).trim())) }
            }.filter { it.name.isNotBlank() },
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_PARENTHETICAL,
        )
    }

    private fun latinPipeAliases(value: String): DecomposedChurchName? {
        val parts = value.split('|').map(String::trim).filter(String::isNotBlank)
        if (parts.size < 2 || parts.any { !containsLatin(it) || containsJapanese(it) || containsKorean(it) }) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = null,
            latinName = parts.first(),
            localizedNames = parts.drop(1).map { LocalizedName("en", it) },
            pattern = ChurchNamePattern.LATIN_PIPE_ALIASES,
        )
    }

    private fun japaneseWithLatinAbbreviation(value: String): DecomposedChurchName? {
        val match = TRAILING_LATIN_ABBREVIATION.find(value) ?: return null
        val japaneseName = value.substring(0, match.range.first).trim()
        if (!containsJapanese(japaneseName) || containsLatin(japaneseName)) return null
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = null,
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_ABBREVIATION,
        )
    }

    private fun latinWithAbbreviation(value: String): DecomposedChurchName? {
        val match = LATIN_METADATA_PARENTHETICAL.find(value) ?: return null
        val inner = match.groupValues[1].trim()
        if (!LATIN_ABBREVIATION.matches(inner.replace(Regex("""\s*[-#]\s*"""), ""))) return null
        if (containsJapanese(value) || containsKorean(value)) return null
        val latinName = (value.substring(0, match.range.first) + value.substring(match.range.last + 1))
            .replace(Regex("""\s+"""), " ").trim(' ', '-')
        return DecomposedChurchName(
            originalName = value,
            japaneseName = null,
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.LATIN_NAME_WITH_ABBREVIATION,
        )
    }

    private fun japaneseWithLatinWhitespaceAlias(value: String): DecomposedChurchName? {
        val boundaries = value.indices.filter { value[it].isWhitespace() }
        val (japaneseName, rawLatinName) = boundaries.asReversed().asSequence()
            .map { value.substring(0, it).trim() to value.substring(it + 1).trim() }
            .firstOrNull { (japanese, latin) ->
                containsJapanese(japanese) && !containsLatin(japanese) && containsLatin(latin) && !containsJapanese(latin)
            } ?: return null
        val latinName = rawLatinName.replace(TRAILING_LATIN_ABBREVIATION, "").trim()
        return DecomposedChurchName(
            originalName = value,
            japaneseName = japaneseName,
            latinName = latinName,
            localizedNames = latinName.toLocalizedName(),
            pattern = ChurchNamePattern.JAPANESE_NAME_WITH_LATIN_WHITESPACE_ALIAS,
        )
    }

    private fun String.toLocalizedName(): List<LocalizedName> {
        val language = languageIdentifier(this)?.lowercase() ?: return emptyList()
        return listOf(LocalizedName(language, this))
    }

    private companion object {
        val PARENTHETICAL = Regex("""[（(]([^()（）]+)[）)]""")
        val KANA_READING = Regex("""[ぁ-ゖァ-ヺー・\s]+""")
        val TRAILING_KANJI = Regex("""[\u3400-\u9fff々〆ヶ]+$""")
        val LATIN_ABBREVIATION_WITH_FULL_NAME = Regex("""^([A-Z][A-Z0-9]{1,12})\s*[（(]([^()（）]*[A-Za-z][^()（）]*)[）)]$""")
        val BRANCH_BUILDING_TERMS = listOf("礼拝堂", "会堂", "チャペル", "聖堂")
        val CHURCH_DESCRIPTORS = setOf("キリスト教会", "クリスチャン教会", "教会")
        val LATIN_ABBREVIATION = Regex("""[A-Z][A-Z0-9]{1,12}""")
        val LEADING_LATIN_ABBREVIATION = Regex("""^[A-Z][A-Z0-9]{1,12}""")
        val JAPANESE_CONGREGATION_WORDS = setOf("教会", "チャーチ", "チャペル", "集会", "フェローシップ")
        val TRAILING_LATIN_ABBREVIATION = Regex("""\s*[（(]\s*[A-Z][A-Z0-9-]{1,12}\s*[）)]\s*$""")
        val LATIN_METADATA_PARENTHETICAL = Regex("""[（(]\s*([^()（）]+)\s*[）)]""")
        fun containsJapanese(value: String): Boolean = value.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
        fun isJapaneseCharacter(value: Char): Boolean = value in '\u3040'..'\u30ff' || value in '\u3400'..'\u9fff'
        fun containsKorean(value: String): Boolean = value.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' }
        fun containsLatin(value: String): Boolean = value.any { it in 'A'..'Z' || it in 'a'..'z' }
    }
}

/** Deterministic translations are intentionally narrow; unresolved Latin names remain queued for LLM cleanup. */
internal object LatinChurchNameJapaneseTranslator {
    private val latinToKatakana by lazy { Transliterator.getInstance("Latin-Katakana") }
    private val anyToKatakana by lazy { Transliterator.getInstance("Any-Latin; Latin-Katakana") }

    @Synchronized
    fun translate(value: String, language: String?): String? {
        if (language == "pt" && value.equals("Igreja Evangélica das Nações", ignoreCase = true)) {
            return "諸国福音教会"
        }
        val translated = if (JAPANESE_SCRIPT.containsMatchIn(value)) {
            LATIN_PHRASE.replace(value) { match -> latinToKatakana.transliterate(match.value) }
        } else {
            anyToKatakana.transliterate(value)
        }
        return translated
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private val JAPANESE_SCRIPT = Regex("""[\u3040-\u30ff\u3400-\u9fff]""")
    private val LATIN_PHRASE = Regex("""[A-Za-z]+(?:[ .'-]+[A-Za-z]+)*""")
}

internal object CybozuChurchNameLanguageIdentifier {
    private val initialized: Unit by lazy {
        val profiles = PROFILE_NAMES.map { language ->
            requireNotNull(javaClass.getResourceAsStream("/language-profiles/$language")) {
                "Missing Cybozu short-text language profile: $language"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        DetectorFactory.loadProfile(profiles)
        DetectorFactory.setSeed(0L)
    }

    fun detect(value: String): String? {
        if (value.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' }) return "ko"
        if (value.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }) return "ja"
        if (value.none(Char::isLetter)) return null
        DETERMINISTIC_LATIN_LANGUAGES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(value) }
            ?.let { return it.second }
        return runCatching {
            initialized
            DetectorFactory.create().apply { append(value) }.detect()
        }.getOrNull()
    }

    private val PROFILE_NAMES = listOf(
        "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "gu", "he",
        "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "lv", "mk", "ml", "nl", "no", "pa", "pl", "pt",
        "ro", "ru", "si", "sq", "sv", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh-cn", "zh-tw",
    )
    private val DETERMINISTIC_LATIN_LANGUAGES = listOf(
        Regex("""\bgereja\b""", RegexOption.IGNORE_CASE) to "id",
            Regex("""\b(igreja|assembl[eé]ia|assembreia|avivamento|deus|ADOMJ|ADVM|ADCD|JMEAD|MEB)\b""", RegexOption.IGNORE_CASE) to "pt",
        Regex("""\b(iglesia|movimiento|misionero)\b""", RegexOption.IGNORE_CASE) to "es",
        Regex(
            """\b(church|churches|chapel|christian|fellowship|assembly|mission|center|centre|ministry|ministries|gospel|bible)\b""",
            RegexOption.IGNORE_CASE,
        ) to "en",
    )
}

/** Languages evidenced by the original Google Maps title, before Crossmap generates translations. */
internal object ChurchTitleLanguageDetector {
    fun detect(title: String): List<String> = buildSet {
        val hasKana = title.any { it in '\u3040'..'\u30ff' }
        val hasHan = title.any { it in '\u3400'..'\u9fff' }
        val hasHangul = title.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' }
        if (hasKana || (hasHan && !hasHangul)) add("ja")
        if (hasHangul) add("ko")

        val normalizedTitle = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "")
        val meaningfulLatin = LATIN_WORD.findAll(normalizedTitle)
            .map(MatchResult::value)
            .filterNot { it.matches(LATIN_ABBREVIATION) }
            .joinToString(" ")
        val deterministic = buildSet {
            if (Regex("""\b(gereja|jemaat)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("id")
            if (Regex(
                    """\b(igreja|assembl[eé]ia|evang[eé]lica|congrega[cç][aã]o|crist[aã]|miss[aã]o|miss[oõ]es|avivamento|comunidade|alian[cç]a|fam[ií]lia|resgate|sede|deus|fiel|ministerio|limites|bola|neve|japao|ADOMJ|JMEAD|JCOB|INSEJEC|MEB|GFCF|IFGF|COOS)\b""",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(normalizedTitle)) add("pt")
            if (Regex("""\b(iglesia|cristiana|movimiento|misionero)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("es")
            if (Regex("""(kirche\b|\bgemeinde\b)""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("de")
            if (Regex("""\b[eé]glise\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("fr")
            if (Regex("""\bchiesa\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("it")
            if (Regex("""\bsimbahan\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle)) add("tl")
            if (Regex("""\b(church|christ|christian|bible|gospel|chapel|ministry|ministries|fellowship|community|grace)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalizedTitle)) add("en")
        }
        if (deterministic.isNotEmpty()) {
            addAll(deterministic)
        } else if (meaningfulLatin.isNotBlank()) {
            CybozuChurchNameLanguageIdentifier.detect(meaningfulLatin)
                ?.substringBefore('-')
                ?.lowercase()
                ?.let { detected ->
                    if (detected in RELIABLE_STATISTICAL_LANGUAGES && !(hasKana || hasHan)) detected else "en"
                }
                ?.takeIf { it !in setOf("ja", "ko", "zh") }
                ?.let(::add)
        }
        if (isEmpty() && LATIN_WORD.containsMatchIn(normalizedTitle)) add("en")
        if (isEmpty()) CybozuChurchNameLanguageIdentifier.detect(title)?.let(::add)
    }.sorted()

    private val LATIN_WORD = Regex("""[A-Za-z]+(?:['’-][A-Za-z]+)?""")
    private val LATIN_ABBREVIATION = Regex("""[A-Z][A-Z0-9]{1,5}""")
    private val RELIABLE_STATISTICAL_LANGUAGES = setOf("en", "pt", "id", "es")
}
