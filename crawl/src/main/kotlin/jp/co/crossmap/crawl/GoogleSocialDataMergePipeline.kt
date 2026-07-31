package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SocialMergeStatus { EXACT_MATCH, ESTIMATED_MATCH, NOT_MATCHED, EXCLUDED }

@Serializable
data class SocialMergeDecision(
    val socialAccountId: String,
    val platform: SocialPlatform,
    val accountName: String,
    val accountUrl: String,
    val status: SocialMergeStatus,
    val churchId: String? = null,
    val churchName: String? = null,
    val score: Float = 0f,
    val reasoning: String,
)

data class GoogleSocialDataMergeReport(
    val googleSavedPlaces: Int,
    val socialWebsiteUrlsMigrated: Int,
    val accountsParsed: Int,
    val exactMatches: Int,
    val estimatedMatches: Int,
    val notMatched: Int,
    val excluded: Int,
    val decisions: List<SocialMergeDecision>,
    val auditLog: Path,
    val updatedChurches: List<ChurchRecord>,
)

class GoogleSocialDataMergePipeline(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun run(
        resourcesRoot: Path,
        churches: List<ChurchRecord>,
        inputs: SocialExportInputPaths = SocialExportInputs.load(),
        auditLog: Path = Path.of("logs/2026-07-23-19-04-google-social-data-merge.log"),
    ): GoogleSocialDataMergeReport {
        val originalChurches = churches
        val migrated = originalChurches.map(::migrateSocialWebsite)
        val migratedCount = originalChurches.zip(migrated).count { (before, after) ->
            before.websiteUrl.isNotBlank() && after.websiteUrl.isBlank()
        }
        val accounts = SocialExportReader().read(inputs)
        val matcher = SocialChurchAccountMatcher(migrated)
        val decisions = accounts.map(matcher::match)
        val accountsById = accounts.associateBy(SocialAccountCandidate::id)
        val acceptedByChurch = decisions.asSequence()
            // Estimated matches are intentionally audit-only. They are useful review candidates,
            // but publishing them automatically would turn a fuzzy church-name collision into
            // durable catalog data.
            .filter { it.status == SocialMergeStatus.EXACT_MATCH }
            // Some exports (notably Facebook pages_followed_v2) provide names without page URLs.
            // They are useful matching evidence, but cannot become a SocialProfile safely.
            .filter { decision -> accountsById.getValue(decision.socialAccountId).url.isNotBlank() }
            .groupBy { requireNotNull(it.churchId) }
        val now = Instant.now().toString()
        val updatedChurches = migrated.map { church ->
            val accepted = acceptedByChurch[church.id].orEmpty().mapNotNull { decision ->
                accountsById[decision.socialAccountId]?.let { it to decision }
            }
            if (accepted.isEmpty()) church else church.copy(
                socialProfiles = (church.socialProfiles + accepted.map { (account) -> account.toProfile(now) })
                    .distinctBy { it.platform to SocialUrlNormalizer.identityKey(it.url, it.platform) },
                determinations = (church.determinations + accepted.map { (account, decision) ->
                    FieldDetermination(
                        field = "socialProfiles.${account.platform.name.lowercase()}",
                        value = account.url,
                        source = DeterminationSource.PROGRAMMATIC,
                        confidence = decision.score.toDouble(),
                        evidence = listOf(account.sourceUrl, account.accountName, decision.reasoning),
                        determinedAt = now,
                    )
                }).distinctBy { determination ->
                    if (determination.field.startsWith("socialProfiles.")) {
                        determination.field to SocialUrlNormalizer.identityKey(determination.value)
                    } else {
                        determination
                    }
                },
                updatedAt = now,
            )
        }
        val evidenceFile = resourcesRoot.resolve("evidence/social-accounts.json")
        val decisionsFile = resourcesRoot.resolve("cleanup/social-merge-decisions.json")
        atomicWrite(evidenceFile, json.encodeToString(accounts))
        atomicWrite(decisionsFile, json.encodeToString(decisions))
        val report = GoogleSocialDataMergeReport(
            googleSavedPlaces = originalChurches.count { it.id.startsWith("google:") },
            socialWebsiteUrlsMigrated = migratedCount,
            accountsParsed = accounts.size,
            exactMatches = decisions.count { it.status == SocialMergeStatus.EXACT_MATCH },
            estimatedMatches = decisions.count { it.status == SocialMergeStatus.ESTIMATED_MATCH },
            notMatched = decisions.count { it.status == SocialMergeStatus.NOT_MATCHED },
            excluded = decisions.count { it.status == SocialMergeStatus.EXCLUDED },
            decisions = decisions,
            auditLog = auditLog,
            updatedChurches = updatedChurches,
        )
        SocialMergeAuditWriter.write(report, auditLog)
        return report
    }

    private fun migrateSocialWebsite(church: ChurchRecord): ChurchRecord {
        val platform = SocialUrlNormalizer.platform(church.websiteUrl) ?: return church
        val canonical = SocialUrlNormalizer.canonical(church.websiteUrl, platform)
        if (canonical.isBlank()) return church
        val profile = SocialProfile(
            platform = platform,
            url = canonical,
            handle = SocialUrlNormalizer.handle(canonical),
            displayName = church.name,
            discoveredAt = Instant.now().toString(),
        )
        return church.copy(
            websiteUrl = "",
            socialProfiles = (church.socialProfiles + profile)
                .distinctBy { it.platform to SocialUrlNormalizer.identityKey(it.url, it.platform) },
        )
    }

    private fun SocialAccountCandidate.toProfile(now: String) = SocialProfile(
        platform = platform,
        url = SocialUrlNormalizer.canonical(url, platform),
        handle = SocialUrlNormalizer.handle(url),
        displayName = accountName,
        description = description.takeIf(String::isNotBlank),
        discoveredAt = now,
    )

    private fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}

