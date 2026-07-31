package jp.co.crossmap.catalog.export

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.catalog.importer.ChurchImportRecord
import jp.co.crossmap.catalog.importer.LegacyJsonChurchCatalogSource
import jp.co.crossmap.catalog.importer.NormalizedCatalogImport
import jp.co.crossmap.catalog.importer.SourceMetadata
import jp.co.crossmap.catalog.neo4j.StaticChurchCatalogSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CatalogLogicalExportResult(
    val output: Path,
    val manifest: Path,
    val churchCount: Int,
    val sourceChecksum: String,
)

@Serializable
data class CatalogLogicalExportManifest(
    val catalogRevisionId: String,
    val catalogRevisionSequence: Long,
    val catalogContentHash: String,
    val generatedAt: String,
    val churchCount: Int,
    val fileSha256: String,
)

class CatalogLogicalExporter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun write(snapshot: StaticChurchCatalogSnapshot, output: Path): CatalogLogicalExportResult {
        output.parent?.let(Files::createDirectories)
        val churches = snapshot.churches.sortedBy(ChurchRecord::id)
        val content = json.encodeToString(churches)
        val temporary = Files.createTempFile(output.parent ?: Path.of("."), ".${output.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val manifest = output.resolveSibling("${output.fileName}.manifest.json")
        val manifestContent = json.encodeToString(
            CatalogLogicalExportManifest(
                catalogRevisionId = snapshot.catalogRevision,
                catalogRevisionSequence = snapshot.catalogRevisionSequence,
                catalogContentHash = snapshot.catalogContentHash,
                generatedAt = Instant.now().toString(),
                churchCount = churches.size,
                fileSha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                    .joinToString("") { "%02x".format(it) },
            ),
        )
        val temporaryManifest = Files.createTempFile(manifest.parent, ".${manifest.fileName}-", ".tmp")
        Files.writeString(temporaryManifest, manifestContent)
        Files.move(temporaryManifest, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return CatalogLogicalExportResult(output, manifest, churches.size, snapshot.sourceChecksum)
    }
}

@Serializable
data class CatalogParityReport(
    val sourceChecksum: String,
    val legacyCount: Int,
    val neo4jCount: Int,
    val matchingCount: Int,
    val missingIds: List<String>,
    val extraIds: List<String>,
    val mismatchedIds: List<String>,
    val mismatchFields: Map<String, List<String>>,
    val projectionWarnings: List<String>,
) {
    val matches: Boolean get() = missingIds.isEmpty() && extraIds.isEmpty() && mismatchedIds.isEmpty()
}

class CatalogParityValidator(
    private val normalizer: LegacyJsonChurchCatalogSource = LegacyJsonChurchCatalogSource(),
) {
    fun compare(legacy: NormalizedCatalogImport, snapshot: StaticChurchCatalogSnapshot): CatalogParityReport {
        val warnings = mutableListOf<String>()
        val legacyById = legacy.records.associateBy { it.id.value }
        val graphById = snapshot.churches.mapIndexed { index, church ->
            church.id to normalizer.normalize(
                church,
                SourceMetadata("neo4j-logical-export", snapshot.sourceChecksum, index),
                warnings,
            )
        }.toMap()
        val missing = (legacyById.keys - graphById.keys).sorted()
        val extra = (graphById.keys - legacyById.keys).sorted()
        val common = legacyById.keys.intersect(graphById.keys)
        val mismatchFields = common.mapNotNull { id ->
            val fields = differingFields(legacyById.getValue(id), graphById.getValue(id))
            fields.takeIf(List<String>::isNotEmpty)?.let { id to it }
        }.toMap().toSortedMap()
        val mismatched = mismatchFields.keys.toList()
        return CatalogParityReport(
            sourceChecksum = snapshot.sourceChecksum,
            legacyCount = legacyById.size,
            neo4jCount = graphById.size,
            matchingCount = common.size - mismatched.size,
            missingIds = missing,
            extraIds = extra,
            mismatchedIds = mismatched,
            mismatchFields = mismatchFields,
            projectionWarnings = warnings,
        )
    }
}

data class CatalogParityReportPaths(val json: Path, val markdown: Path)

class CatalogParityReportWriter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun write(report: CatalogParityReport, directory: Path): CatalogParityReportPaths {
        Files.createDirectories(directory)
        val jsonPath = directory.resolve("report.json")
        val markdownPath = directory.resolve("report.md")
        atomicWrite(jsonPath, json.encodeToString(report))
        atomicWrite(markdownPath, buildString {
            appendLine("# Crossmap catalog parity report")
            appendLine()
            appendLine("- Matches: ${report.matches}")
            appendLine("- Source SHA-256: `${report.sourceChecksum}`")
            appendLine("- Legacy churches: ${report.legacyCount}")
            appendLine("- Neo4j churches: ${report.neo4jCount}")
            appendLine("- Matching churches: ${report.matchingCount}")
            appendLine("- Missing IDs: ${report.missingIds.size}")
            appendLine("- Extra IDs: ${report.extraIds.size}")
            appendLine("- Mismatched IDs: ${report.mismatchedIds.size}")
            if (report.mismatchedIds.isNotEmpty()) {
                appendLine()
                appendLine("## Mismatched IDs")
                report.mismatchedIds.forEach { appendLine("- `$it`: ${report.mismatchFields.getValue(it).joinToString()}") }
            }
        })
        return CatalogParityReportPaths(jsonPath, markdownPath)
    }

    private fun atomicWrite(path: Path, content: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

private fun ChurchImportRecord.canonical(): ChurchImportRecord = copy(source = SourceMetadata("", "", 0))

private fun differingFields(left: ChurchImportRecord, right: ChurchImportRecord): List<String> = buildList {
    if (left.id != right.id) add("id")
    if (left.googlePlaceId != right.googlePlaceId) add("googlePlaceId")
    if (left.names != right.names) add("names")
    if (left.primaryName != right.primaryName) add("primaryName")
    if (left.englishName != right.englishName) add("englishName")
    if (left.titleLanguages != right.titleLanguages) add("titleLanguages")
    if (left.denomination != right.denomination) add("denomination")
    if (left.category != right.category) add("category")
    if (left.address != right.address) add("address")
    if (left.latitude != right.latitude) add("latitude")
    if (left.longitude != right.longitude) add("longitude")
    if (left.website?.id != right.website?.id) add("website.id")
    if (left.website?.url != right.website?.url) add("website.url")
    if (left.website?.normalizedUrl != right.website?.normalizedUrl) add("website.normalizedUrl")
    if (left.website?.pages != right.website?.pages) add("website.pages")
    if (left.email != right.email) add("email")
    if (left.socialAccounts != right.socialAccounts) add("socialAccounts")
    if (left.ministers != right.ministers) add("ministers")
    if (left.determinations != right.determinations) add("determinations")
    if (left.updatedAt != right.updatedAt) add("updatedAt")
}
