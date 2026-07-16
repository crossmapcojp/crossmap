package jp.co.crossmap.crawl

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ChurchNameComponentTranslationRequest(
    val key: String,
    val japanese: String,
    val role: ChurchNamePartRole,
    val churchName: String,
    val address: String,
    val authoritativeUrlHint: String? = null,
)

fun interface ChurchNameComponentTranslator {
    suspend fun translateAll(requests: List<ChurchNameComponentTranslationRequest>): Map<String, String>
}

data class ChurchNameComponentCacheStats(
    var hits: Int = 0,
    var translated: Int = 0,
    var batches: Int = 0,
    var errors: Int = 0,
    var timeouts: Int = 0,
    var invalidCacheEntries: Int = 0,
    var fallbackExecutions: Int = 0,
)

/** Completes only unresolved typed name spans; a whole-name translator is reserved for unparseable names. */
class ComponentCompletingChurchEnglishNameTranslator(
    private val analyzer: ChurchNameComponentAnalyzer,
    private val componentTranslator: ChurchNameComponentTranslator,
    private val fullNameFallback: ChurchEnglishNameTranslator,
    private val modelName: String,
    val stats: ChurchNameTranslationStats = ChurchNameTranslationStats(),
) : ChurchEnglishNameTranslator {
    override suspend fun translate(church: ChurchEnglishNameInput): ChurchEnglishNameGuess =
        translateAll(listOf(church)).getValue(church.id)

    override suspend fun translateAll(churches: List<ChurchEnglishNameInput>): Map<String, ChurchEnglishNameGuess> {
        val analyses = churches.associateWith(analyzer::analyze)
        val analyzable = analyses.filterValues { it != null }.mapValues { requireNotNull(it.value) }
        val requestsByChurch = analyzable.mapValues { (church, analysis) ->
            analysis.unresolvedComponents.associateWith { component -> request(church, component, analysis) }
        }
        val uniqueRequests = requestsByChurch.values.flatMap { it.values }.distinctBy { it.key }
        stats.componentLlmPartsRequested += requestsByChurch.values.sumOf { it.size }
        stats.componentLlmUniqueExecutions += uniqueRequests.size
        val translatedComponents = if (uniqueRequests.isEmpty()) emptyMap() else componentTranslator.translateAll(uniqueRequests)
        val result = linkedMapOf<String, ChurchEnglishNameGuess>()
        analyzable.forEach { (church, analysis) ->
            val translations = requestsByChurch.getValue(church).entries.associate { (component, request) ->
                component.translationKey() to requireNotNull(translatedComponents[request.key]) {
                    "Component translator returned no result for ${request.key}"
                }.requireSanitizedComponentTranslation()
            }
            val englishName = requireNotNull(analysis.compose(translations)) {
                "Could not compose English name for ${church.id} (${church.name})"
            }
            result[church.id] = ChurchEnglishNameGuess(
                englishName = englishName,
                parts = analysis.components.map { component ->
                    TranslatedChurchNamePart(
                        component.japanese,
                        component.role,
                        component.english ?: translations.getValue(component.translationKey()),
                    )
                } + TranslatedChurchNamePart(
                    analysis.congregationJapanese,
                    ChurchNamePartRole.CONGREGATION,
                    analysis.congregationEnglish,
                ),
                confidence = if (analysis.unresolvedComponents.isEmpty()) 0.99f else 0.94f,
                reasoning = if (analysis.unresolvedComponents.isEmpty()) {
                    "Deterministic component composition"
                } else {
                    "Deterministic church-name composition with LLM translation limited to unresolved typed spans"
                },
                model = if (analysis.unresolvedComponents.isEmpty()) null else modelName,
            )
            if (analysis.unresolvedComponents.isNotEmpty()) stats.llmComposedNames++
        }
        val unparseable = analyses.filterValues { it == null }.keys.toList()
        if (unparseable.isNotEmpty()) {
            stats.fullNameLlmFallbacks += unparseable.size
            result.putAll(fullNameFallback.translateAll(unparseable))
        }
        return result
    }

    private fun request(
        church: ChurchEnglishNameInput,
        component: ChurchNameComponent,
        analysis: ChurchNameAnalysis,
    ): ChurchNameComponentTranslationRequest {
        val urlHint = authoritativeUrlHint(church.websiteUrl).takeIf {
            component.japanese.length >= 2 && analysis.unresolvedComponents.size == 1
        }
        return ChurchNameComponentTranslationRequest(
            key = listOf(component.role.name, component.japanese, urlHint.orEmpty()).joinToString(":"),
            japanese = component.japanese,
            role = component.role,
            churchName = church.name,
            address = church.address,
            authoritativeUrlHint = urlHint,
        )
    }

}