internal class SocialChurchAccountMatcher(churches: List<ChurchRecord>) {
    private data class IndexedChurch(val church: ChurchRecord, val names: Set<String>)
    private val indexed = churches.filter(SocialChurchCandidateClassifier::isEligible).map { church ->
        IndexedChurch(church, SocialChurchNameNormalizer.variants(church))
    }
    private val directUrls = buildMap<String, MutableList<IndexedChurch>> {
        indexed.forEach { church ->
            (church.church.socialProfiles.map(SocialProfile::url) + church.church.websiteUrl)
                .filter(String::isNotBlank)
                .forEach { url -> getOrPut(SocialUrlNormalizer.canonical(url)) { mutableListOf() } += church }
        }
    }
    private val exactNames = buildMap<String, MutableList<IndexedChurch>> {
        indexed.forEach { church -> church.names.forEach { name -> getOrPut(name) { mutableListOf() } += church } }
    }
    private val gramIndex = buildMap<String, MutableSet<Int>> {
        indexed.forEachIndexed { index, church ->
            church.names.flatMap(::bigrams).forEach { gram -> getOrPut(gram) { linkedSetOf() } += index }
        }
    }

    fun match(account: SocialAccountCandidate): SocialMergeDecision {
        val canonicalUrl = SocialUrlNormalizer.canonical(account.url, account.platform)
        directUrls[canonicalUrl].orEmpty().singleOrNull()?.let { direct ->
            return decision(account, SocialMergeStatus.EXACT_MATCH, direct, 1f, "Social URL is already published by the church or denomination directory")
        }
        SocialChurchAccountClassifier.explicitNonChurchReason(account)?.let { reason ->
            return SocialMergeDecision(account.id, account.platform, account.accountName, account.url, SocialMergeStatus.EXCLUDED, reasoning = reason)
        }
        val accountNames = SocialChurchNameNormalizer.variants(account)
        val exact = accountNames.flatMap { exactNames[it].orEmpty() }.distinctBy { it.church.id }
        if (exact.size == 1) {
            return decision(account, SocialMergeStatus.EXACT_MATCH, exact.single(), 1f, "A decomposed Google church name exactly equals a decomposed social account name")
        }
        val exclusion = SocialChurchAccountClassifier.exclusionReason(account)
        if (exclusion != null) {
            return SocialMergeDecision(account.id, account.platform, account.accountName, account.url, SocialMergeStatus.EXCLUDED, reasoning = exclusion)
        }
        val candidateHits = mutableMapOf<Int, Int>()
        accountNames.flatMap(::bigrams).forEach { gram ->
            gramIndex[gram].orEmpty().forEach { index -> candidateHits[index] = candidateHits.getOrDefault(index, 0) + 1 }
        }
        val ranked = candidateHits.entries.sortedByDescending(Map.Entry<Int, Int>::value).take(80).map { (index) ->
            val church = indexed[index]
            church to maxNameScore(accountNames, church.names)
        }.sortedByDescending { it.second }
        val winner = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        if (winner != null && winner.second >= 0.82f && winner.second - runnerUp >= 0.08f) {
            return decision(account, SocialMergeStatus.ESTIMATED_MATCH, winner.first, winner.second, "Unique high-scoring decomposed-name match")
        }
        return SocialMergeDecision(
            account.id, account.platform, account.accountName, account.url, SocialMergeStatus.NOT_MATCHED,
            churchId = winner?.first?.church?.id,
            churchName = winner?.first?.church?.name,
            score = winner?.second ?: 0f,
            reasoning = if (winner != null && winner.second >= 0.82f) "Ambiguous high-scoring matches require review" else "No sufficiently similar Japanese church was found",
        )
    }

