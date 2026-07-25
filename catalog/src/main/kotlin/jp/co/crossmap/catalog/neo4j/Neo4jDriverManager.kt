package jp.co.crossmap.catalog.neo4j

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig

private val logger = KotlinLogging.logger {}

data class Neo4jHealth(
    val reachable: Boolean,
    val schemaVersion: Int?,
    val catalogImported: Boolean,
)

class Neo4jDriverManager(
    val config: Neo4jConfig,
    driverFactory: (Neo4jConfig) -> Driver = { value ->
        val driverConfig = org.neo4j.driver.Config.builder()
            .withMaxConnectionPoolSize(value.maxConnectionPoolSize)
            .withConnectionAcquisitionTimeout(value.connectionAcquisitionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .withConnectionTimeout(value.connectionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .withMaxTransactionRetryTime(value.maxTransactionRetryTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
        GraphDatabase.driver(value.uri, AuthTokens.basic(value.username, value.password), driverConfig)
    },
) : AutoCloseable {
    val driver: Driver = driverFactory(config)
    private val closed = AtomicBoolean(false)

    init {
        logger.info { "neo4j-driver: uri=${config.uri}, database=${config.database}" }
    }

    suspend fun verifyConnectivity() = withContext(Dispatchers.IO) {
        driver.verifyConnectivity()
        driver.session(SessionConfig.forDatabase(config.database)).use { session ->
            check(session.run("RETURN 1 AS ok").single()["ok"].asInt() == 1) { "Neo4j health query failed" }
        }
        logger.info { "neo4j-connectivity: reachable=true, database=${config.database}" }
    }

    suspend fun health(expectedSchemaVersion: Int): Neo4jHealth = withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(config.database)).use { session ->
            val result = session.run(
                """
                OPTIONAL MATCH (migration:SchemaMigration)
                WITH max(migration.version) AS schemaVersion
                OPTIONAL MATCH (run:ImportRun {status: 'COMPLETED'})
                RETURN schemaVersion, count(run) > 0 AS catalogImported
                """.trimIndent(),
            ).single()
            Neo4jHealth(
                reachable = true,
                schemaVersion = result["schemaVersion"].takeUnless { it.isNull }?.asInt(),
                catalogImported = result["catalogImported"].asBoolean() &&
                    result["schemaVersion"].takeUnless { it.isNull }?.asInt() == expectedSchemaVersion,
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            driver.close()
            logger.info { "neo4j-driver: closed" }
        }
    }
}
