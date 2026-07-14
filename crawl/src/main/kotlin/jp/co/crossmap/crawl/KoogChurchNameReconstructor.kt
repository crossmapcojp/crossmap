package jp.co.crossmap.crawl

import java.net.URI

/** Reconciles CAT translations with partial but authoritative Latin spelling evidence from church URLs. */
internal class KoogChurchNameReconstructor(
    modelName: String = "qwen3:1.7b",
    baseUrl: String,
) {
    private val agent = KoogOllamaTextAgent(
        modelName = modelName,
        baseUrl = baseUrl,
        contextLength = 4_096,
        maxOutputTokens = 256,
        timeoutMillis = 60_000,
    )

    suspend fun reconstructAll(
        churches: List<ChurchEnglishNameInput>,
        catTranslations: Map<String, String>,
    ): Map<String, String> = churches.filter(::hasMeaningfulUrlEvidence).chunked(24).flatMap { batch ->
        val input = batch.mapIndexed { index, church ->
            "${index + 1}. Japanese=${church.name} | CAT=${catTranslations.getValue(church.id)} | " +
                "Denomination=${church.denominationId.orEmpty()} | Address=${church.address} | " +
                "Required URL spelling=${authoritativeUrlProperName(church.websiteUrl)} | URL=${church.websiteUrl}"
        }.joinToString("\n")
        val response = agent.run(
            """
                /no_think
                Reconstruct each concise English church name from its CAT translation and evidence.
                Required URL spelling is an authoritative proper-name token. The final church name MUST contain that exact token, replacing a conflicting semantic translation or kana romanization.
                URL evidence is partial, so never return a raw domain or path by itself. Do not prefix the denomination ID or acronym; it is separate metadata.
                If URL words are unrelated to the church proper name, preserve the CAT translation exactly.
                Preserve each numeric prefix and output exactly one English church name per input line. No explanation.

                $input
            """.trimIndent(),
        )
        val parsed = NUMBERED_NAME.findAll(response).associate { it.groupValues[1].toInt() to it.groupValues[2].trim() }
        val singleUnnumbered = response.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("<") }
            .lastOrNull()
            ?.takeIf { batch.size == 1 && parsed.isEmpty() }
        batch.mapIndexed { index, church ->
            church.id to (parsed[index + 1] ?: singleUnnumbered ?: catTranslations.getValue(church.id))
        }
    }.toMap()

    private fun hasMeaningfulUrlEvidence(church: ChurchEnglishNameInput): Boolean {
        return authoritativeUrlProperName(church.websiteUrl) != null
    }

    private fun authoritativeUrlProperName(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        if (GENERIC_HOSTS.any { host == it || host.endsWith(".$it") }) return null
        val hostLabel = host.substringBefore('.')
        Regex("""^([a-z0-9-]{3,})-(?:ch|church)$""").matchEntire(hostLabel)?.let {
            return it.groupValues[1].replace("-", " ")
        }
        val path = uri.path.orEmpty().lowercase().split('/').filter(String::isNotBlank)
        val churchIndex = path.indexOfFirst { it == "church" || it == "chapel" }
        return path.getOrNull(churchIndex + 1)
            ?.replace(Regex("""[^a-z0-9-]+"""), "")
            ?.replace("-", " ")
            ?.takeIf { it.length >= 3 }
    }

    private companion object {
        val NUMBERED_NAME = Regex("""(?m)^\s*(\d+)\.\s+(.+?)\s*$""")
        val GENERIC_HOSTS = setOf(
            "google.com", "facebook.com", "instagram.com", "youtube.com", "x.com", "twitter.com",
        )
    }
}