/** Retries only invalid CAT component results with a second small Japanese-capable model. */
class FallbackChurchNameComponentTranslator(
    private val primary: ChurchNameComponentTranslator,
    private val fallback: ChurchNameComponentTranslator,
) : ChurchNameComponentTranslator {
    var fallbackExecutions: Int = 0
        private set

    override suspend fun translateAll(requests: List<ChurchNameComponentTranslationRequest>): Map<String, String> {
        val primaryResults = primary.translateAll(requests)
        val invalid = requests.filter { request ->
            primaryResults[request.key]?.sanitizeComponentTranslationOrNull() == null
        }
        val fallbackResults = if (invalid.isEmpty()) emptyMap() else fallback.translateAll(invalid).also {
            fallbackExecutions += invalid.size
        }
        return requests.associate { request ->
            val value = primaryResults[request.key]?.sanitizeComponentTranslationOrNull()
                ?: fallbackResults[request.key]?.sanitizeComponentTranslationOrNull()
                ?: error("No Latin translation in primary or fallback component output for ${request.key}")
            request.key to value
        }
    }
}

/** CAT prompt adapter: one batched request per component category, never a whole church name. */
class KoogChurchNameComponentTranslator(
    modelName: String,
    baseUrl: String,
) : ChurchNameComponentTranslator {
    private val translator = KoogJapaneseTextTranslator(modelName, baseUrl)

    override suspend fun translateAll(requests: List<ChurchNameComponentTranslationRequest>): Map<String, String> =
        requests.groupBy(ChurchNameComponentTranslationRequest::role).flatMap { (role, grouped) ->
            val instruction = when (role) {
                ChurchNamePartRole.GEONAME ->
                    "Romanize each Japanese place-name span into its standard concise Latin spelling. Output the name only."
                ChurchNamePartRole.CONCEPTUAL_NAME, ChurchNamePartRole.PROPER_NAME ->
                    "Phonetically transliterate each Japanese church proper-name span into concise Latin script; preserve identity instead of semantically translating it. Output the name only."
                ChurchNamePartRole.TRADITION ->
                    "Translate each Christian tradition term into its standard concise English name. Output the term only."
                ChurchNamePartRole.OTHER ->
                    "Render each Japanese church-name span as a concise Latin-script proper name. Output the name only."
                ChurchNamePartRole.CONGREGATION ->
                    "Translate each congregation type into its standard concise English term. Output the term only."
            }
            val values = grouped.map { request ->
                request.authoritativeUrlHint?.let {
                    "${request.japanese} [optional URL path evidence: $it; use only if it is a plausible spelling of this exact span]"
                }
                    ?: request.japanese
            }
            val translations = translator.translateAll(values, instruction)
            grouped.zip(translations).map { (request, translated) -> request.key to translated }
        }.toMap()
}

@Serializable
private data class ChurchNameComponentCache(
    val model: String,
    val entries: Map<String, String> = emptyMap(),
)

