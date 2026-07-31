package jp.co.crossmap.crawl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Path
import jp.co.crossmap.catalog.export.CatalogLogicalExporter
import jp.co.crossmap.catalog.export.CatalogParityReportWriter
import jp.co.crossmap.catalog.export.CatalogParityValidator
import jp.co.crossmap.catalog.importer.CatalogImportReportWriter
import jp.co.crossmap.catalog.importer.LegacyJsonChurchCatalogSource
import jp.co.crossmap.catalog.importer.Neo4jCatalogImporter
import jp.co.crossmap.catalog.integrity.CatalogIntegrityReportWriter
import jp.co.crossmap.catalog.integrity.CatalogIntegrityService
import jp.co.crossmap.catalog.neo4j.CatalogSchemaMigrator
import jp.co.crossmap.catalog.neo4j.GraphQueryRunner
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import jp.co.crossmap.catalog.neo4j.Neo4jConfig
import jp.co.crossmap.catalog.neo4j.Neo4jDriverManager
import jp.co.crossmap.catalog.neo4j.Neo4jGraphTransactionRunner
import jp.co.crossmap.catalog.neo4j.Neo4jStaticChurchCatalogSource
import kotlinx.coroutines.runBlocking

internal class CatalogNeo4jStatus : CliktCommand(name = "catalog-neo4j-status") {
    override fun run() = runBlocking {
        withNeo4j { manager, _ ->
            val health = manager.health(CatalogSchemaMigrator.EXPECTED_VERSION)
            echo(
                "Neo4j reachable=${health.reachable} schemaVersion=${health.schemaVersion ?: "none"} " +
                    "expectedSchemaVersion=${CatalogSchemaMigrator.EXPECTED_VERSION} catalogImported=${health.catalogImported} " +
                    "catalogRevision=${health.catalogRevision ?: "none"} catalogContentHash=${health.catalogContentHash ?: "none"}",
            )
        }
    }
}

internal class CatalogNeo4jMigrate : CliktCommand(name = "catalog-neo4j-migrate") {
    override fun run() = runBlocking {
        withNeo4j { manager, transactions ->
            val result = CatalogSchemaMigrator(transactions).migrate()
            val health = manager.health(CatalogSchemaMigrator.EXPECTED_VERSION)
            check(health.schemaVersion == CatalogSchemaMigrator.EXPECTED_VERSION) {
                "Catalog schema migration did not install expected version ${CatalogSchemaMigrator.EXPECTED_VERSION}"
            }
            echo("Catalog schema version=${result.version} newlyApplied=${result.appliedVersions.ifEmpty { listOf("none") }}")
        }
    }
}

internal class CatalogNeo4jBootstrapFromLegacyJson : CliktCommand(name = "catalog-neo4j-bootstrap-from-legacy-json") {
    private val input by option("--input", help = "Legacy churches.json bootstrap input; never used after bootstrap").required()
    private val reportDirectory by option("--report-directory").default("build/reports/catalog-import")
    private val batchSize by option("--batch-size").int().default(250)
    private val dryRun by option("--dry-run").flag()

    override fun run() = runBlocking {
        val normalized = LegacyJsonChurchCatalogSource().read(Path.of(input))
        val report = if (dryRun) {
            Neo4jCatalogImporter(
                transactions = RejectingTransactions,
                database = "not-connected-dry-run",
                schemaVersion = CatalogSchemaMigrator.EXPECTED_VERSION,
                batchSize = batchSize,
            ).import(normalized, dryRun = true)
        } else {
            withNeo4j { manager, transactions ->
                CatalogSchemaMigrator(transactions).migrate()
                val report = Neo4jCatalogImporter(
                    transactions = transactions,
                    database = manager.config.database,
                    schemaVersion = CatalogSchemaMigrator.EXPECTED_VERSION,
                    batchSize = batchSize,
                ).import(normalized)
                check(manager.health(CatalogSchemaMigrator.EXPECTED_VERSION).catalogImported) {
                    "Import completed but catalog health did not report imported"
                }
                report
            }
        }
        val paths = CatalogImportReportWriter().write(report, Path.of(reportDirectory))
        echo(
            "Catalog import dryRun=${report.dryRun} churches=${report.entityCounts.getValue("Church")} " +
                "rejected=${report.rejectedRecords.size} json=${paths.json} markdown=${paths.markdown}",
        )
    }
}

internal class CatalogNeo4jExportChurchProjection : CliktCommand(name = "catalog-neo4j-export-church-projection") {
    private val output by option("--output").default("build/reports/catalog-export/churches.json")

    override fun run() = runBlocking {
        val result = withNeo4j { _, transactions ->
            val snapshot = Neo4jStaticChurchCatalogSource(transactions).read()
            CatalogLogicalExporter().write(snapshot, Path.of(output))
        }
        echo(
            "Catalog projection export churches=${result.churchCount} sourceChecksum=${result.sourceChecksum} " +
                "output=${result.output} manifest=${result.manifest}",
        )
    }
}

internal class CatalogNeo4jParity : CliktCommand(name = "catalog-neo4j-parity") {
    private val input by option("--input").required()
    private val reportDirectory by option("--report-directory").default("build/reports/catalog-parity")

    override fun run() = runBlocking {
        val legacy = LegacyJsonChurchCatalogSource().read(Path.of(input))
        val report = withNeo4j { _, transactions ->
            CatalogParityValidator().compare(legacy, Neo4jStaticChurchCatalogSource(transactions).read())
        }
        val paths = CatalogParityReportWriter().write(report, Path.of(reportDirectory))
        echo(
            "Catalog parity matches=${report.matches} matching=${report.matchingCount} missing=${report.missingIds.size} " +
                "extra=${report.extraIds.size} mismatched=${report.mismatchedIds.size} json=${paths.json} markdown=${paths.markdown}",
        )
        check(report.matches) { "Neo4j catalog does not match the normalized legacy catalog" }
    }
}

internal class CatalogNeo4jIntegrity : CliktCommand(name = "catalog-neo4j-integrity") {
    private val reportDirectory by option("--report-directory").default("build/reports/catalog-integrity")

    override fun run() = runBlocking {
        val report = withNeo4j { _, transactions -> CatalogIntegrityService(transactions).inspect() }
        val paths = CatalogIntegrityReportWriter().write(report, Path.of(reportDirectory))
        echo("Catalog integrity passed=${report.passed} checks=${report.checks.size} json=${paths.json} markdown=${paths.markdown}")
        check(report.passed) { "Neo4j catalog integrity checks failed" }
    }
}

private suspend fun <T> withNeo4j(block: suspend (Neo4jDriverManager, GraphTransactionRunner) -> T): T {
    val manager = Neo4jDriverManager(Neo4jConfig.fromEnvironmentAndLocalProperties())
    return manager.use {
        manager.verifyConnectivity()
        block(manager, Neo4jGraphTransactionRunner(manager.driver, manager.config.database))
    }
}

private object RejectingTransactions : GraphTransactionRunner {
    override suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T =
        error("Dry-run import attempted a database read: $queryName")

    override suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T =
        error("Dry-run import attempted a database write: $queryName")
}
