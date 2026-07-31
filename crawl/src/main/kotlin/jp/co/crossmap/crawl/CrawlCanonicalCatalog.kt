package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.catalog.canonical.CanonicalChurchCatalogSnapshot
import jp.co.crossmap.catalog.canonical.CatalogCommitResult
import jp.co.crossmap.catalog.canonical.CatalogOperationMetadata
import jp.co.crossmap.catalog.canonical.CatalogRevisionToken
import jp.co.crossmap.catalog.canonical.Neo4jCanonicalChurchCatalogReader
import jp.co.crossmap.catalog.canonical.Neo4jCanonicalChurchCatalogWriter
import jp.co.crossmap.catalog.neo4j.CatalogSchemaMigrator
import jp.co.crossmap.catalog.neo4j.Neo4jConfig
import jp.co.crossmap.catalog.neo4j.Neo4jDriverManager
import jp.co.crossmap.catalog.neo4j.Neo4jGraphTransactionRunner

interface CrawlCanonicalCatalogGateway {
    suspend fun readCommittedSnapshot(): CanonicalChurchCatalogSnapshot
    suspend fun replace(
        expectedRevision: CatalogRevisionToken,
        churches: List<ChurchRecord>,
        operation: CatalogOperationMetadata,
    ): CatalogCommitResult
}

object CrawlCanonicalCatalog : CrawlCanonicalCatalogGateway {
    override suspend fun readCommittedSnapshot(): CanonicalChurchCatalogSnapshot = withCatalog { manager, transactions ->
        requireReady(manager)
        Neo4jCanonicalChurchCatalogReader(transactions).readCommittedSnapshot()
    }

    override suspend fun replace(
        expectedRevision: CatalogRevisionToken,
        churches: List<ChurchRecord>,
        operation: CatalogOperationMetadata,
    ): CatalogCommitResult = withCatalog { manager, transactions ->
        requireReady(manager)
        Neo4jCanonicalChurchCatalogWriter(
            transactions = transactions,
            database = manager.config.database,
            schemaVersion = CatalogSchemaMigrator.EXPECTED_VERSION,
        ).replaceChurchCatalog(expectedRevision, churches, operation)
    }

    private suspend fun <T> withCatalog(
        block: suspend (Neo4jDriverManager, Neo4jGraphTransactionRunner) -> T,
    ): T = Neo4jDriverManager(Neo4jConfig.fromEnvironmentAndLocalProperties()).use { manager ->
        block(manager, Neo4jGraphTransactionRunner(manager.driver, manager.config.database))
    }

    private suspend fun requireReady(manager: Neo4jDriverManager) {
        val health = manager.health(CatalogSchemaMigrator.EXPECTED_VERSION)
        check(
            health.reachable &&
                health.schemaVersion == CatalogSchemaMigrator.EXPECTED_VERSION &&
                health.catalogImported
        ) { "Neo4j canonical catalog is not ready: $health" }
    }
}
