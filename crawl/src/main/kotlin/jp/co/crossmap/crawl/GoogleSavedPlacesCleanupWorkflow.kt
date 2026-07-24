package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.crawl.denomination.OfficialDenominationChurchListPipeline
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class GoogleSavedPlacesPromotionReport(
    val rawCandidates: Int,
    val exactDuplicatesMerged: Int,
    val nonGoogleRecordsRetained: Int,
    val existingEvidenceReused: Int,
    val websiteFetched: Int,
    val websiteErrors: Int,
    val directoryCandidates: Int,
    val denominationProgrammatic: Int,
    val denominationLlm: Int,
    val denominationHuman: Int,
    val denominationUncertain: Int,
    val socialAccountsParsed: Int,
    val socialWebsiteUrlsMigrated: Int,
    val socialExactMatches: Int,
    val socialEstimatedMatches: Int,
    val socialNotMatched: Int,
    val socialExcluded: Int,
    val finalChurches: Int,
    val englishNamesProgrammatic: Int,
    val englishNamesLlm: Int,
    val promoted: Boolean,
    val stageDurationsSeconds: Map<String, Double>,
)

/**
 * Connects the standalone Google Saved Places source to the existing Crossmap cleanup pipeline.
 * The canonical catalog is replaced only after denomination cleanup and mandatory English naming succeed.
 */
