package jp.co.crossmap.crawl

import java.text.Normalizer
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
enum class ChurchNamePartRole {
    GEONAME,
    TRADITION,
    CONGREGATION,
    CONCEPTUAL_NAME,
    PROPER_NAME,
    OTHER,
}

@Serializable
data class TranslatedChurchNamePart(
    val japanese: String,
    val role: ChurchNamePartRole,
    val english: String,
)

@Serializable
data class ChurchEnglishNameGuess(
    val englishName: String,
    val parts: List<TranslatedChurchNamePart> = emptyList(),
    val confidence: Float,
    val reasoning: String = "",
    val model: String? = null,
)

data class ProgrammaticEnglishName(
    val englishName: String,
    val confidence: Float,
    val evidence: String,
)

data class ResolvedChurchEnglishName(
    val englishName: String,
    val source: DeterminationSource,
    val confidence: Float,
    val evidence: List<String>,
    val model: String? = null,
    val parts: List<TranslatedChurchNamePart> = emptyList(),
)

/** Naming input used before a candidate is valid enough to become a canonical [ChurchRecord]. */
data class ChurchEnglishNameInput(
    val id: String,
    val name: String,
    val existingEnglishName: String? = null,
    val denominationId: String? = null,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val pages: List<CrawledPage> = emptyList(),
    val socialProfiles: List<SocialProfile> = emptyList(),
)

fun ChurchRecord.toEnglishNameInput() = ChurchEnglishNameInput(
    id = id,
    name = name,
    existingEnglishName = englishName,
    denominationId = denominationId,
    address = address,
    location = location,
    websiteUrl = websiteUrl,
    pages = pages,
    socialProfiles = socialProfiles,
)

fun interface ChurchEnglishNameTranslator {
    suspend fun translate(church: ChurchEnglishNameInput): ChurchEnglishNameGuess

    suspend fun translateAll(churches: List<ChurchEnglishNameInput>): Map<String, ChurchEnglishNameGuess> =
        churches.associate { it.id to translate(it) }
}

