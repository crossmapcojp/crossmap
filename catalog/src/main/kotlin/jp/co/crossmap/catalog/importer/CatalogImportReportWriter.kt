package jp.co.crossmap.catalog.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CatalogImportReportPaths(val json: Path, val markdown: Path)

class CatalogImportReportWriter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun write(report: CatalogImportReport, directory: Path): CatalogImportReportPaths {
        Files.createDirectories(directory)
        val jsonPath = directory.resolve("report.json")
        val markdownPath = directory.resolve("report.md")
        writeAtomically(jsonPath, json.encodeToString(report))
        writeAtomically(markdownPath, markdown(report))
        return CatalogImportReportPaths(jsonPath, markdownPath)
    }

    private fun markdown(report: CatalogImportReport): String = buildString {
        appendLine("# Crossmap catalog import report")
        appendLine()
        appendLine("- Source: `${report.sourcePath}`")
        appendLine("- SHA-256: `${report.sourceChecksum}`")
        appendLine("- Mode: ${if (report.dryRun) "dry run" else "import"}")
        appendLine("- Database: `${report.database}`")
        appendLine("- Schema version: ${report.schemaVersion}")
        appendLine("- Batches: ${report.batches}")
        appendLine("- Duration: ${report.durationMillis} ms")
        appendLine("- Rejected records: ${report.rejectedRecords.size}")
        appendLine("- Duplicate collapses: ${report.duplicateCollapses}")
        appendLine()
        appendLine("## Entity counts")
        report.entityCounts.forEach { (name, count) -> appendLine("- $name: $count") }
        appendLine()
        appendLine("## Relationship counts")
        report.relationshipCounts.forEach { (name, count) -> appendLine("- $name: $count") }
        if (report.warnings.isNotEmpty()) {
            appendLine()
            appendLine("## Warnings")
            report.warnings.forEach { appendLine("- $it") }
        }
        if (report.rejectedRecords.isNotEmpty()) {
            appendLine()
            appendLine("## Rejected records")
            report.rejectedRecords.forEach { appendLine("- index=${it.recordIndex}, id=${it.id ?: "unknown"}: ${it.reason}") }
        }
    }

    private fun writeAtomically(path: Path, content: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
