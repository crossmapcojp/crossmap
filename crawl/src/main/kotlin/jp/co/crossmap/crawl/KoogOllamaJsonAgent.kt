package jp.co.crossmap.crawl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.withTimeout

/** Shared non-thinking, bounded-output Koog client for local classification tasks. */
internal class KoogOllamaJsonAgent(
    modelName: String,
    baseUrl: String,
    systemPrompt: String,
    maxOutputTokens: Int,
    private val timeoutMillis: Long,
) {
    private val model = LLModel(
        provider = LLMProvider.Ollama,
        id = modelName,
        capabilities = listOf(LLMCapability.Temperature, LLMCapability.Schema.JSON.Basic),
        contextLength = 8_192,
        maxOutputTokens = maxOutputTokens.toLong(),
    )
    private val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(mapOf(LLMProvider.Ollama to OllamaClient(baseUrl))),
        agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "crossmap-json-classifier",
                params = OllamaParams(temperature = 0.0, maxTokens = maxOutputTokens, think = false),
            ) { system(systemPrompt) },
            model = model,
            maxAgentIterations = 2,
        ),
    )

    suspend fun run(input: String): String = withTimeout(timeoutMillis) { agent.run(input) }
}