    private fun decision(
        account: SocialAccountCandidate,
        status: SocialMergeStatus,
        church: IndexedChurch,
        score: Float,
        reasoning: String,
    ) = SocialMergeDecision(account.id, account.platform, account.accountName, account.url, status, church.church.id, church.church.name, score, reasoning)

    private fun maxNameScore(left: Set<String>, right: Set<String>): Float = left.maxOfOrNull { a ->
        right.maxOfOrNull { b ->
            when {
                a == b -> 1f
                oneSidedDenominationMatch(a, b) -> 0.90f
                minOf(congregationStem(a).length, congregationStem(b).length) >= 4 &&
                    (a.contains(b) || b.contains(a)) -> 0.94f
                else -> JapaneseEntityNormalizer.deterministicNormalizedNameScore(a, b)
            }
        } ?: 0f
    } ?: 0f

    private fun oneSidedDenominationMatch(left: String, right: String): Boolean {
        val leftToken = denominationToken.find(left)?.value
        val rightToken = denominationToken.find(right)?.value
        if ((leftToken == null) == (rightToken == null)) return false
        return left.replace(denominationToken, "") == right.replace(denominationToken, "")
    }

    private fun congregationStem(value: String): String = value.replace(
        Regex("(?:キリスト)?教会|伝道所|チャペル|礼拝堂|聖堂|church|chapel"),
        "",
    )

    private val denominationToken = Regex("日本キリスト教団|日本聖公会|カトリック|バプテスト|ルーテル|福音自由")

    private fun bigrams(value: String): Set<String> = when {
        value.isBlank() -> emptySet()
        value.length == 1 -> setOf(value)
        else -> (0 until value.length - 1).mapTo(linkedSetOf()) { value.substring(it, it + 2) }
    }
}

internal object SocialChurchNameNormalizer {
    private val decorations = Regex(
        "(?i)(?:公式(?:アカウント|チャンネル)?|official(?:\\s+channel)?|youtube(?:\\s+channel)?|チャンネル|instagram|facebook|ライブ配信|礼拝配信|広報)$",
    )

    fun variants(church: ChurchRecord): Set<String> = (listOf(church.name, church.englishName) + church.localizedNames.map { it.name })
        .flatMap(::variants)
        .toSet()

    fun variants(account: SocialAccountCandidate): Set<String> = buildSet {
        addAll(variants(account.accountName))
        addAll(variants(SocialUrlNormalizer.handle(account.url).replace('_', ' ').replace('-', ' ')))
    }

    fun variants(value: String): Set<String> {
        val public = ChurchPublicNameNormalizer.normalize(value)
            .replace(Regex("[\uD83C-\uDBFF\uDC00-\uDFFF]"), " ")
            .replace(Regex("[【(（\\[]\\s*公式\\s*[】)）\\]]"), "")
            .replace(decorations, "")
            .trim()
        if (public.isBlank()) return emptySet()
        val parts = (listOf(public) + public.split(Regex("[|｜/／@]"))).map(String::trim).filter(String::isNotBlank)
        return parts.flatMap { part ->
            val normalized = normalize(part)
            listOf(
                normalized,
                canonicalDenominationOrder(normalized),
                normalized.replace(Regex("(?:キリスト)?教会|伝道所|チャペル|礼拝堂"), ""),
            )
        }.filter { it.length >= 2 }.toSet()
    }

