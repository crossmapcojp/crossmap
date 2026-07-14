package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.SocialProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

@Serializable
data class SocialLinkReport(
    val accountsProcessed: Int,
    val directLinksAccepted: Int,
    val nameLinksAccepted: Int,
    val llmLinksAccepted: Int,
    val unmatched: Int,
    val decisions: List<SocialLinkDecision>,
)

class SocialLinkPipeline(
    private val llm: LlmEntitySimilarityMatcher,
    private val llmThreshold: Float = 0.80f,
    private val modelName: String? = null,
    private val shortlistSize: Int = 3,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    init {
        require(llmThreshold in 0f..1f)
        require(shortlistSize > 0)
    }

    suspend fun run(resourcesRoot: Path, limit: Int = Int.MAX_VALUE, applyChanges: Boolean = false): SocialLinkReport {
        require(limit > 0)
        val churchesFile = resourcesRoot.resolve("catalog/churches.json")
        val accountsFile = resourcesRoot.resolve("evidence/social-accounts.json")
        require(Files.isRegularFile(churchesFile)) { "Missing $churchesFile" }
        require(Files.isRegularFile(accountsFile)) { "Missing $accountsFile; create it as [] until social crawling is enabled" }

        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(churchesFile))
        val accounts = json.decodeFromString<List<SocialAccountCandidate>>(Files.readString(accountsFile)).take(limit)
        if (accounts.isEmpty()) {
            atomicWrite(resourcesRoot.resolve("cleanup/social-decisions.json"), json.encodeToString(emptyList<SocialLinkDecision>()))
            return SocialLinkReport(0, 0, 0, 0, 0, emptyList())
        }
        val links = extractCrawledPageLinks(resourcesRoot)
        val linker = SocialAccountLinker(llm, llmThreshold)
        val decisions = mutableListOf<SocialLinkDecision>()

        for (account in accounts) {
            val directChurches = churches.filter { church ->
                links[church.id].orEmpty().values.flatten().any { sameUrl(it, account.url) }
            }
            if (directChurches.isNotEmpty()) {
                directChurches.forEach { decisions += linker.link(it, account, links[it.id].orEmpty()) }
                continue
            }

            val exact = churches.filter { JapaneseEntityNormalizer.isExactOrContainingName(it.name, account.accountName) }
            if (exact.isNotEmpty()) {
                exact.forEach { decisions += linker.link(it, account, emptyMap()) }
                continue
            }

            val candidates = churches.asSequence()
                .map { it to JapaneseEntityNormalizer.deterministicNameScore(it.name, account.accountName) }
                .filter { it.second >= 0.25f }
                .sortedByDescending { it.second }
                .take(shortlistSize)
                .map { it.first }
                .toList()
            val attempted = candidates.map { linker.link(it, account, emptyMap()) }
            val accepted = attempted.filter { it.matched }.sortedByDescending { it.score }
            val uniqueWinner = accepted.firstOrNull()?.takeIf { winner ->
                accepted.getOrNull(1)?.let { winner.score - it.score >= 0.05f } ?: true
            }
            decisions += attempted.map { decision ->
                when {
                    uniqueWinner == null && decision.matched -> decision.copy(
                        matched = false,
                        reasoning = "Multiple LLM candidates are too close; manual review required",
                    )
                    uniqueWinner != null && decision.matched && decision != uniqueWinner -> decision.copy(
                        matched = false,
                        reasoning = "A higher-confidence church candidate was selected",
                    )
                    else -> decision
                }
            }
        }

        val accepted = decisions.filter { it.matched }
        if (applyChanges && accepted.isNotEmpty()) {
            val accountsById = accounts.associateBy { it.id }
            val acceptedByChurch = accepted.groupBy { it.churchId }
            val updated = churches.map { church ->
                val additions = acceptedByChurch[church.id].orEmpty().mapNotNull { decision ->
                    accountsById[decision.socialAccountId]?.let { it to decision }
                }
                if (additions.isEmpty()) church else church.copy(
                    socialProfiles = (church.socialProfiles + additions.map { (account, _) -> account.toProfile() })
                        .distinctBy { it.platform to normalizedUrl(it.url) },
                    determinations = church.determinations + additions.map { (account, decision) ->
                        FieldDetermination(
                            field = "socialProfiles.${account.platform.name.lowercase()}",
                            value = account.url,
                            source = decision.source,
                            confidence = decision.score.toDouble(),
                            evidence = decision.evidence,
                            model = modelName.takeIf { decision.source == DeterminationSource.LLM },
                            determinedAt = Instant.now().toString(),
                        )
                    },
                    updatedAt = Instant.now().toString(),
                )
            }
            atomicWrite(churchesFile, json.encodeToString(updated))
        }
        atomicWrite(resourcesRoot.resolve("cleanup/social-decisions.json"), json.encodeToString(decisions))

        return SocialLinkReport(
            accountsProcessed = accounts.size,
            directLinksAccepted = accepted.count { it.reasoning.startsWith("Crawled church page") },
            nameLinksAccepted = accepted.count { it.source == DeterminationSource.PROGRAMMATIC && !it.reasoning.startsWith("Crawled church page") },
            llmLinksAccepted = accepted.count { it.source == DeterminationSource.LLM },
            unmatched = accounts.count { account -> accepted.none { it.socialAccountId == account.id } },
            decisions = decisions,
        )
    }

    private fun extractCrawledPageLinks(resourcesRoot: Path): Map<String, Map<String, List<String>>> {
        val manifestFile = resourcesRoot.resolve("crawl/manifest.json")
        if (!Files.isRegularFile(manifestFile)) return emptyMap()
        return json.decodeFromString<List<CrawlManifestEntry>>(Files.readString(manifestFile))
            .mapNotNull { entry ->
                val cache = resourcesRoot.resolve(entry.cachePath).normalize()
                if (!cache.startsWith(resourcesRoot.normalize()) || !Files.isRegularFile(cache)) null
                else runCatching {
                    entry to Jsoup.parse(Files.readString(cache), entry.finalUrl).select("a[href]").map { it.absUrl("href").ifBlank { it.attr("href") } }
                }.getOrNull()
            }
            .groupBy({ it.first.churchId }, { it.first.finalUrl to it.second })
            .mapValues { (_, pages) -> pages.toMap() }
    }

    private fun SocialAccountCandidate.toProfile() = SocialProfile(
        platform = platform,
        url = url,
        handle = url.substringAfterLast('/').takeIf { it.isNotBlank() },
        displayName = accountName,
        description = description.takeIf { it.isNotBlank() },
        discoveredAt = Instant.now().toString(),
    )

    private fun sameUrl(left: String, right: String) = normalizedUrl(left) == normalizedUrl(right)

    private fun normalizedUrl(value: String): String = value.trim().lowercase()
        .substringBefore('#').substringBefore('?').removeSuffix("/")
        .replace("http://", "https://")

    private fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}
