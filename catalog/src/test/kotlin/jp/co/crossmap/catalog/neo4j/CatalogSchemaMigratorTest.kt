package jp.co.crossmap.catalog.neo4j

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CatalogSchemaMigratorTest {
    @Test
    fun appliesOrderedMigrationAndRecordsIt() = runBlocking {
        val transactions = RecordingTransactions()
        val migrator = migrator(transactions)

        val result = migrator.migrate()

        assertEquals(1, result.version)
        assertEquals(listOf(1), result.appliedVersions)
        assertTrue(transactions.writes.any { it.startsWith("CREATE CONSTRAINT") })
        assertTrue(transactions.writes.last().contains("MERGE (migration:SchemaMigration"))
        assertEquals(1, transactions.parameters.last()["version"])
    }

    @Test
    fun secondRunWithMatchingChecksumIsIdempotent() = runBlocking {
        val transactions = RecordingTransactions()
        val first = migrator(transactions)
        first.migrate()
        val checksum = transactions.parameters.last().getValue("checksum").toString()
        transactions.applied = listOf(mapOf("version" to 1L, "checksum" to checksum))
        transactions.writes.clear()

        val result = migrator(transactions).migrate()

        assertEquals(emptyList(), result.appliedVersions)
        assertEquals(emptyList(), transactions.writes)
    }

    @Test
    fun refusesChangedAppliedMigration() = runBlocking {
        val transactions = RecordingTransactions().apply {
            applied = listOf(mapOf("version" to 1L, "checksum" to "different"))
        }
        assertFailsWith<IllegalStateException> { migrator(transactions).migrate() }
        Unit
    }

    @Test
    fun parserIgnoresCommentsAndEmptyStatements() {
        assertEquals(listOf("RETURN 1", "RETURN 2"), splitCypherStatements("// note\nRETURN 1;\n; RETURN 2;"))
    }

    private fun migrator(transactions: RecordingTransactions) = CatalogSchemaMigrator(
        transactions = transactions,
        migrations = listOf(CatalogSchemaMigration(1, "test", "/test.cypher")),
        resourceLoader = { "CREATE CONSTRAINT test IF NOT EXISTS FOR (node:Test) REQUIRE node.id IS UNIQUE;" },
    )
}

private class RecordingTransactions : GraphTransactionRunner {
    var applied: List<Map<String, Any?>> = emptyList()
    val writes = mutableListOf<String>()
    val parameters = mutableListOf<Map<String, Any?>>()

    override suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T =
        block(object : GraphQueryRunner {
            override fun query(cypher: String, parameters: Map<String, Any?>) = applied
        })

    override suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T =
        block(object : GraphQueryRunner {
            override fun query(cypher: String, parameters: Map<String, Any?>): List<Map<String, Any?>> {
                writes += cypher
                this@RecordingTransactions.parameters += parameters
                return emptyList()
            }
        })
}