class GoogleSavedPlacesCleanupWorkflow(
    private val postCrawlCleanup: PostCrawlCleanup,
    private val englishNameResolver: ChurchEnglishNameResolver,
    private val websiteRefresher: WebsiteRefresher = WebsiteRefresher(),
    private val directoryCrawler: OfficialDenominationChurchListPipeline = OfficialDenominationChurchListPipeline(),
    private val now: () -> String = { Instant.now().toString() },
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    suspend fun run(
        resourcesRoot: Path,
        llmLimit: Int = Int.MAX_VALUE,
        enableLlm: Boolean = true,
        refreshWebsites: Boolean = true,
        crawlDirectories: Boolean = true,
        cleanupDenominations: Boolean = true,
        socialInputs: SocialExportInputPaths? = null,
        promote: Boolean = true,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): GoogleSavedPlacesPromotionReport {
        val totalMark = TimeSource.Monotonic.markNow()
        val stageDurations = linkedMapOf<String, Double>()
        val progress = PipelineProgress("promote-google-saved-places")
        var stageMark = TimeSource.Monotonic.markNow()
        progress.start("prepare_load_inputs")
        val prepared = preparePendingCatalog(resourcesRoot, cacheRoot) { stage -> progress.start("prepare_$stage") }
        stageDurations += prepared.stageDurationsSeconds.mapKeys { "prepare_${it.key}" }
        stageDurations["prepare_total"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        val staging = pendingCatalog(resourcesRoot, cacheRoot)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("website_refresh")
        val website = if (refreshWebsites) websiteRefresher.refresh(resourcesRoot, staging, cacheRoot) else null
        stageDurations["website_refresh"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("directory_crawl")
        val directory = if (crawlDirectories) {
            // Crawl/cache official inputs here, then reconcile exactly once after denomination cleanup below.
            directoryCrawler.run(resourcesRoot, cacheRoot, catalogFile = null)
        } else {
            null
        }
        stageDurations["directory_crawl"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("denomination_cleanup")
        val cleanup = if (cleanupDenominations) {
            postCrawlCleanup.run(
                resourcesRoot = resourcesRoot,
                limit = llmLimit,
                applyChanges = true,
                enableLlm = enableLlm,
                catalogFile = staging,
                cacheRoot = cacheRoot,
            )
        } else {
            null
        }
        stageDurations["denomination_cleanup"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("directory_reconcile")
        if (directory != null) directoryCrawler.reconcileGeneratedLists(staging, resourcesRoot)
        stageDurations["directory_reconcile"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("social_merge")
        val social = socialInputs?.let { inputs ->
            GoogleSocialDataMergePipeline(json).run(
                resourcesRoot = resourcesRoot,
                inputs = inputs,
                applyChanges = true,
                catalogFile = staging,
            )
        }
        stageDurations["social_merge"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        progress.start("validate_pending")
        val completed = json.decodeFromString<List<ChurchRecord>>(Files.readString(staging))
        require(completed.all { it.englishName.isNotBlank() }) { "Pending catalog contains blank English names" }
        stageDurations["validate_pending"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)

        stageMark = TimeSource.Monotonic.markNow()
        progress.start("promote_catalog")
        if (promote) {
            atomicWrite(resourcesRoot.resolve("catalog/churches.json"), json.encodeToString(completed))
        }
        stageDurations["promote_catalog"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageDurations["total"] = totalMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        progress.complete()
        return GoogleSavedPlacesPromotionReport(
            rawCandidates = prepared.rawCandidates,
            exactDuplicatesMerged = prepared.exactDuplicatesMerged,
            nonGoogleRecordsRetained = prepared.nonGoogleRecordsRetained,
            existingEvidenceReused = prepared.existingEvidenceReused,
            websiteFetched = website?.fetched ?: 0,
            websiteErrors = website?.errors ?: 0,
            directoryCandidates = directory?.candidates ?: 0,
            denominationProgrammatic = cleanup?.programmaticAccepted ?: 0,
            denominationLlm = cleanup?.llmAccepted ?: 0,
            denominationHuman = cleanup?.humanOverrides ?: 0,
            denominationUncertain = cleanup?.uncertain ?: 0,
            socialAccountsParsed = social?.accountsParsed ?: 0,
            socialWebsiteUrlsMigrated = social?.socialWebsiteUrlsMigrated ?: 0,
            socialExactMatches = social?.exactMatches ?: 0,
            socialEstimatedMatches = social?.estimatedMatches ?: 0,
            socialNotMatched = social?.notMatched ?: 0,
            socialExcluded = social?.excluded ?: 0,
            finalChurches = completed.size,
            englishNamesProgrammatic = prepared.englishNamesProgrammatic,
            englishNamesLlm = prepared.englishNamesLlm,
            promoted = promote,
            stageDurationsSeconds = stageDurations,
        )
    }

    data class PreparationReport(
        val rawCandidates: Int,
        val exactDuplicatesMerged: Int,
        val nonGoogleRecordsRetained: Int,
        val existingEvidenceReused: Int,
        val pendingChurches: Int,
        val englishNamesProgrammatic: Int,
        val englishNamesLlm: Int,
        val stageDurationsSeconds: Map<String, Double>,
    )

    suspend fun preparePendingCatalog(
        resourcesRoot: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
        onStage: (String) -> Unit = {},
    ): PreparationReport {
        val stageDurations = linkedMapOf<String, Double>()
        var stageMark = TimeSource.Monotonic.markNow()
        onStage("load_inputs")
        val websitePolicy = ExcludedChurchListingDomains.policy(resourcesRoot)
        val candidatesFile = CrossmapPaths(resourcesRoot, cacheRoot).googleSavedPlaces.resolve("google-place-candidates.json")
        require(Files.isRegularFile(candidatesFile)) { "Google place candidates do not exist: $candidatesFile" }
        val rawCandidates = json.decodeFromString<List<GooglePlaceChurchCandidate>>(Files.readString(candidatesFile))
        val reviewedNameReadingsFile = resourcesRoot.resolve("catalog/church-name-readings.json")
        val reviewedNameReadings = if (Files.isRegularFile(reviewedNameReadingsFile)) {
            json.decodeFromString<Map<String, List<String>>>(Files.readString(reviewedNameReadingsFile))
        } else {
            emptyMap()
        }
        val catalog = resourcesRoot.resolve("catalog/churches.json")
        val existing = if (Files.isRegularFile(catalog)) {
            json.decodeFromString<List<ChurchRecord>>(Files.readString(catalog))
        } else {
            emptyList()
        }
        val existingByCid = existing.mapNotNull { church -> church.googleCid?.let { it to church } }.toMap()
        stageDurations["load_inputs"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        onStage("normalize_candidates")
        val normalized = rawCandidates.map { candidate ->
            candidate.copy(
                name = GooglePlaceChurchNameNormalizer.normalize(candidate.name),
                localizedNames = (
                    candidate.localizedNames + reviewedNameReadings[candidate.id].orEmpty()
                        .map { LocalizedName("ja", it) }
                    ).map { localizedName ->
                    localizedName.copy(name = GooglePlaceChurchNameNormalizer.normalize(localizedName.name))
                }.filter { it.name.isNotBlank() }.distinctBy { it.languageCode to it.name },
                address = GooglePlaceAddressNormalizer.normalize(candidate.address),
                websiteUrl = websitePolicy.publicWebsiteUrl(candidate.websiteUrl, candidate.googleCid, candidate.id),
            )
        }.filter { GooglePlaceChurchCandidatePolicy.isUsableChurchName(it.name) }
        stageDurations["normalize_candidates"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        onStage("merge_duplicates")
        val groups = normalized.groupBy { exactEntityKey(it.name, it.address) }
        val merged = groups.values.map { duplicates ->
            val preferred = duplicates.minBy(GooglePlaceChurchCandidate::googleCid)
            val sourceLists = duplicates.flatMap(GooglePlaceChurchCandidate::sourceLists).distinct().sorted()
            preferred.copy(
                sourceLists = sourceLists,
                denominationHint = GoogleSavedPlacesLists.deterministicDenominationId(sourceLists)
                    ?: duplicates.firstNotNullOfOrNull(GooglePlaceChurchCandidate::denominationHint),
                websiteUrl = duplicates.map(GooglePlaceChurchCandidate::websiteUrl)
                    .firstOrNull { !it.contains("google.com/maps") }
                    ?: preferred.websiteUrl,
            )
        }
        stageDurations["merge_duplicates"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        onStage("resolve_english_names")
        val namingInputs = merged.map { candidate ->
            val previous = existingByCid[candidate.googleCid]
            val humanDenomination = previous?.determinations
                ?.lastOrNull { it.field == "denominationId" && it.source == DeterminationSource.HUMAN }
                ?.value
            val denomination = humanDenomination
                ?: GoogleSavedPlacesLists.deterministicDenominationId(candidate.sourceLists)
                ?: previous?.denominationId
                ?.takeUnless { it == NOT_DETERMINED }
                ?: candidate.denominationHint
                ?: NOT_DETERMINED
            ChurchEnglishNameInput(
                id = candidate.id,
                name = candidate.name,
                existingEnglishName = candidate.localizedNames
                    .firstOrNull { it.languageCode.substringBefore('-').equals("en", ignoreCase = true) }
                    ?.name
                    ?.takeIf(ChurchEnglishNameResolver::isUsableEnglishChurchName)
                    ?: previous?.englishName?.takeIf(::isReusablePublishedLatinName)
                    ?: candidate.latinName?.takeIf(ChurchEnglishNameResolver::isUsableEnglishChurchName),
                denominationId = denomination,
                address = candidate.address,
                location = candidate.location,
                websiteUrl = candidate.websiteUrl,
                pages = previous?.pages.orEmpty().filterNot { websitePolicy.isExcluded(it.url) },
                socialProfiles = previous?.socialProfiles.orEmpty(),
            )
        }
        val englishResolutions = englishNameResolver.resolveInputs(namingInputs)
        stageDurations["resolve_english_names"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        onStage("assemble_catalog")
        val fromGoogle = merged.map { candidate ->
            val previous = existingByCid[candidate.googleCid]
            val humanDenomination = previous?.determinations
                ?.lastOrNull { it.field == "denominationId" && it.source == DeterminationSource.HUMAN }
                ?.value
            val listDenomination = GoogleSavedPlacesLists.deterministicDenominationId(candidate.sourceLists)
            val denomination = humanDenomination
                ?: listDenomination
                ?: previous?.denominationId
                ?.takeUnless { it == NOT_DETERMINED }
                ?: candidate.denominationHint
                ?: NOT_DETERMINED
            val english = requireNotNull(englishResolutions[candidate.id])
            val determinedAt = now()
            val determinations = previous?.determinations.orEmpty().toMutableList()
            if (humanDenomination == null && listDenomination != null) {
                determinations.removeAll { it.field == "denominationId" }
                determinations += FieldDetermination(
                    field = "denominationId",
                    value = listDenomination,
                    source = DeterminationSource.PROGRAMMATIC,
                    confidence = 1.0,
                    evidence = listOf("Google Saved Places list: ${GoogleSavedPlacesLists.CATHOLIC_CHURCH}"),
                    determinedAt = determinedAt,
                )
            }
            determinations.removeAll { it.field == "englishName" }
            val previousEnglish = previous?.determinations?.lastOrNull { it.field == "englishName" }
                ?.takeIf { it.value == english.englishName }
            determinations += previousEnglish ?: FieldDetermination(
                    field = "englishName",
                    value = english.englishName,
                    source = english.source,
                    confidence = english.confidence.toDouble(),
                    evidence = english.evidence,
                    model = english.model,
                    determinedAt = determinedAt,
                )
            ChurchRecord(
                id = candidate.id,
                googleCid = candidate.googleCid,
                name = candidate.name,
                englishName = english.englishName,
                localizedNames = candidate.localizedNames,
                titleLanguages = candidate.titleLanguages,
                denominationId = denomination,
                category = candidate.category ?: previous?.category,
                address = candidate.address,
                location = candidate.location,
                websiteUrl = candidate.websiteUrl,
                pages = previous?.pages.orEmpty().filterNot { websitePolicy.isExcluded(it.url) },
                socialProfiles = previous?.socialProfiles.orEmpty(),
                determinations = determinations,
                updatedAt = determinedAt,
            )
        }
        val nonGoogle = existing.filter { it.googleCid == null }.map { church ->
            church.copy(
                websiteUrl = websitePolicy.publicWebsiteUrl(church.websiteUrl, church.googleCid, church.id),
                pages = church.pages.filterNot { websitePolicy.isExcluded(it.url) },
            )
        }
        val pending = (fromGoogle + nonGoogle).sortedBy(ChurchRecord::id)
        val englishLlm = pending.count { church ->
            church.determinations.lastOrNull { it.field == "englishName" }?.source == DeterminationSource.LLM
        }
        stageDurations["assemble_catalog"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        stageMark = TimeSource.Monotonic.markNow()
        onStage("write_pending")
        atomicWrite(pendingCatalog(resourcesRoot, cacheRoot), json.encodeToString(pending))
        stageDurations["write_pending"] = stageMark.elapsedNow().toDouble(DurationUnit.SECONDS)
        return PreparationReport(
            rawCandidates = rawCandidates.size,
            exactDuplicatesMerged = rawCandidates.size - merged.size,
            nonGoogleRecordsRetained = nonGoogle.size,
            existingEvidenceReused = merged.count { it.googleCid in existingByCid },
            pendingChurches = pending.size,
            englishNamesProgrammatic = pending.size - englishLlm,
            englishNamesLlm = englishLlm,
            stageDurationsSeconds = stageDurations,
        )
    }

    private fun pendingCatalog(resourcesRoot: Path, cacheRoot: Path): Path =
        CrossmapPaths(resourcesRoot, cacheRoot).cleanup.resolve("google-saved-places-pending.json")

    private fun exactEntityKey(name: String, address: String): String =
        JapaneseEntityNormalizer.name(name) + "|" + JapaneseEntityNormalizer.address(address)

    private fun atomicWrite(destination: Path, content: String) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        runCatching {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private class PipelineProgress(private val pipeline: String) {
    private data class Stage(val name: String, val started: TimeMark)

    private val totalStarted = TimeSource.Monotonic.markNow()
    private val current = AtomicReference(Stage("starting", TimeSource.Monotonic.markNow()))
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crossmap-pipeline-progress").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleAtFixedRate(::heartbeat, 1, 1, TimeUnit.MINUTES)
    }

    fun start(name: String) {
        val previous = current.getAndSet(Stage(name, TimeSource.Monotonic.markNow()))
        println(
            "pipeline_progress event=stage_completed pipeline=$pipeline stage=${previous.name} " +
                "stage_seconds=${seconds(previous.started)} total_seconds=${seconds(totalStarted)}",
        )
    }

    fun complete() {
        val stage = current.get()
        println(
            "pipeline_progress event=pipeline_completed pipeline=$pipeline stage=${stage.name} " +
                "stage_seconds=${seconds(stage.started)} total_seconds=${seconds(totalStarted)}",
        )
        scheduler.shutdownNow()
    }

    private fun heartbeat() {
        val stage = current.get()
        println(
            "pipeline_progress event=heartbeat pipeline=$pipeline stage=${stage.name} " +
                "stage_seconds=${seconds(stage.started)} total_seconds=${seconds(totalStarted)}",
        )
    }

    private fun seconds(mark: TimeMark): String =
        "%.3f".format(Locale.ROOT, mark.elapsedNow().toDouble(DurationUnit.SECONDS))
}

private fun isReusablePublishedLatinName(value: String): Boolean {
    val normalized = value.trim()
    return normalized.count(Char::isLetter) >= 3 &&
        normalized.all { it.code < 128 || it == '’' } &&
        normalized.lowercase() !in setOf("church", "chapel", "mission", "assembly")
}
