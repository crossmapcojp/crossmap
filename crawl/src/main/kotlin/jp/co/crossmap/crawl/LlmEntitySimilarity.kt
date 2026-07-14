package jp.co.crossmap.crawl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout

enum class SimilarityField { ADDRESS, NAME, ENTITY }

@Serializable
data class EntitySimilarityInput(
    val leftName: String = "",
    val leftAddress: String = "",
    val leftWebsite: String = "",
    val rightName: String = "",
    val rightAddress: String = "",
    val rightWebsite: String = "",
)

@Serializable
data class SimilarityDecision(
    val score: Float,
    val reasoning: String,
    val normalizedLeft: String = "",
    val normalizedRight: String = "",
)

interface LlmEntitySimilarityMatcher {
    suspend fun determineSameAddressByLlm(address1: String, address2: String): Float
    suspend fun determineSameNameByLlm(name1: String, name2: String): Float
    suspend fun churchNameMatchesByLlm(churchName1: String, churchName2: String): Float
    suspend fun determineSameEntityByLlm(input: EntitySimilarityInput): Float
}

class KoogLlmEntitySimilarityMatcher(
    private val modelName: String = "qwen3:4b",
    baseUrl: String = "http://localhost:11434",
) : LlmEntitySimilarityMatcher {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val agent = KoogOllamaJsonAgent(
        modelName = modelName,
        baseUrl = baseUrl,
        systemPrompt = """
            日本の教会データの同一性を判定します。表記揺れ、全角半角、旧字体、郵便番号、丁目番地号、都道府県省略を考慮してください。
            ただし同じ建物に複数の教会がある可能性、同名の別教会、移転を考慮し、証拠以上に高い点を付けないでください。
            scoreは「同一である確率」を0.0から1.0で返します。回答はscore, reasoning, normalizedLeft, normalizedRightだけのJSONです。
            思考過程やMarkdownは出力しません。
        """.trimIndent(),
        maxOutputTokens = 300,
        timeoutMillis = 45_000,
    )

    override suspend fun determineSameAddressByLlm(address1: String, address2: String): Float =
        compare(SimilarityField.ADDRESS, EntitySimilarityInput(leftAddress = address1, rightAddress = address2)).score

    override suspend fun determineSameNameByLlm(name1: String, name2: String): Float =
        compare(SimilarityField.NAME, EntitySimilarityInput(leftName = name1, rightName = name2)).score

    override suspend fun churchNameMatchesByLlm(churchName1: String, churchName2: String): Float =
        determineSameNameByLlm(churchName1, churchName2)

    override suspend fun determineSameEntityByLlm(input: EntitySimilarityInput): Float =
        compare(SimilarityField.ENTITY, input).score

    suspend fun compare(field: SimilarityField, input: EntitySimilarityInput): SimilarityDecision {
        val response = agent.run("比較種別: $field\n入力JSON: ${json.encodeToString(input)}")
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start >= 0 && end > start) { "Ollama response did not contain a JSON object" }
        val parsed = json.decodeFromString<SimilarityDecision>(response.substring(start, end + 1))
        return parsed.copy(score = parsed.score.coerceIn(0f, 1f))
    }
}

object JapaneseEntityNormalizer {
    fun name(value: String): String = normalize(value)
        .replace(Regex("(?:キリスト)?教会$"), "教会")

    fun address(value: String): String = normalize(value)
        .replace(Regex("〒?\\d{3}-?\\d{4}"), "")
        .replace(Regex("(丁目|番地?|号)"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

    fun deterministicAddressScore(left: String, right: String): Float {
        val a = address(left)
        val b = address(right)
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        if (a.contains(b) || b.contains(a)) return 0.92f
        return fuzzyScore(a, b).coerceAtMost(0.90f)
    }

    fun deterministicNameScore(left: String, right: String): Float = fuzzyScore(name(left), name(right))

    fun isExactOrContainingName(left: String, right: String): Boolean {
        val normalizedLeft = name(left)
        val normalizedRight = name(right)
        return normalizedLeft.isNotBlank() && normalizedRight.isNotBlank() &&
            (normalizedLeft == normalizedRight || normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft))
    }

    fun deterministicEntityScore(
        leftName: String,
        leftAddress: String,
        rightName: String,
        rightAddress: String,
    ): Float {
        val nameScore = deterministicNameScore(leftName, rightName)
        val addressScore = deterministicAddressScore(leftAddress, rightAddress)
        if (nameScore < 0.25f || addressScore < 0.25f) return minOf(nameScore, addressScore)
        return nameScore * 0.55f + addressScore * 0.45f
    }

    private fun fuzzyScore(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val gramsLeft = ngrams(left, if (minOf(left.length, right.length) >= 4) 2 else 1)
        val gramsRight = ngrams(right, if (minOf(left.length, right.length) >= 4) 2 else 1)
        val overlap = gramsLeft.intersect(gramsRight).size
        val dice = 2f * overlap / (gramsLeft.size + gramsRight.size).coerceAtLeast(1)
        val edit = 1f - levenshtein(left, right).toFloat() / maxOf(left.length, right.length)
        return maxOf(dice, edit).coerceIn(0f, 1f)
    }

    private fun ngrams(value: String, size: Int): Set<String> =
        if (value.length <= size) setOf(value) else (0..value.length - size).mapTo(linkedSetOf()) { value.substring(it, it + size) }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[\\s　・･,，.。()（）]"), "")
}
