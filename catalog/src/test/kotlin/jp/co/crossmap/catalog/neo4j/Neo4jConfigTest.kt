package jp.co.crossmap.catalog.neo4j

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class Neo4jConfigTest {
    @Test
    fun jsonBackendDoesNotRequireNeo4jCredentials() {
        val config = CatalogConfig.fromEnvironment(emptyMap())
        assertEquals(CatalogBackend.JSON, config.backend)
        assertNull(config.neo4j)
    }

    @Test
    fun neo4jBackendRequiresCredentialsAndUsesSafeLocalDefaults() {
        val config = CatalogConfig.fromEnvironment(
            mapOf(
                "CATALOG_BACKEND" to "neo4j",
                "NEO4J_USERNAME" to "neo4j",
                "NEO4J_PASSWORD" to "secret",
            )
        )
        assertEquals("bolt://localhost:7687", config.neo4j?.uri)
        assertEquals("neo4j", config.neo4j?.database)
        assertEquals(50, config.neo4j?.maxConnectionPoolSize)
        assertEquals(60.seconds, config.neo4j?.connectionAcquisitionTimeout)
    }

    @Test
    fun neo4jBackendNeverSilentlyFallsBackToJson() {
        assertFailsWith<IllegalStateException> {
            CatalogConfig.fromEnvironment(mapOf("CATALOG_BACKEND" to "neo4j"))
        }
    }

    @Test
    fun parsesOptionalPoolAndTimeoutSettings() {
        val config = Neo4jConfig.fromEnvironment(
            mapOf(
                "NEO4J_USERNAME" to "neo4j",
                "NEO4J_PASSWORD" to "secret",
                "NEO4J_MAX_CONNECTION_POOL_SIZE" to "12",
                "NEO4J_CONNECTION_ACQUISITION_TIMEOUT_SECONDS" to "7",
                "NEO4J_CONNECTION_TIMEOUT_SECONDS" to "8",
                "NEO4J_MAX_TRANSACTION_RETRY_TIME_SECONDS" to "9",
            ),
        )
        assertEquals(12, config.maxConnectionPoolSize)
        assertEquals(7.seconds, config.connectionAcquisitionTimeout)
        assertEquals(8.seconds, config.connectionTimeout)
        assertEquals(9.seconds, config.maxTransactionRetryTime)
    }

    @Test
    fun rejectsInvalidOptionalSettings() {
        assertFailsWith<IllegalStateException> {
            Neo4jConfig.fromEnvironment(
                mapOf(
                    "NEO4J_USERNAME" to "neo4j",
                    "NEO4J_PASSWORD" to "secret",
                    "NEO4J_MAX_CONNECTION_POOL_SIZE" to "0",
                ),
            )
        }
    }

    @Test
    fun readsIgnoredLocalPropertyNamesAndLetsEnvironmentOverrideThem() {
        val config = Neo4jConfig.fromEnvironment(
            environment = mapOf("NEO4J_URI" to "bolt://ci:7687"),
            properties = mapOf(
                "neo4j.uri" to "bolt://localhost:7687",
                "neo4j.username" to "neo4j",
                "neo4j.password" to "password",
            ),
        )
        assertEquals("bolt://ci:7687", config.uri)
        assertEquals("neo4j", config.username)
        assertEquals("password", config.password)
    }
}