class ChurchEnglishNameResolver(
    private val translationRules: List<ChurchNameEnglishTranslationRule> = ChurchNameEnglishTranslationRules.create(),
    private val translator: ChurchEnglishNameTranslator,
) {
    suspend fun findOutChurchEnglishName(church: ChurchRecord): String =
        resolve(church.toEnglishNameInput()).englishName

    suspend fun findOutChurchEnglishName(church: ChurchEnglishNameInput): String =
        resolve(church).englishName

    suspend fun resolve(church: ChurchRecord): ResolvedChurchEnglishName = resolve(church.toEnglishNameInput())

    suspend fun resolve(church: ChurchEnglishNameInput): ResolvedChurchEnglishName {
        determineProgrammatically(church)?.let {
            return ResolvedChurchEnglishName(
                englishName = it.englishName,
                source = DeterminationSource.PROGRAMMATIC,
                confidence = it.confidence,
                evidence = listOf(it.evidence),
            )
        }
        val guess = translator.translate(church)
        return ResolvedChurchEnglishName(
            englishName = guess.englishName.requireUsableEnglishChurchName(),
            source = DeterminationSource.LLM,
            confidence = guess.confidence.coerceIn(0f, 1f),
            evidence = listOf(guess.reasoning).filter(String::isNotBlank),
            model = guess.model,
            parts = guess.parts,
        )
    }

    suspend fun resolveAll(churches: List<ChurchRecord>): Map<String, ResolvedChurchEnglishName> {
        return resolveInputs(churches.map(ChurchRecord::toEnglishNameInput))
    }

    suspend fun resolveInputs(churches: List<ChurchEnglishNameInput>): Map<String, ResolvedChurchEnglishName> {
        val programmatic = churches.mapNotNull { church ->
            determineProgrammatically(church)?.let { church.id to it }
        }.toMap()
        val unresolved = churches.filterNot { it.id in programmatic }
        val guesses = translator.translateAll(unresolved)
        return churches.associate { church ->
            val deterministic = programmatic[church.id]
            church.id to if (deterministic != null) {
                ResolvedChurchEnglishName(
                    englishName = deterministic.englishName,
                    source = DeterminationSource.PROGRAMMATIC,
                    confidence = deterministic.confidence,
                    evidence = listOf(deterministic.evidence),
                )
            } else {
                val guess = requireNotNull(guesses[church.id]) { "Translator returned no English name for ${church.id}" }
                runCatching {
                    ResolvedChurchEnglishName(
                        englishName = guess.englishName.requireUsableEnglishChurchName(),
                        source = DeterminationSource.LLM,
                        confidence = guess.confidence.coerceIn(0f, 1f),
                        evidence = listOf(guess.reasoning).filter(String::isNotBlank),
                        model = guess.model,
                        parts = guess.parts,
                    )
                }.getOrElse { error ->
                    throw IllegalArgumentException("${church.id} (${church.name}): ${error.message}", error)
                }
            }
        }
    }

    suspend fun findSplitAndTranslateChurchNameToEnglishByLlm(church: ChurchRecord): String =
        translator.translate(church.toEnglishNameInput()).englishName.requireUsableEnglishChurchName()

    suspend fun findSplitAndTranslateChurchNameToEnglishByLlm(church: ChurchEnglishNameInput): String =
        translator.translate(church).englishName.requireUsableEnglishChurchName()

    fun determineProgrammatically(church: ChurchRecord): ProgrammaticEnglishName? =
        determineProgrammatically(church.toEnglishNameInput())

    fun determineProgrammatically(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        church.existingEnglishName?.trim()?.takeIf(::isUsableEnglishChurchName)?.let {
            return ProgrammaticEnglishName(it, 1f, "ChurchRecord.englishName")
        }

        sanitizeLatinScriptName(church.name)?.let {
            return ProgrammaticEnglishName(it, 0.99f, "Latin-script church name")
        }

        val pageEvidence = church.pages.asSequence().flatMap { page ->
            sequenceOf(page.title, page.text)
        }
        findEnglishChurchName(pageEvidence)?.let {
            return ProgrammaticEnglishName(it, 0.99f, "Crawled church webpage")
        }
        findLatinChurchTitle(church.pages.asSequence().map(CrawledPage::title))?.let {
            return ProgrammaticEnglishName(it, 0.99f, "Crawled church webpage Latin title")
        }
        if (church.name in GENERIC_JAPANESE_CHURCH_NAMES) {
            church.pages.asSequence().map(CrawledPage::title).mapNotNull(::findJapaneseChurchName).forEach { pageName ->
                translationRules.firstNotNullOfOrNull { it.translate(church.copy(name = pageName)) }?.let {
                    return ProgrammaticEnglishName(
                        it.englishName,
                        minOf(it.confidence, 0.99f),
                        "Crawled church webpage Japanese title; ${it.evidence}",
                    )
                }
            }
        }

        val socialEvidence = church.socialProfiles.asSequence().flatMap { profile ->
            sequenceOf(profile.displayName.orEmpty(), profile.description.orEmpty(), profile.handle.orEmpty())
        }
        findEnglishChurchName(socialEvidence)?.let {
            return ProgrammaticEnglishName(it, 0.98f, "Linked social account")
        }

        translationRules.firstNotNullOfOrNull { it.translate(church) }?.let { return it }

        return null
    }

    private fun findEnglishChurchName(values: Sequence<String>): String? = values
        .flatMap { value -> ENGLISH_CHURCH_NAME.findAll(value).map { it.value.normalizeEnglishName() } }
        .filter(::isUsableEnglishChurchName)
        .minWithOrNull(compareBy<String> { it.split(' ').size }.thenBy { it.length })

    private fun findJapaneseChurchName(value: String): String? = JAPANESE_CHURCH_NAME.findAll(value)
        .map(MatchResult::value)
        .filterNot(GENERIC_JAPANESE_CHURCH_NAMES::contains)
        .minByOrNull(String::length)

    private fun findLatinChurchTitle(values: Sequence<String>): String? = values.flatMap { title ->
        title.split(Regex("""\s+[-–—|]\s+""")).asSequence()
    }.map(String::trim).filter { candidate ->
        !JAPANESE_SCRIPT.containsMatchIn(candidate) &&
            LATIN_CONGREGATION_WORD.containsMatchIn(candidate) &&
            !URL_LIKE.containsMatchIn(candidate)
    }.minByOrNull(String::length)?.normalizeEnglishName()

    private fun sanitizeLatinScriptName(value: String): String? {
        if (JAPANESE_SCRIPT.containsMatchIn(value) || value.none(Char::isLetter) || URL_LIKE.containsMatchIn(value)) {
            return null
        }
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""['’]"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^A-Za-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return ascii.takeIf(String::isNotBlank)
    }

    private fun String.requireUsableEnglishChurchName(): String {
        val normalized = normalizeEnglishName()
        require(isUsableEnglishChurchName(normalized)) { "LLM did not return a usable English church name: $this" }
        return normalized
    }

    companion object {
        private val CONGREGATION_WORDS = setOf(
            "Church", "Chapel", "Cathedral", "Fellowship", "Congregation", "Parish", "Mission", "Assembly",
            "Center", "House", "Hall", "Ministry", "Ecclesia", "School", "Academy", "Seminary", "Institute",
        )
        private val ENGLISH_CHURCH_NAME = Regex(
            """(?<![A-Za-z])(?:[A-Z][A-Za-z0-9'’.-]*|St\.)(?:\s+(?:[A-Z][A-Za-z0-9'’.-]*|of|the|in|at|and|International)){0,10}\s+(?:Church|Chapel|Cathedral|Fellowship|Congregation|Parish|Mission|Assembly)\b""",
        )
        private val JAPANESE_SCRIPT = Regex("""[\u3040-\u30ff\u3400-\u9fff]""")
        private val JAPANESE_CHURCH_NAME = Regex(
            """[\u3040-\u30ff\u3400-\u9fff・ー]{2,}(?:教会|聖堂|チャペル)""",
        )
        private val LATIN_CONGREGATION_WORD = Regex(
            """\b(?:Church|Chapel|Cathedral|Fellowship|Mission|Assembly|Iglesia|Igreja)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val URL_LIKE = Regex("""(?:https?://|www\.|\.(?:com|org|net|jp)(?:/|$))""", RegexOption.IGNORE_CASE)
        private val GENERIC_JAPANESE_CHURCH_NAMES = setOf("教会", "キリスト教会", "チャペル", "聖堂")

        private fun String.normalizeEnglishName(): String = trim()
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':', '·')

internal fun isUsableEnglishChurchName(value: String): Boolean {
            val normalized = value.normalizeEnglishName()
            return normalized.count(Char::isLetter) >= 3 &&
                normalized.all { it.code < 128 || it == '’' } &&
                !URL_LIKE.containsMatchIn(normalized) &&
                normalized.lowercase() !in setOf("church", "chapel", "mission", "assembly")
        }
    }
}

class KoogChurchEnglishNameTranslator(
    private val modelName: String = CAT_TRANSLATE_MODEL,
    baseUrl: String = "http://localhost:11434",
    reconstructionModelName: String = "qwen3:1.7b",
) : ChurchEnglishNameTranslator {
    private val translator = KoogJapaneseTextTranslator(modelName, baseUrl)
    private val reconstructor = KoogChurchNameReconstructor(reconstructionModelName, baseUrl)
    private val malformedOutputFallback = KoogOllamaTextAgent(
        modelName = reconstructionModelName,
        baseUrl = baseUrl,
        contextLength = 2_048,
        maxOutputTokens = 128,
        timeoutMillis = 60_000,
    )

    override suspend fun translate(church: ChurchEnglishNameInput): ChurchEnglishNameGuess {
        val catTranslation = translator.translate(church.name)
        val reconstructed = reconstructor.reconstructAll(listOf(church), mapOf(church.id to catTranslation))[church.id]
            ?: catTranslation
        return guessOrRetry(church, reconstructed)
    }

    override suspend fun translateAll(churches: List<ChurchEnglishNameInput>): Map<String, ChurchEnglishNameGuess> {
        val catTranslations = churches.zip(translator.translateAll(churches.map(ChurchEnglishNameInput::name)))
            .associate { (church, englishName) -> church.id to englishName }
        val reconstructed = reconstructor.reconstructAll(churches, catTranslations)
        val results = linkedMapOf<String, ChurchEnglishNameGuess>()
        churches.forEach { church ->
            val englishName = reconstructed[church.id] ?: catTranslations.getValue(church.id)
            results[church.id] = guessOrRetry(church, englishName)
        }
        return results
    }

    private suspend fun guessOrRetry(
        church: ChurchEnglishNameInput,
        initialTranslation: String,
    ): ChurchEnglishNameGuess {
        runCatching { guess(church, initialTranslation) }.getOrNull()?.let { return it }
        var lastOutput = initialTranslation
        repeat(3) {
            lastOutput = malformedOutputFallback.run(
                """
                    /no_think
                    Return exactly one concise English name for this Japanese church. Output the name only.
                    It must use Latin script and end with Church, Chapel, Cathedral, Fellowship, Congregation, Parish, Mission, or Assembly.
                    Preserve authoritative proper-name spelling suggested by the website URL, but do not output a raw domain or URL.
                    Japanese name: ${church.name}
                    Address: ${church.address}
                    Website URL: ${church.websiteUrl}
                    Malformed previous translation: $lastOutput
                """.trimIndent(),
            ).lineSequence().map(String::trim).filter(String::isNotBlank).last()
                .removeSurrounding("\"")
            runCatching { guess(church, lastOutput) }.getOrNull()?.let { return it }
        }
        error("LLM could not produce a usable English name for ${church.id} (${church.name}); last output=$lastOutput")
    }

    private fun guess(church: ChurchEnglishNameInput, englishName: String): ChurchEnglishNameGuess {
        val forcedEnglishName = forceUsableEnglishName(church.name, englishName)
        return ChurchEnglishNameGuess(
            englishName = forcedEnglishName,
            parts = splitChurchNameParts(church.name, forcedEnglishName),
            confidence = 0.90f,
            reasoning = "CAT translation of the Japanese church name; normalized to the mandatory publication name contract",
            model = modelName,
        )
    }

    private fun forceUsableEnglishName(japaneseName: String, translated: String): String {
        val initial = translated.substringBefore(',').substringBefore(" - ")
            .substringBefore(" (not ").substringBefore(" not ").trim()
        val concise = Regex("""(?i)^.+\s+Church\s+of\s+(St\.?\s+.+)$""").matchEntire(initial)
            ?.groupValues?.get(1)?.let { "$it Church" }
            ?: initial
        val ascii = Normalizer.normalize(concise, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""['’]"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^A-Za-z0-9 .-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '-')
        require(ascii.any(Char::isLetter)) { "CAT returned no Latin-script name for $japaneseName: $translated" }
        val hasCongregationWord = Regex(
            """(?i)\b(?:Church|Chapel|Cathedral|Fellowship|Congregation|Parish|Mission|Assembly)\b""",
        ).containsMatchIn(ascii)
        if (hasCongregationWord) return ascii
        val suffix = when {
            "チャペル" in japaneseName -> "Chapel"
            "大聖堂" in japaneseName -> "Cathedral"
            "集会" in japaneseName -> "Assembly"
            "宣教" in japaneseName || "ミッション" in japaneseName -> "Mission"
            else -> "Church"
        }
        return "$ascii $suffix"
    }

}

private fun splitChurchNameParts(japaneseName: String, englishName: String): List<TranslatedChurchNamePart> {
    val markers = listOf(
        "バプテスト" to (ChurchNamePartRole.TRADITION to "Baptist"),
        "長老" to (ChurchNamePartRole.TRADITION to "Presbyterian"),
        "聖公会" to (ChurchNamePartRole.TRADITION to "Anglican"),
        "ルーテル" to (ChurchNamePartRole.TRADITION to "Lutheran"),
        "カトリック" to (ChurchNamePartRole.TRADITION to "Catholic"),
        "福音" to (ChurchNamePartRole.TRADITION to "Gospel"),
        "キリスト" to (ChurchNamePartRole.TRADITION to "Christian"),
        "チャペル" to (ChurchNamePartRole.CONGREGATION to "Chapel"),
        "大聖堂" to (ChurchNamePartRole.CONGREGATION to "Cathedral"),
        "教会" to (ChurchNamePartRole.CONGREGATION to "Church"),
    )
    val found = markers.mapNotNull { (japanese, taggedEnglish) ->
        japanese.takeIf(japaneseName::contains)?.let {
            TranslatedChurchNamePart(it, taggedEnglish.first, taggedEnglish.second)
        }
    }
    val remainder = markers.fold(japaneseName) { value, marker -> value.replace(marker.first, "") }.trim()
    return buildList {
        if (remainder.isNotBlank()) {
            val englishRemainder = found.fold(englishName) { value, part ->
                value.replace(Regex("""(?i)\b${Regex.escape(part.english)}\b"""), "")
            }.replace(Regex("""\s+"""), " ").trim()
            val role = if (remainder.endsWith("都") || remainder.endsWith("道") || remainder.endsWith("府") ||
                remainder.endsWith("県") || remainder.endsWith("市") || remainder.endsWith("区")
            ) ChurchNamePartRole.GEONAME else ChurchNamePartRole.PROPER_NAME
            add(TranslatedChurchNamePart(remainder, role, englishRemainder))
        }
        addAll(found)
    }
}

const val CAT_TRANSLATE_MODEL = "cat-translate:7b-q4_k_m"

fun findOutChurchEnglishName(church: ChurchRecord): String = runBlocking {
    ChurchEnglishNameResolver(translator = KoogChurchEnglishNameTranslator()).findOutChurchEnglishName(church)
}

fun findSplitAndTranslateChurchNameToEnglishbyLlmz(church: ChurchRecord): String = runBlocking {
    ChurchEnglishNameResolver(translator = KoogChurchEnglishNameTranslator())
        .findSplitAndTranslateChurchNameToEnglishByLlm(church)
}
