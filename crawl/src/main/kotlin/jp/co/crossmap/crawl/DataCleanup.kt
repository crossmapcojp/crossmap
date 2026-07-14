package jp.co.crossmap.crawl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout

const val NOT_DETERMINED = "NOT_DETERMINED"

@Serializable
data class DenominationCandidate(
    val denominationId: String,
    val churchName: String,
    val address: String = "",
    val url: String = "",
    val source: String,
    val nameSimilarity: Float? = null,
    val addressSimilarity: Float? = null,
    val entitySimilarity: Float? = null,
)

@Serializable
data class DenominationRule(
    val denominationId: String,
    val name: String,
    val churchNameComponents: List<String> = emptyList(),
    val churchNameExcludes: List<String> = emptyList(),
    val websiteComponents: List<String> = emptyList(),
    val officialChurchNames: List<String> = emptyList(),
    val source: String = "",
)

@Serializable
data class HumanOverride(
    val churchId: String,
    val field: String = "denominationId",
    val value: String,
    val note: String = "",
    val reviewedAt: String = "",
)

@Serializable
data class EntityMatchInput(
    val churchId: String,
    val churchName: String,
    val address: String,
    val websiteUrl: String,
    val websiteText: String,
    val allowedDenominationIds: List<String>,
    val officialCandidates: List<DenominationCandidate>,
)

@Serializable
data class EntityMatchDecision(
    val denominationId: String? = null,
    val confidence: Double,
    val sameEntityCandidateSource: String? = null,
    val reasoning: String,
)

@Serializable
data class CleanupAuditEntry(
    val churchId: String,
    val previousValue: String,
    val proposedValue: String? = null,
    val confidence: Double = 0.0,
    val accepted: Boolean,
    val source: DeterminationSource,
    val evidence: List<String> = emptyList(),
    val model: String? = null,
    val reasoning: String = "",
    val determinedAt: String,
)

data class CleanupReport(
    val total: Int,
    val notDeterminedBefore: Int,
    val notDeterminedAfter: Int,
    val programmaticAccepted: Int,
    val llmAccepted: Int,
    val uncertain: Int,
    val humanOverrides: Int,
    val errors: Int,
)

data class ProgrammaticDecision(
    val denominationId: String,
    val confidence: Double,
    val evidence: List<String>,
    val reasoning: String,
)

class ProgrammaticDenominationMatcher {
    fun match(
        church: ChurchRecord,
        rules: List<DenominationRule>,
        candidates: List<DenominationCandidate>,
    ): ProgrammaticDecision? {
        val normalizedName = normalize(church.name)
        val normalizedAddress = normalize(church.address)
        uniqueRuleMatch(church.name, rules) { listOf(it.name) + it.churchNameComponents }?.let { (rule, term) ->
            return ProgrammaticDecision(rule.denominationId, 0.95, listOf(rule.source, term), "Church name contains a unique denomination name or alias")
        }

        church.pages.sortedBy(::pagePriority).forEach { page ->
            uniqueRuleMatch("${page.title}\n${page.text}", rules) { listOf(it.name) + it.churchNameComponents }?.let { (rule, term) ->
                return ProgrammaticDecision(rule.denominationId, 0.93, listOf(rule.source, page.url, term), "Crawled home/about page contains a unique denomination name or alias")
            }
        }

        val exactCandidates = candidates.filter { normalize(it.churchName) == normalizedName }
        if (exactCandidates.map { it.denominationId }.distinct().size == 1) {
            val candidate = exactCandidates.first()
            val addressMatches = candidate.address.isNotBlank() &&
                (normalizedAddress.contains(normalize(candidate.address)) || normalize(candidate.address).contains(normalizedAddress))
            return ProgrammaticDecision(
                candidate.denominationId,
                if (addressMatches) 0.99 else 0.95,
                listOf(candidate.source, candidate.url).filter { it.isNotBlank() },
                if (addressMatches) "Exact official-list name and address match" else "Unique exact official-list name match",
            )
        }
        val fuzzyCandidates = candidates.filter {
            (it.nameSimilarity ?: 0f) >= 0.82f && (it.addressSimilarity ?: 0f) >= 0.82f
        }
        if (fuzzyCandidates.isNotEmpty() && fuzzyCandidates.map { it.denominationId }.distinct().size == 1) {
            val candidate = fuzzyCandidates.maxBy { it.entitySimilarity ?: 0f }
            return ProgrammaticDecision(
                candidate.denominationId,
                minOf(0.94, (candidate.entitySimilarity ?: 0f).toDouble()),
                listOf(candidate.source, candidate.url).filter { it.isNotBlank() },
                "Official-directory church name and address are both strong fuzzy matches",
            )
        }

        uniqueRuleMatch(church.websiteUrl, rules) { it.websiteComponents }?.let { (rule, component) ->
            return ProgrammaticDecision(rule.denominationId, 0.92, listOf(rule.source, component), "Church website URL matches a unique denomination domain rule")
        }
        return null
    }

