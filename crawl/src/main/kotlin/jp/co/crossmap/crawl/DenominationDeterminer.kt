package jp.co.crossmap.crawl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

@Serializable
data class Denomination(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val officialWebsite: String = "",
    val proposed: Boolean = false,
) {
    companion object {
        val NOT_DETERMINED = Denomination(jp.co.crossmap.crawl.NOT_DETERMINED, "未判定")
    }
}

private val latinDenominationAbbreviation = Regex("""[A-Z][A-Z0-9]{1,12}""")

/** Latin denomination identifiers and aliases that may occur verbatim in Google place titles. */
internal fun Iterable<Denomination>.knownLatinAbbreviations(): Set<String> = flatMap { denomination ->
    (listOf(denomination.id) + denomination.aliases)
        .map(String::uppercase)
        .filter(latinDenominationAbbreviation::matches)
}.toSet()

@Serializable
data class DenominationGuessResult(
    val denomination: Denomination,
    val score: Float,
    val reasoning: String = "",
    val model: String? = null,
)

interface DenominationGuesser {
    suspend fun determineDenominationByLlm(text: String): DenominationGuessResult
}

class ProgrammaticDenominationDeterminer(
    private val rules: List<DenominationRule>,
) {
    fun determineDenominationProgrammatically(text: String): DenominationGuessResult {
        val normalized = JapaneseEntityNormalizer.name(text)
        val matches = rules.mapNotNull { rule ->
            if (rule.churchNameExcludes.any { normalized.contains(JapaneseEntityNormalizer.name(it)) }) return@mapNotNull null
            val evidence = (listOf(rule.name) + rule.churchNameComponents + rule.officialChurchNames + rule.websiteComponents)
                .filter { it.isNotBlank() && normalized.contains(JapaneseEntityNormalizer.name(it)) }
            if (evidence.isEmpty()) null else rule to evidence.maxOf { JapaneseEntityNormalizer.name(it).length }
        }.sortedByDescending { it.second }
        val best = matches.firstOrNull() ?: return DenominationGuessResult(Denomination.NOT_DETERMINED, 0f, "No deterministic rule matched")
        if (matches.getOrNull(1)?.second == best.second && matches[1].first.denominationId != best.first.denominationId) {
            return DenominationGuessResult(Denomination.NOT_DETERMINED, 0.5f, "Multiple denomination rules matched equally")
        }
        val rule = best.first
        return DenominationGuessResult(
            Denomination(rule.denominationId, rule.name, rule.churchNameComponents),
            if (best.second >= 6) 0.95f else 0.85f,
            "Matched Crossmap denomination rule from ${rule.source}",
        )
    }
}

class KoogDenominationGuesser(
    private val catalog: List<Denomination>,
    private val modelName: String = "qwen3:4b",
    baseUrl: String = "http://localhost:11434",
) : DenominationGuesser {
    @Serializable
    private data class WireResult(
        val denominationId: String? = null,
        val denominationName: String? = null,
        val score: Float,
        val reasoning: String,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val agent = KoogOllamaJsonAgent(
        modelName = modelName,
        baseUrl = baseUrl,
        systemPrompt = """
            日本のキリスト教会の名称、ウェブ本文、住所から所属教派を判定してください。
            catalogにある場合はdenominationIdを厳密に使用します。catalogにない明示的な教派はdenominationNameに正式名を提案します。
            根拠がなければ両方null、scoreは0.5未満にします。JSONのdenominationId, denominationName, score, reasoningだけを返します。
        """.trimIndent(),
        maxOutputTokens = 300,
        timeoutMillis = 45_000,
    )

    override suspend fun determineDenominationByLlm(text: String): DenominationGuessResult {
        val visibleText = if (text.contains('<') && text.contains('>')) {
            Jsoup.parse(text).apply { select("script,style,noscript,template").remove() }.text()
        } else text
        val results = chunks(visibleText).mapIndexed { index, chunk -> guessChunk(chunk, index) }
        val determined = results.filter { it.denomination.id != NOT_DETERMINED }
        if (determined.isEmpty()) return results.maxByOrNull { it.score }
            ?: DenominationGuessResult(Denomination.NOT_DETERMINED, 0f, "Empty webpage", modelName)
        val grouped = determined.groupBy { it.denomination.id }
        val ordered = grouped.entries.sortedByDescending { (_, guesses) -> guesses.maxOf { it.score } }
        val best = ordered.first()
        val competing = ordered.getOrNull(1)
        if (competing != null && competing.value.maxOf { it.score } >= best.value.maxOf { it.score } - 0.05f) {
            return DenominationGuessResult(Denomination.NOT_DETERMINED, 0.5f, "Conflicting denomination evidence across webpage chunks", modelName)
        }
        return best.value.maxBy { it.score }
    }

    private suspend fun guessChunk(text: String, index: Int): DenominationGuessResult {
        val prompt = "catalog=${json.encodeToString(catalog)}\nwebpageChunk=$index\ntext=$text"
        val response = agent.run(prompt)
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start >= 0 && end > start) { "Ollama response did not contain a JSON object" }
        val wire = json.decodeFromString<WireResult>(response.substring(start, end + 1))
        val known = catalog.firstOrNull { it.id == wire.denominationId }
        val denomination = known ?: wire.denominationName?.takeIf(String::isNotBlank)?.let { proposedName ->
            Denomination(
                id = "proposed:${proposedName.toByteArray().sha256().take(16)}",
                name = proposedName,
                proposed = true,
            )
        } ?: Denomination.NOT_DETERMINED
        return DenominationGuessResult(denomination, wire.score.coerceIn(0f, 1f), "chunk $index: ${wire.reasoning}", modelName)
    }

    private fun chunks(text: String, size: Int = 6_000, overlap: Int = 500): List<String> {
        if (text.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length && result.size < 32) {
            val end = minOf(text.length, start + size)
            result += text.substring(start, end)
            if (end == text.length) break
            start = end - overlap
        }
        return result
    }
}

fun List<DenominationRule>.toDenominationCatalog(): List<Denomination> = map {
    Denomination(it.denominationId, it.name, it.churchNameComponents, it.websiteComponents.firstOrNull().orEmpty())
}.distinctBy { it.id }.sortedBy { it.id }
