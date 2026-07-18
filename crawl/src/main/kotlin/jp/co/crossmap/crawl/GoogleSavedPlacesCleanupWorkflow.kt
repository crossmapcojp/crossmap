package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.LocalizedName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val finalChurches: Int,
    val englishNamesProgrammatic: Int,
    val englishNamesLlm: Int,
    val promoted: Boolean,
)

/**
 * Connects the standalone Google Saved Places source to the existing Crossmap cleanup pipeline.
 * The canonical catalog is replaced only after denomination cleanup and mandatory English naming succeed.
 */
class GoogleSavedPlacesCleanupWorkflow(
    private val postCrawlCleanup: PostCrawlCleanup,
    private val englishNameResolver: ChurchEnglishNameResolver,
    private val websiteRefresher: WebsiteRefresher = WebsiteRefresher(),
    private val directoryCrawler: OfficialDirectoryCrawler = OfficialDirectoryCrawler(),
    private val now: () -> String = { Instant.now().toString() },
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    suspend fun run(
        resourcesRoot: Path,
        llmLimit: Int = Int.MAX_VALUE,
        enableLlm: Boolean = true,
        refreshWebsites: Boolean = true,
        crawlDirectories: Boolean = true,
        promote: Boolean = true,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): GoogleSavedPlacesPromotionReport {
        val prepared = preparePendingCatalog(resourcesRoot, cacheRoot)
        val staging = pendingCatalog(resourcesRoot, cacheRoot)
        val website = if (refreshWebsites) websiteRefresher.refresh(resourcesRoot, staging, cacheRoot) else null
        val directory = if (crawlDirectories) directoryCrawler.crawl(resourcesRoot, cacheRoot) else null
        val cleanup = postCrawlCleanup.run(
            resourcesRoot = resourcesRoot,
            limit = llmLimit,
            applyChanges = true,
            enableLlm = enableLlm,
            catalogFile = staging,
            cacheRoot = cacheRoot,
        )
        val completed = json.decodeFromString<List<ChurchRecord>>(Files.readString(staging))
        require(completed.all { it.englishName.isNotBlank() }) { "Pending catalog contains blank English names" }

        if (promote) {
            atomicWrite(resourcesRoot.resolve("catalog/churches.json"), json.encodeToString(completed))
        }
        return GoogleSavedPlacesPromotionReport(
            rawCandidates = prepared.rawCandidates,
            exactDuplicatesMerged = prepared.exactDuplicatesMerged,
            nonGoogleRecordsRetained = prepared.nonGoogleRecordsRetained,
            existingEvidenceReused = prepared.existingEvidenceReused,
            websiteFetched = website?.fetched ?: 0,
            websiteErrors = website?.errors ?: 0,
            directoryCandidates = directory?.candidates ?: 0,
            denominationProgrammatic = cleanup.programmaticAccepted,
            denominationLlm = cleanup.llmAccepted,
            denominationHuman = cleanup.humanOverrides,
            denominationUncertain = cleanup.uncertain,
            finalChurches = completed.size,
            englishNamesProgrammatic = prepared.englishNamesProgrammatic,
            englishNamesLlm = prepared.englishNamesLlm,
            promoted = promote,
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
    )

    suspend fun preparePendingCatalog(
        resourcesRoot: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
    ): PreparationReport {
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
        val normalized = rawCandidates.map { candidate ->
            candidate.copy(
                name = normalizeChurchName(candidate.name),
                localizedNames = (
                    candidate.localizedNames + reviewedNameReadings[candidate.id].orEmpty()
                        .map { LocalizedName("ja", it) }
                    ).map { localizedName ->
                    localizedName.copy(name = normalizeChurchName(localizedName.name))
                }.filter { it.name.isNotBlank() }.distinctBy { it.languageCode to it.name },
                address = candidate.address.replace(Regex("""\s+"""), " ").trim(),
                websiteUrl = websitePolicy.publicWebsiteUrl(candidate.websiteUrl, candidate.googleCid, candidate.id),
            )
        }
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
        atomicWrite(pendingCatalog(resourcesRoot, cacheRoot), json.encodeToString(pending))
        return PreparationReport(
            rawCandidates = rawCandidates.size,
            exactDuplicatesMerged = rawCandidates.size - merged.size,
            nonGoogleRecordsRetained = nonGoogle.size,
            existingEvidenceReused = merged.count { it.googleCid in existingByCid },
            pendingChurches = pending.size,
            englishNamesProgrammatic = pending.size - englishLlm,
            englishNamesLlm = englishLlm,
        )
    }

    private fun pendingCatalog(resourcesRoot: Path, cacheRoot: Path): Path =
        CrossmapPaths(resourcesRoot, cacheRoot).cleanup.resolve("google-saved-places-pending.json")

    private fun normalizeChurchName(value: String): String = ChurchPublicNameNormalizer.normalize(value)

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

private fun isReusablePublishedLatinName(value: String): Boolean {
    val normalized = value.trim()
    return normalized.count(Char::isLetter) >= 3 &&
        normalized.all { it.code < 128 || it == '’' } &&
        normalized.lowercase() !in setOf("church", "chapel", "mission", "assembly")
}
