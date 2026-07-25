package jp.co.crossmap.catalog.integrity

import jp.co.crossmap.catalog.neo4j.GraphQueryRunner
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class CatalogIntegrityServiceTest {
    @Test
    fun reportsViolationWithoutMutatingDatabase() = runBlocking {
        val transactions = IntegrityTransactions()
        val report = CatalogIntegrityService(transactions).inspect()
        assertFalse(report.passed)
        assertEquals(1L, report.checks.first().violations)
        assertEquals(12, transactions.reads.size)
    }
}

private class IntegrityTransactions : GraphTransactionRunner {
    val reads = mutableListOf<String>()
    override suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T {
        reads += queryName
        return block(object : GraphQueryRunner {
            override fun query(cypher: String, parameters: Map<String, Any?>): List<Map<String, Any?>> = when {
                "UNWIND labels" in cypher -> listOf(mapOf("label" to "Church", "count" to 1L))
                "type(relationship)" in cypher -> listOf(mapOf("type" to "LOCATED_AT", "count" to 1L))
                else -> listOf(mapOf("violations" to 1L, "sampleIds" to listOf("church:1")))
            }
        })
    }
    override suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T = error("Integrity checks must be read-only")
}
