package jp.co.crossmap.crawl

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.withTimeout

/** Bounded plain-text Koog client for local translation models. */
internal class KoogOllamaTextAgent(
    modelName: String,
    baseUrl: String,
    contextLength: Long = 4_096,
    maxOutputTokens: Int,
    private val timeoutMillis: Long,
) {
    private val model = LLModel(
        provider = LLMProvider.Ollama,
        id = modelName,
        capabilities = listOf(LLMCapability.Temperature),
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens.toLong(),
    )
    private val executor = MultiLLMPromptExecutor(mapOf(LLMProvider.Ollama to OllamaClient(baseUrl)))
    private val params = OllamaParams(temperature = 0.0, maxTokens = maxOutputTokens, think = false)

    suspend fun run(input: String): String = withTimeout(timeoutMillis) {
        executor.execute(
            prompt = prompt("crossmap-text-translation", params = params) { user(input) },
            model = model,
        ).parts.filterIsInstance<MessagePart.Text>().joinToString(separator = "") { it.text }
    }
}
