package jp.co.crossmap.catalog.integrity

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CatalogIntegrityCheck(
    val name: String,
    val violations: Long,
    val sampleIds: List<String>,
) {
    val passed: Boolean get() = violations == 0L
}

@Serializable
data class CatalogIntegrityReport(
    val checks: List<CatalogIntegrityCheck>,
    val entityCounts: Map<String, Long>,
    val relationshipCounts: Map<String, Long>,
) {
    val passed: Boolean get() = checks.all(CatalogIntegrityCheck::passed)
}

class CatalogIntegrityService(private val transactions: GraphTransactionRunner) {
    suspend fun inspect(): CatalogIntegrityReport {
        val entityCounts = transactions.read("catalog-integrity.entity-counts") { runner ->
            runner.query(
                """
                MATCH (node)
                UNWIND labels(node) AS label
                RETURN label, count(*) AS count
                ORDER BY label
                """.trimIndent(),
            ).associate { it.getValue("label").toString() to (it.getValue("count") as Number).toLong() }
        }
        val relationshipCounts = transactions.read("catalog-integrity.relationship-counts") { runner ->
            runner.query(
                """
                MATCH ()-[relationship]->()
                RETURN type(relationship) AS type, count(*) AS count
                ORDER BY type
                """.trimIndent(),
            ).associate { it.getValue("type").toString() to (it.getValue("count") as Number).toLong() }
        }
        val checks = CHECKS.map { definition ->
            transactions.read("catalog-integrity.${definition.name}") { runner ->
                val row = runner.query(definition.cypher).single()
                CatalogIntegrityCheck(
                    name = definition.name,
                    violations = (row.getValue("violations") as Number).toLong(),
                    sampleIds = (row["sampleIds"] as? List<*>)?.map(Any?::toString).orEmpty(),
                )
            }
        }
        return CatalogIntegrityReport(checks, entityCounts, relationshipCounts)
    }

    private data class CheckDefinition(val name: String, val cypher: String)

    companion object {
        private val CHECKS = listOf(
            CheckDefinition(
                "missing-stable-ids",
                "MATCH (node) WHERE any(label IN labels(node) WHERE label IN ['Church','Denomination','Location','Website','Webpage','SocialMediaAccount','Person','RoleEvent','SourceRecord','ImportRun']) AND (node.id IS NULL OR trim(toString(node.id)) = '') WITH node LIMIT 10000 RETURN count(node) AS violations, collect(coalesce(node.id, '<missing>'))[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "duplicate-church-ids",
                "MATCH (church:Church) WITH church.id AS id, count(*) AS copies WHERE copies > 1 RETURN coalesce(sum(copies - 1), 0) AS violations, collect(id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "missing-church-names",
                "MATCH (church:Church) WHERE church.primaryName IS NULL OR trim(church.primaryName) = '' OR church.englishName IS NULL OR trim(church.englishName) = '' RETURN count(church) AS violations, collect(church.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "invalid-coordinates",
                "MATCH (church:Church)-[:LOCATED_AT]->(location:Location) WHERE location.latitude < -90 OR location.latitude > 90 OR location.longitude < -180 OR location.longitude > 180 RETURN count(church) AS violations, collect(church.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "multiple-denominations",
                "MATCH (church:Church)-[:BELONGS_TO_DENOMINATION]->(:Denomination) WITH church, count(*) AS memberships WHERE memberships > 1 RETURN count(church) AS violations, collect(church.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "orphan-websites",
                "MATCH (website:Website) WHERE NOT (:Church)-[:HAS_WEBSITE]->(website) RETURN count(website) AS violations, collect(website.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "orphan-webpages",
                "MATCH (page:Webpage) WHERE NOT (:Website)-[:HAS_PAGE]->(page) RETURN count(page) AS violations, collect(page.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "duplicate-normalized-website-urls",
                "MATCH (website:Website) WHERE website.normalizedUrl IS NOT NULL WITH website.normalizedUrl AS url, count(*) AS copies WHERE copies > 1 RETURN coalesce(sum(copies - 1), 0) AS violations, collect(url)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "missing-location",
                "MATCH (church:Church) WHERE NOT (church)-[:LOCATED_AT]->(:Location) RETURN count(church) AS violations, collect(church.id)[0..20] AS sampleIds",
            ),
            CheckDefinition(
                "missing-import-provenance",
                "MATCH (church:Church) WHERE NOT (church)-[:IMPORTED_FROM]->(:SourceRecord)<-[:IMPORTED]-(:ImportRun {status:'COMPLETED'}) RETURN count(church) AS violations, collect(church.id)[0..20] AS sampleIds",
            ),
        )
    }
}

data class CatalogIntegrityReportPaths(val json: Path, val markdown: Path)

class CatalogIntegrityReportWriter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun write(report: CatalogIntegrityReport, directory: Path): CatalogIntegrityReportPaths {
        Files.createDirectories(directory)
        val jsonPath = directory.resolve("report.json")
        val markdownPath = directory.resolve("report.md")
        atomicWrite(jsonPath, json.encodeToString(report))
        atomicWrite(markdownPath, buildString {
            appendLine("# Crossmap catalog integrity report")
            appendLine()
            appendLine("- Passed: ${report.passed}")
            appendLine()
            appendLine("## Checks")
            report.checks.forEach { appendLine("- ${it.name}: ${if (it.passed) "PASS" else "FAIL (${it.violations})"}") }
            appendLine()
            appendLine("## Entity counts")
            report.entityCounts.forEach { (name, count) -> appendLine("- $name: $count") }
            appendLine()
            appendLine("## Relationship counts")
            report.relationshipCounts.forEach { (name, count) -> appendLine("- $name: $count") }
        })
        return CatalogIntegrityReportPaths(jsonPath, markdownPath)
    }

    private fun atomicWrite(path: Path, content: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