    private fun normalize(value: String): String = JapaneseEntityNormalizer.name(
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("基督", "キリスト")
            .replace(Regex("(?i)church$"), "教会")
            .replace(Regex("[\\s　・･,，.。()（）【】「」『』'\"_:：;；\\-]+"), ""),
    )

    private fun canonicalDenominationOrder(value: String): String {
        val token = denominationOrderToken.find(value)?.value ?: return value
        return token + value.replace(denominationOrderToken, "")
    }

    private val denominationOrderToken = Regex("日本キリスト教団|日本聖公会|カトリック|バプテスト|ルーテル|福音自由")
}

internal object SocialChurchAccountClassifier {
    private val churchSignal = Regex(
        "(?i)教会|伝道所|チャペル|礼拝堂|聖堂|church|chapel|parish|congregation|igreja|교회|성당|gereja|(?:^|[._-])ch(?:$|[._-])",
    )
    private val nonChurchOrganization = Regex(
        "(?i)神学校|学院|保育園|幼稚園|学校|大学|キャンプ|聖書協会|放送|出版社|書店|ミニストリー|ministry|seminary|school|camp|association|music|musician|singer|artist",
    )
    private val japanSignal = Regex(
        "(?i)[ぁ-んァ-ヶ一-龯]|japan|japanese|tokyo|osaka|kyoto|kobe|nagoya|yokohama|sapporo|fukuoka|okinawa|日本|東京|大阪|京都|神戸|名古屋|横浜|札幌|福岡|沖縄",
    )

    fun exclusionReason(account: SocialAccountCandidate): String? {
        val text = "${account.accountName} ${account.description}"
        explicitNonChurchReason(account)?.let { return it }
        if (!churchSignal.containsMatchIn(text)) return "Excluded account without church/congregation identity evidence"
        if (!japanSignal.containsMatchIn(text)) return "Excluded non-Japanese church account"
        return null
    }

    fun explicitNonChurchReason(account: SocialAccountCandidate): String? {
        val text = "${account.accountName} ${account.description}"
        return if (nonChurchOrganization.containsMatchIn(text) && !churchSignal.containsMatchIn(text)) {
            "Excluded non-church Christian organization or individual/media account"
        } else {
            null
        }
    }
}

internal object SocialChurchCandidateClassifier {
    private val congregationIdentity = Regex(
        "(?i)教会|伝道所|チャペル|礼拝堂|聖堂|チャーチ|集会|小隊|分隊|センター|フェローシップ|聖公会|church|chapel|parish|congregation|igreja|교회|성당|gereja",
    )
    private val nonChurch = Regex("保育園|幼稚園|学校|大学|神学校|キャンプ|教団$|教区$|連盟$|協議会$|事務局$|修道院$")

    fun isEligible(church: ChurchRecord): Boolean = congregationIdentity.containsMatchIn(church.name) &&
        !nonChurch.containsMatchIn(church.name)
}

internal object SocialMergeAuditWriter {
    fun write(report: GoogleSocialDataMergeReport, path: Path) {
        Files.createDirectories(path.toAbsolutePath().normalize().parent)
        val content = buildString {
            appendLine("google social data merge summary {")
            appendLine("  google saved places: ${report.googleSavedPlaces}")
            appendLine("  google website social urls migrated: ${report.socialWebsiteUrlsMigrated}")
            appendLine("  social accounts parsed: ${report.accountsParsed}")
            appendLine("  exact match: ${report.exactMatches}")
            appendLine("  estimated match: ${report.estimatedMatches}")
            appendLine("  not matched: ${report.notMatched}")
            appendLine("  excluded: ${report.excluded}")
            appendLine("}")
            report.decisions.filter { it.status != SocialMergeStatus.EXACT_MATCH }.forEach { decision ->
                appendLine()
                appendLine("social account {")
                appendLine("  performed operation: ${decision.status.name.lowercase()}")
                appendLine("  platform: ${decision.platform.name.lowercase()}")
                appendLine("  account name: ${decision.accountName}")
                appendLine("  account url: ${decision.accountUrl}")
                appendLine("  candidate church id: ${decision.churchId.orEmpty()}")
                appendLine("  candidate church name: ${decision.churchName.orEmpty()}")
                appendLine("  score: ${"%.3f".format(decision.score)}")
                appendLine("  reasoning: ${decision.reasoning}")
                appendLine("}")
            }
        }
        Files.writeString(path, content)
    }
}
