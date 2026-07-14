package jp.co.crossmap.crawl

/** Batch-aware Japanese-to-English translation over Koog's Ollama prompt executor. */
internal class KoogJapaneseTextTranslator(
    modelName: String,
    baseUrl: String,
) {
    private val agent = KoogOllamaTextAgent(
        modelName = modelName,
        baseUrl = baseUrl,
        contextLength = 2_048,
        maxOutputTokens = 1_024,
        timeoutMillis = 45_000,
    )

    suspend fun translate(value: String, instruction: String = DEFAULT_INSTRUCTION): String = clean(
        agent.run("$instruction\n\n$value"),
    )

    suspend fun translateAll(
        values: List<String>,
        instruction: String = DEFAULT_INSTRUCTION,
    ): List<String> = values.chunked(32).flatMap { batch ->
        val numbered = batch.mapIndexed { index, value -> "${index + 1}. $value" }.joinToString("\n")
        val prompt = """
            $instruction
            Preserve each numeric prefix and output exactly one translated line per input line.

            $numbered
        """.trimIndent()
        val parsed = NUMBERED_TRANSLATION.findAll(agent.run(prompt)).associate { match ->
            match.groupValues[1].toInt() to clean(match.groupValues[2])
        }
        batch.mapIndexed { index, value -> parsed[index + 1] ?: translate(value, instruction) }
    }

    private fun clean(value: String): String = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .last()
        .removeSurrounding("\"")
        .trim()

    private companion object {
        const val DEFAULT_INSTRUCTION = "Translate the following Japanese text into English."
        val NUMBERED_TRANSLATION = Regex("""(?m)^\s*(\d+)\.\s+(.+?)\s*$""")
    }
}
