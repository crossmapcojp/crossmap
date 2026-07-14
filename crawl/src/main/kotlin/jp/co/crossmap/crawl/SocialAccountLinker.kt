package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.SocialPlatform
import kotlinx.serialization.Serializable

@Serializable
data class SocialAccountCandidate(
    val id: String,
    val platform: SocialPlatform,
    val url: String,
    val accountName: String,
    val description: String = "",
    val sourceUrl: String = "",
)

@Serializable
data class SocialLinkDecision(
    val churchId: String,
    val socialAccountId: String,
    val matched: Boolean,
    val score: Float,
    val source: DeterminationSource,
    val reasoning: String,
    val evidence: List<String> = emptyList(),
)

class SocialAccountLinker(
    private val llm: LlmEntitySimilarityMatcher,
    private val llmThreshold: Float = 0.80f,
) {
    init {
        require(llmThreshold in 0f..1f)
    }

    suspend fun link(
        church: ChurchRecord,
        account: SocialAccountCandidate,
        crawledPageLinks: Map<String, List<String>>,
    ): SocialLinkDecision {
        val normalizedAccountUrl = normalizeUrl(account.url)
        val linkingPages = crawledPageLinks.filterValues { links -> links.any { normalizeUrl(it) == normalizedAccountUrl } }.keys
        if (linkingPages.isNotEmpty()) {
            return SocialLinkDecision(
                church.id, account.id, true, 1f, DeterminationSource.PROGRAMMATIC,
                "Crawled church page links directly to the social account", linkingPages.toList(),
            )
        }

        if (JapaneseEntityNormalizer.isExactOrContainingName(church.name, account.accountName)) {
            return SocialLinkDecision(
                church.id, account.id, true, 1f, DeterminationSource.PROGRAMMATIC,
                "Normalized Google place name and social account name are equal or one contains the other",
                listOf(church.name, account.accountName),
            )
        }

        val deterministicName = JapaneseEntityNormalizer.deterministicNameScore(church.name, account.accountName)
        if (deterministicName < 0.25f) {
            return SocialLinkDecision(
                church.id, account.id, false, deterministicName, DeterminationSource.PROGRAMMATIC,
                "Names have too little character overlap for LLM fallback",
            )
        }

        val llmScore = llm.churchNameMatchesByLlm(church.name, account.accountName).coerceIn(0f, 1f)
        return SocialLinkDecision(
            church.id,
            account.id,
            llmScore >= llmThreshold,
            llmScore,
            DeterminationSource.LLM,
            if (llmScore >= llmThreshold) "LLM matched ambiguous church/social names" else "LLM score below social-link threshold",
            listOf(church.name, account.accountName, account.url),
        )
    }

    private fun normalizeUrl(value: String): String = value.trim().lowercase()
        .substringBefore('#').substringBefore('?').removeSuffix("/")
        .replace("http://", "https://")
}