/** Persistent component cache; the same typed Japanese span is translated once and reused across churches. */
class CachingChurchNameComponentTranslator(
    private val delegate: ChurchNameComponentTranslator,
    private val model: String,
    private val cacheFile: Path,
    private val batchSize: Int = 128,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) : ChurchNameComponentTranslator {
    val stats = ChurchNameComponentCacheStats()

    override suspend fun translateAll(requests: List<ChurchNameComponentTranslationRequest>): Map<String, String> {
        val entries = readEntries().toMutableMap()
        val result = linkedMapOf<String, String>()
        val pending = requests.distinctBy { it.key }.filter { request ->
            entries[request.key]?.let { result[request.key] = it; stats.hits++ } == null
        }
        pending.chunked(batchSize).forEach { batch ->
            val translated = runCatching { delegate.translateAll(batch) }
                .onFailure(::recordFailure).getOrThrow()
            stats.fallbackExecutions = (delegate as? FallbackChurchNameComponentTranslator)?.fallbackExecutions ?: 0
            batch.forEach { request ->
                val value = requireNotNull(translated[request.key]) { "No component translation for ${request.key}" }
                entries[request.key] = value
                result[request.key] = value
            }
            stats.translated += batch.size
            stats.batches++
            write(entries)
        }
        return result
    }

    private fun readEntries(): Map<String, String> {
        if (!Files.isRegularFile(cacheFile)) return emptyMap()
        val cache = json.decodeFromString<ChurchNameComponentCache>(Files.readString(cacheFile))
        if (cache.model != model) return emptyMap()
        return cache.entries.mapNotNull { (key, value) ->
            value.sanitizeComponentTranslationOrNull()?.let { key to it }
                ?: run { stats.invalidCacheEntries++; null }
        }.toMap()
    }

    private fun write(entries: Map<String, String>) {
        Files.createDirectories(cacheFile.parent)
        val temporary = Files.createTempFile(cacheFile.parent, ".church-name-component-cache-", ".json")
        Files.writeString(temporary, json.encodeToString(ChurchNameComponentCache(model, entries.toSortedMap())))
        runCatching { Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun recordFailure(error: Throwable) {
        stats.errors++
        if (error.message.orEmpty().contains("timeout", true) || error::class.simpleName.orEmpty().contains("timeout", true)) {
            stats.timeouts++
        }
    }
}

private fun String.requireSanitizedComponentTranslation(): String =
    requireNotNull(sanitizeComponentTranslationOrNull()) { "No Latin translation in component output: $this" }

private fun String.sanitizeComponentTranslationOrNull(): String? {
    return lineSequence().mapNotNull { rawLine ->
        val value = rawLine.trim().trim('`', '*', ' ', '\"')
            .replace(Regex("""^(?:translation|answer|english)\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .substringBefore(',')
            .substringBefore('(')
            .trim()
        if (value.isBlank()) return@mapNotNull null
        val sanitized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""['’]"""), "")
            .replace(Regex("""[^A-Za-z0-9 .-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '-')
        val words = sanitized.lowercase().split(Regex("""\s+""")).filter(String::isNotBlank)
        sanitized.takeIf {
            it.any(Char::isLetter) && words.size <= 4 && words.none(COMPONENT_EXPLANATION_WORDS::contains)
        }
    }.lastOrNull()
}

private val COMPONENT_EXPLANATION_WORDS = setOf(
    "assistant", "hint", "japanese", "means", "note", "output", "reading", "required",
    "spelling", "standard", "the", "this", "translate", "translated", "translates", "translation",
)

private fun authoritativeUrlHint(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val pathTokens = uri.path.orEmpty().lowercase().split(Regex("""[^a-z0-9]+"""))
        .filter { it.length >= 3 && it !in COMPONENT_URL_STOP_WORDS }
    return pathTokens.lastOrNull()
}
private val COMPONENT_URL_STOP_WORDS = setOf(
    "www", "church", "chapel", "index", "html", "about", "contact", "christ", "php",
)
