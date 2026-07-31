package jp.co.crossmap.catalog.canonical

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.catalog.importer.LegacyJsonChurchCatalogSource
import jp.co.crossmap.catalog.importer.Neo4jCatalogImporter
import jp.co.crossmap.catalog.importer.NormalizedCatalogImport
import jp.co.crossmap.catalog.importer.SourceMetadata
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner

class Neo4jCanonicalChurchCatalogWriter(
    private val transactions: GraphTransactionRunner,
    private val database: String,
    private val schemaVersion: Int,
    private val batchSize: Int = 250,
    private val normalizer: LegacyJsonChurchCatalogSource = LegacyJsonChurchCatalogSource(),
) : CanonicalChurchCatalogWriter {
    override suspend fun replaceChurchCatalog(
        expectedRevision: CatalogRevisionToken?,
        churches: List<ChurchRecord>,
        operation: CatalogOperationMetadata,
    ): CatalogCommitResult {
        require(churches.map(ChurchRecord::id).distinct().size == churches.size) {
            "Canonical replacement contains duplicate church IDs"
        }
        val normalizedChurches = CanonicalChurchCatalogHasher.normalized(churches)
        val contentHash = CanonicalChurchCatalogHasher.contentHash(normalizedChurches)
        val warnings = mutableListOf<String>()
        val records = normalizedChurches.mapIndexed { index, church ->
            normalizer.normalize(
                church,
                SourceMetadata(operation.source ?: operation.operation, contentHash, index),
                warnings,
            )
        }
        val priorHash = currentRevisionOrNull()?.contentHash
        val report = Neo4jCatalogImporter(
            transactions = transactions,
            database = database,
            schemaVersion = schemaVersion,
            batchSize = batchSize,
        ).import(
            catalog = NormalizedCatalogImport(
                sourcePath = operation.source ?: operation.operation,
                sourceChecksum = contentHash,
                records = records,
                rejectedRecords = emptyList(),
                warnings = warnings,
                duplicateCollapses = 0,
            ),
            expectedRevision = expectedRevision,
            operation = operation,
        )
        val revision = CatalogRevision(
            revisionId = requireNotNull(report.revisionId) { "Catalog replacement produced no revision ID" },
            revisionSequence = requireNotNull(report.revisionSequence) { "Catalog replacement produced no revision sequence" },
            contentHash = report.contentHash,
        )
        return CatalogCommitResult(revision, changed = priorHash != contentHash)
    }

    private suspend fun currentRevisionOrNull(): CatalogRevision? = transactions.read("canonical-catalog.current-revision") { runner ->
        runner.query(
            """
            MATCH (state:CatalogState {name: 'catalog'})-[:CURRENT_REVISION]->(revision:CatalogRevision {status: 'COMMITTED'})
            RETURN revision.id AS revisionId,
                   revision.sequence AS revisionSequence,
                   revision.contentHash AS contentHash
            """.trimIndent(),
        ).singleOrNull()
    }?.let { row ->
        CatalogRevision(
            revisionId = row.getValue("revisionId").toString(),
            revisionSequence = (row.getValue("revisionSequence") as Number).toLong(),
            contentHash = row.getValue("contentHash").toString(),
        )
    }
}