    private fun uniqueRuleMatch(
        text: String,
        rules: List<DenominationRule>,
        terms: (DenominationRule) -> List<String>,
    ): Pair<DenominationRule, String>? {
        val normalized = normalize(text)
        val matches = rules.mapNotNull { rule ->
            if (rule.churchNameExcludes.any { normalized.contains(normalize(it)) }) return@mapNotNull null
            terms(rule).filter { normalize(it).length >= 3 && normalized.contains(normalize(it)) }
                .maxByOrNull { normalize(it).length }?.let { rule to it }
        }.sortedByDescending { normalize(it.second).length }
        val best = matches.firstOrNull() ?: return null
        return best.takeIf { matches.map { match -> match.first.denominationId }.distinct().size == 1 }
    }

    private fun pagePriority(page: jp.co.crossmap.CrawledPage): Int {
        val value = "${page.url} ${page.title}".lowercase()
        return when {
            Regex("about|私たち|教会紹介|信仰").containsMatchIn(value) -> 1
            Regex("index|home|トップ").containsMatchIn(value) -> 0
            else -> 2
        }
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[\\s　・･()（）\\-]"), "")
}

fun interface EntityMatcher {
    suspend fun match(input: EntityMatchInput): EntityMatchDecision
}

class KoogOllamaEntityMatcher(
    override val modelName: String,
    baseUrl: String = "http://localhost:11434",
) : EntityMatcher, ModelIdentified {
    private val agent = KoogOllamaJsonAgent(
        modelName = modelName,
        baseUrl = baseUrl,
        systemPrompt = """
            あなたは日本の教会データを照合する慎重なデータキュレーターです。
            Google Maps の教会名・住所・ウェブ本文と、教派公式リスト由来の候補を比較してください。
            同名別組織を誤結合しないでください。根拠が弱い場合は denominationId を null にしてください。
            回答は説明文を付けず、denominationId, confidence, sameEntityCandidateSource, reasoning を持つJSONオブジェクトだけにしてください。
            confidence は0から1です。
        """.trimIndent(),
        maxOutputTokens = 400,
        timeoutMillis = 60_000,
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun match(input: EntityMatchInput): EntityMatchDecision {
        val response = agent.run(
            "次のレコードの教派を判定してください。候補が同一実体なら住所表記揺れも考慮してください。入力JSON:\n" +
                json.encodeToString(input)
        )
        val objectText = response.substring(response.indexOf('{').coerceAtLeast(0), response.lastIndexOf('}') + 1)
        return json.decodeFromString(objectText)
    }
}

interface ModelIdentified { val modelName: String }

class PostCrawlCleanup(
    private val matcher: EntityMatcher,
    private val confidenceThreshold: Double = 0.80,
    private val programmaticMatcher: ProgrammaticDenominationMatcher = ProgrammaticDenominationMatcher(),
    private val webpageGuesser: DenominationGuesser? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    init {
        require(confidenceThreshold in 0.0..1.0)
    }

    suspend fun run(
        resourcesRoot: Path,
        limit: Int = Int.MAX_VALUE,
        applyChanges: Boolean = true,
        enableLlm: Boolean = true,
    ): CleanupReport {
        val cleanupDir = resourcesRoot.resolve("cleanup")
        Files.createDirectories(cleanupDir)
        val catalogFile = resourcesRoot.resolve("catalog/churches.json")
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile))
        val candidates = readList<DenominationCandidate>(cleanupDir.resolve("denomination-candidates.json"))
        val rules = readList<DenominationRule>(cleanupDir.resolve("denomination-rules.json"))
        val overrides = readList<HumanOverride>(cleanupDir.resolve("human-overrides.json"))
        ensureReviewFiles(cleanupDir)
        val allowed = (churches.mapNotNull { it.denominationId } + candidates.map { it.denominationId } + rules.map { it.denominationId })
            .filter { it != NOT_DETERMINED }.distinct().sorted()
        val now = Instant.now().toString()
        val audit = mutableListOf<CleanupAuditEntry>()
        var processed = 0
        var errors = 0

        val updated = churches.map { original ->
            val override = overrides.lastOrNull { it.churchId == original.id && it.field == "denominationId" }
            if (override != null) {
                audit += CleanupAuditEntry(original.id, original.denominationId.orEmpty(), override.value, 1.0, true, DeterminationSource.HUMAN, listOf(override.note), reasoning = override.note, determinedAt = override.reviewedAt.ifBlank { now })
                return@map original.withDenomination(override.value, audit.last().toDetermination())
            }
            val current = original.denominationId
            if (!current.isNullOrBlank() && current != NOT_DETERMINED) {
                val existing = original.determinations.any { it.field == "denominationId" && it.value == current }
                return@map if (existing) original else original.withDenomination(
                    current,
                    FieldDetermination("denominationId", current, DeterminationSource.PROGRAMMATIC, 1.0, listOf("Imported crawler result"), determinedAt = now),
                )
            }
            val officialCandidates = candidatesFor(original, candidates)
            val programmatic = programmaticMatcher.match(original, rules, officialCandidates)
            if (programmatic != null) {
                val entry = CleanupAuditEntry(
                    original.id, current.orEmpty(), programmatic.denominationId, programmatic.confidence, true,
                    DeterminationSource.PROGRAMMATIC, programmatic.evidence, reasoning = programmatic.reasoning, determinedAt = now,
                )
                audit += entry
                return@map original.withDenomination(programmatic.denominationId, entry.toDetermination())
            }
            if (!enableLlm) return@map original
            if (processed++ >= limit) return@map original
            val activeWebpageGuesser = webpageGuesser
            if (activeWebpageGuesser != null && original.pages.isNotEmpty()) {
                var bestPageGuess: Pair<String, DenominationGuessResult>? = null
                for (page in original.pages) {
                    val guess = runCatching { activeWebpageGuesser.determineDenominationByLlm(page.text) }
                        .onFailure { errors++ }
                        .getOrNull() ?: continue
                    if (bestPageGuess == null || guess.score > bestPageGuess!!.second.score) bestPageGuess = page.url to guess
                    if (!guess.denomination.proposed && guess.denomination.id != NOT_DETERMINED && guess.score >= confidenceThreshold) break
                }
                bestPageGuess?.let { (pageUrl, guess) ->
                    val known = guess.denomination.id in allowed
                    val accepted = known && !guess.denomination.proposed && guess.score >= confidenceThreshold
                    val entry = CleanupAuditEntry(
                        original.id, current.orEmpty(), guess.denomination.id, guess.score.toDouble(), accepted,
                        DeterminationSource.LLM, listOf(pageUrl), guess.model, guess.reasoning, now,
                    )
                    audit += entry
                    if (accepted) return@map original.withDenomination(guess.denomination.id, entry.toDetermination())
                }
            }
            val input = EntityMatchInput(
                churchId = original.id,
                churchName = original.name,
                address = original.address,
                websiteUrl = original.websiteUrl,
                websiteText = original.pages.joinToString("\n") { "${it.title}\n${it.text}" }.take(4_000),
                allowedDenominationIds = allowed,
                officialCandidates = officialCandidates.take(12),
            )
            runCatching { matcher.match(input) }.fold(
                onSuccess = { decision ->
                    val valid = decision.denominationId in allowed
                    val accepted = valid && decision.confidence >= confidenceThreshold
                    val entry = CleanupAuditEntry(
                        original.id, current.orEmpty(), decision.denominationId, decision.confidence, accepted,
                        DeterminationSource.LLM,
                        evidence = input.officialCandidates.map { it.source } + original.websiteUrl,
                        model = (matcher as? ModelIdentified)?.modelName,
                        reasoning = decision.reasoning,
                        determinedAt = now,
                    )
                    audit += entry
                    if (accepted) original.withDenomination(requireNotNull(decision.denominationId), entry.toDetermination()) else original
                },
                onFailure = { error ->
                    errors++
                    audit += CleanupAuditEntry(original.id, current.orEmpty(), accepted = false, source = DeterminationSource.LLM, model = (matcher as? ModelIdentified)?.modelName, reasoning = error.message.orEmpty(), determinedAt = now)
                    original
                },
            )
        }

        if (applyChanges) atomicWrite(catalogFile, json.encodeToString(updated))
        atomicWrite(cleanupDir.resolve("decisions.json"), json.encodeToString(audit))
        return CleanupReport(
            total = updated.size,
            notDeterminedBefore = churches.count { it.denominationId.isNullOrBlank() || it.denominationId == NOT_DETERMINED },
            notDeterminedAfter = updated.count { it.denominationId.isNullOrBlank() || it.denominationId == NOT_DETERMINED },
            programmaticAccepted = audit.count { it.source == DeterminationSource.PROGRAMMATIC && it.accepted && it.previousValue == NOT_DETERMINED },
            llmAccepted = audit.count { it.source == DeterminationSource.LLM && it.accepted },
            uncertain = audit.count { it.source == DeterminationSource.LLM && !it.accepted },
            humanOverrides = audit.count { it.source == DeterminationSource.HUMAN },
            errors = errors,
        )
    }

