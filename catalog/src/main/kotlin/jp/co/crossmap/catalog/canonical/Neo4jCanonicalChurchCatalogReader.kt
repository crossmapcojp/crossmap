package jp.co.crossmap.catalog.canonical

import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import jp.co.crossmap.catalog.neo4j.Neo4jStaticChurchCatalogSource

class Neo4jCanonicalChurchCatalogReader(
    private val transactions: GraphTransactionRunner,
) : CanonicalChurchCatalogReader, CatalogRevisionReader {
    override suspend fun readCommittedSnapshot(): CanonicalChurchCatalogSnapshot {
        val snapshot = Neo4jStaticChurchCatalogSource(transactions).read()
        return CanonicalChurchCatalogSnapshot(
            churches = snapshot.churches,
            revisionId = snapshot.catalogRevision,
            revisionSequence = snapshot.catalogRevisionSequence,
            contentHash = snapshot.catalogContentHash,
        ).also {
            check(CanonicalChurchCatalogHasher.contentHash(it.churches) == it.contentHash) {
                "Committed catalog content hash does not match its deterministic logical projection"
            }
        }
    }

    override suspend fun currentCommittedRevision(): CatalogRevision =
        readCommittedSnapshot().let { CatalogRevision(it.revisionId, it.revisionSequence, it.contentHash) }
}
