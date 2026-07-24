package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.crawl.CrossmapPaths
import jp.co.crossmap.crawl.DenominationCandidate
import jp.co.crossmap.crawl.DirectoryCrawlReport
import jp.co.crossmap.crawl.GoogleSavedPlaceSeed
import jp.co.crossmap.crawl.OfficialDirectoryCrawler
import kotlinx.serialization.json.Json

data class OfficialDenominationChurchListPipelineReport(
    val sources: Int,
    val pages: Int,
    val candidates: Int,
    val errors: Int,
    val excludedUrls: Int,
    val churchesByDenomination: Map<String, Int>,
    val cacheHits: Int,
    val reconciliation: OfficialDenominationReconciliationReport?,
)

class OfficialDenominationChurchListPipeline(
    private val runner: DenominationChurchListCrawlerRunner = DenominationChurchListCrawlerRunner(),
    private val genericCrawler: OfficialDirectoryCrawler = OfficialDirectoryCrawler(),
    private val reconciler: OfficialDenominationChurchListReconciler = OfficialDenominationChurchListReconciler(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    private val dedicatedCrawlers = listOf(
        UCCJDenominationChurchListCrawler(),
        JBCDenominationChurchListCrawler(),
        JBBFDenominationChurchListCrawler(),
        JACCDenominationChurchListCrawler(),
        JHCDenominationChurchListCrawler(),
        RCJDenominationChurchListCrawler(),
        IGMDenominationChurchListCrawler(),
        JAGDenominationChurchListCrawler(),
        JELCDenominationChurchListCrawler(),
        JCCDenominationChurchListCrawler(),
        SDAJPDenominationChurchListCrawler(),
        TLEADenominationChurchListCrawler(),
        HEJDenominationChurchListCrawler(),
        JECADenominationChurchListCrawler(),
        JCCJDenominationChurchListCrawler(),
        KCCJDenominationChurchListCrawler(),
        FGJADenominationChurchListCrawler(),
        COTNJPDenominationChurchListCrawler(),
        JBUDenominationChurchListCrawler(),
        TPKFDenominationChurchListCrawler(),
        BCCDenominationChurchListCrawler(),
        BGCJPDenominationChurchListCrawler(),
        SAJPDenominationChurchListCrawler(),
        JCBADenominationChurchListCrawler(),
        PCJDenominationChurchListCrawler(),
        EFCJPDenominationChurchListCrawler(),
        GECDenominationChurchListCrawler(),
    )

    fun run(
        resourcesRoot: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
        catalogFile: Path? = resourcesRoot.resolve("catalog/churches.json").takeIf(Files::isRegularFile),
        forceRefresh: Boolean = false,
        crawlGenericDirectories: Boolean = true,
        denominationIds: Set<String>? = null,
    ): OfficialDenominationChurchListPipelineReport {
        val selectedCrawlers = denominationIds?.let { ids ->
            val selected = dedicatedCrawlers.filter { it.denominationId in ids }
            require(selected.mapTo(linkedSetOf()) { it.denominationId } == ids) {
                "Unknown denomination crawler ids: ${ids - selected.mapTo(linkedSetOf()) { it.denominationId }}"
            }
            selected
        } ?: dedicatedCrawlers
        val results = selectedCrawlers.map { runner.crawl(it, resourcesRoot, cacheRoot, forceRefresh) }
        validateProductionLists(results.map(DenominationChurchListCrawlResult::list))
        replaceDedicatedCandidates(cacheRoot, results.map(DenominationChurchListCrawlResult::list))
        val generic = if (crawlGenericDirectories) {
            genericCrawler.crawl(resourcesRoot, cacheRoot, excludedDenominationIds = dedicatedCrawlers.mapTo(linkedSetOf()) { it.denominationId })
        } else {
            DirectoryCrawlReport(0, 0, 0, 0, 0)
        }
        val lists = results.map(DenominationChurchListCrawlResult::list)
        val googlePlaceTitles = loadGooglePlaceTitles(cacheRoot)
        val reconciliation = catalogFile?.takeIf(Files::isRegularFile)?.takeIf { denominationIds == null }?.let {
            reconciler.reconcile(it, lists, googlePlaceTitles)
        }
        return OfficialDenominationChurchListPipelineReport(
            sources = generic.sources + results.size,
            pages = generic.pages + results.sumOf(DenominationChurchListCrawlResult::pageCount),
            candidates = generic.candidates + lists.sumOf { list -> list.churches.count(OfficialDenominationChurch::eligibleForDenominationEvidence) },
            errors = generic.errors,
            excludedUrls = generic.excludedUrls,
            churchesByDenomination = lists.associate { it.denominationId to it.churches.size },
            cacheHits = results.count(DenominationChurchListCrawlResult::cacheHit),
            reconciliation = reconciliation,
        )
    }

    fun reconcileGeneratedLists(catalogFile: Path, resourcesRoot: Path): OfficialDenominationReconciliationReport {
        val lists = dedicatedCrawlers.map { crawler ->
            val file = resourcesRoot.resolve("crawl/${crawler.outputFileName}")
            require(Files.isRegularFile(file)) { "Missing generated official list: $file" }
            json.decodeFromString<OfficialDenominationChurchList>(Files.readString(file))
        }
        return reconciler.reconcile(
            catalogFile,
            lists,
            loadGooglePlaceTitles(CrossmapPaths.defaultCacheRoot(resourcesRoot)),
        )
    }

    private fun loadGooglePlaceTitles(cacheRoot: Path): Map<String, String> {
        val file = cacheRoot.resolve("google-saved-places/seeds.json")
        if (!Files.isRegularFile(file)) return emptyMap()
        return json.decodeFromString<List<GoogleSavedPlaceSeed>>(Files.readString(file))
            .associate { it.id to it.title }
    }

    private fun validateProductionLists(lists: List<OfficialDenominationChurchList>) {
        val minimums = mapOf(
            "UCCJ" to 1_500,
            "JBC" to 250,
            "JBBF" to 50,
            "JACC" to 100,
            "JHC" to 100,
            "RCJ" to 100,
            "IGM" to 50,
            "JAG" to 100,
            "JELC" to 100,
            "CCJ" to 70,
            "SDA_JP" to 150,
            "TLEA" to 100,
            "HEJ" to 90,
            "JECA" to 200,
            "JCCJ" to 120,
            "KCCJ" to 80,
            "FGJA" to 70,
            "COTN_JP" to 60,
            "JBU" to 60,
            "TPKF" to 50,
            "BCC" to 50,
            "BGC_JP" to 50,
            "SA_JP" to 30,
            "JCBA" to 70,
            "PCJ" to 60,
            "EFC_JP" to 80,
            "GEC" to 25,
        )
        lists.forEach { list ->
            require(list.churches.size >= minimums.getValue(list.denominationId)) {
                "${list.denominationId} official directory unexpectedly contained only ${list.churches.size} rows"
            }
        }
    }

    private fun replaceDedicatedCandidates(cacheRoot: Path, lists: List<OfficialDenominationChurchList>) {
        val file = cacheRoot.resolve("cleanup/denomination-candidates.json")
        val ids = lists.mapTo(hashSetOf(), OfficialDenominationChurchList::denominationId)
        val existing = if (Files.isRegularFile(file)) {
            json.decodeFromString<List<DenominationCandidate>>(Files.readString(file)).filterNot { it.denominationId in ids }
        } else {
            emptyList()
        }
        val dedicated = lists.flatMap { list ->
            list.churches.filter(OfficialDenominationChurch::eligibleForDenominationEvidence).map { church ->
                DenominationCandidate(
                    denominationId = list.denominationId,
                    churchName = church.name,
                    address = church.address,
                    url = church.websiteUrl,
                    source = list.sourceUrl,
                )
            }
        }
        val combined = (existing + dedicated)
            .distinctBy { Triple(it.denominationId, it.churchName, it.address) }
            .sortedWith(compareBy(DenominationCandidate::denominationId, DenominationCandidate::churchName, DenominationCandidate::address))
        Files.createDirectories(file.parent)
        atomicWrite(file, json.encodeToString(combined))
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}
