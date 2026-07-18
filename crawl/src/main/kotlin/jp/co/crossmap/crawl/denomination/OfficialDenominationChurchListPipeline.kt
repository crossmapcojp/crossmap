package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.crawl.CrossmapPaths
import jp.co.crossmap.crawl.DenominationCandidate
import jp.co.crossmap.crawl.DirectoryCrawlReport
import jp.co.crossmap.crawl.OfficialDirectoryCrawler
import kotlinx.serialization.json.Json

data class OfficialDenominationChurchListPipelineReport(
    val sources: Int,
    val pages: Int,
    val candidates: Int,
    val errors: Int,
    val excludedUrls: Int,
    val uccjChurches: Int,
    val jbcChurches: Int,
    val jbbfChurches: Int,
    val jaccChurches: Int,
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
    )

    fun run(
        resourcesRoot: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
        catalogFile: Path? = resourcesRoot.resolve("catalog/churches.json").takeIf(Files::isRegularFile),
        forceRefresh: Boolean = false,
        crawlGenericDirectories: Boolean = true,
    ): OfficialDenominationChurchListPipelineReport {
        val results = dedicatedCrawlers.map { runner.crawl(it, resourcesRoot, cacheRoot, forceRefresh) }
        validateProductionLists(results.map(DenominationChurchListCrawlResult::list))
        replaceDedicatedCandidates(cacheRoot, results.map(DenominationChurchListCrawlResult::list))
        val generic = if (crawlGenericDirectories) {
            genericCrawler.crawl(resourcesRoot, cacheRoot, excludedDenominationIds = dedicatedCrawlers.mapTo(linkedSetOf()) { it.denominationId })
        } else {
            DirectoryCrawlReport(0, 0, 0, 0, 0)
        }
        val lists = results.map(DenominationChurchListCrawlResult::list)
        val reconciliation = catalogFile?.takeIf(Files::isRegularFile)?.let { reconciler.reconcile(it, lists) }
        val uccj = lists.single { it.denominationId == "UCCJ" }
        val jbc = lists.single { it.denominationId == "JBC" }
        val jbbf = lists.single { it.denominationId == "JBBF" }
        val jacc = lists.single { it.denominationId == "JACC" }
        return OfficialDenominationChurchListPipelineReport(
            sources = generic.sources + results.size,
            pages = generic.pages + results.size,
            candidates = generic.candidates + lists.sumOf { list -> list.churches.count(OfficialDenominationChurch::eligibleForDenominationEvidence) },
            errors = generic.errors,
            excludedUrls = generic.excludedUrls,
            uccjChurches = uccj.churches.size,
            jbcChurches = jbc.churches.size,
            jbbfChurches = jbbf.churches.size,
            jaccChurches = jacc.churches.size,
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
        return reconciler.reconcile(catalogFile, lists)
    }

    private fun validateProductionLists(lists: List<OfficialDenominationChurchList>) {
        val minimums = mapOf("UCCJ" to 1_500, "JBC" to 250, "JBBF" to 50, "JACC" to 100)
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
