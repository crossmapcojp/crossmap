package jp.co.crossmap.catalog.neo4j

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.SimpleQueryRunner

private val transactionLogger = KotlinLogging.logger {}

interface GraphQueryRunner {
    fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Map<String, Any?>>
}

interface GraphTransactionRunner {
    suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T
    suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T
}

class Neo4jGraphTransactionRunner(
    private val driver: Driver,
    private val database: String,
) : GraphTransactionRunner {
    override suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T = execute(queryName, false, block)
    override suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T = execute(queryName, true, block)

    private suspend fun <T> execute(queryName: String, write: Boolean, block: (GraphQueryRunner) -> T): T =
        withContext(Dispatchers.IO) {
            val mark = TimeSource.Monotonic.markNow()
            try {
                driver.session(SessionConfig.forDatabase(database)).use { session ->
                    if (write) {
                        session.executeWrite { transaction -> block(DriverQueryRunner(transaction)) }
                    } else {
                        session.executeRead { transaction -> block(DriverQueryRunner(transaction)) }
                    }
                }
            } finally {
                val elapsed = mark.elapsedNow()
                if (elapsed.inWholeMilliseconds >= 250) {
                    transactionLogger.warn { "neo4j-query: name=$queryName duration=$elapsed write=$write" }
                } else {
                    transactionLogger.debug { "neo4j-query: name=$queryName duration=$elapsed write=$write" }
                }
            }
        }
}

private class DriverQueryRunner(private val delegate: SimpleQueryRunner) : GraphQueryRunner {
    override fun query(cypher: String, parameters: Map<String, Any?>): List<Map<String, Any?>> =
        delegate.run(cypher, parameters).list { record ->
            record.keys().associateWith { key -> record[key].takeUnless { it.isNull }?.asObject() }
        }
}