    private inline fun <reified T> readList(path: Path): List<T> =
        if (Files.isRegularFile(path)) json.decodeFromString(Files.readString(path)) else emptyList()

    private fun ensureReviewFiles(directory: Path) {
        listOf("denomination-candidates.json", "human-overrides.json").forEach {
            val file = directory.resolve(it)
            if (!Files.exists(file)) Files.writeString(file, "[]\n")
        }
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun candidatesFor(church: ChurchRecord, candidates: List<DenominationCandidate>): List<DenominationCandidate> =
        candidates.map {
            val nameScore = JapaneseEntityNormalizer.deterministicNameScore(church.name, it.churchName)
            val addressScore = JapaneseEntityNormalizer.deterministicAddressScore(church.address, it.address)
            val entityScore = JapaneseEntityNormalizer.deterministicEntityScore(church.name, church.address, it.churchName, it.address)
            it.copy(nameSimilarity = nameScore, addressSimilarity = addressScore, entitySimilarity = entityScore) to entityScore
        }
            .filter { (candidate, score) ->
                score >= 0.35f || JapaneseEntityNormalizer.deterministicNameScore(church.name, candidate.churchName) >= 0.60f
            }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[\\s　・･()（）\\-]"), "")

    private fun ChurchRecord.withDenomination(value: String, determination: FieldDetermination): ChurchRecord = copy(
        denominationId = value,
        determinations = determinations.filterNot { it.field == "denominationId" } + determination,
        updatedAt = determination.determinedAt,
    )

    private fun CleanupAuditEntry.toDetermination() = FieldDetermination(
        field = "denominationId",
        value = requireNotNull(proposedValue),
        source = source,
        confidence = confidence,
        evidence = evidence,
        model = model,
        determinedAt = determinedAt,
    )
}
