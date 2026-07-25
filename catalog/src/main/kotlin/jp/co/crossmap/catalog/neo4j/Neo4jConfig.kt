package jp.co.crossmap.catalog.neo4j

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class CatalogBackend { JSON, NEO4J }

data class Neo4jConfig(
    val uri: String,
    val username: String,
    val password: String,
    val database: String,
    val maxConnectionPoolSize: Int = 50,
    val connectionAcquisitionTimeout: Duration = 60.seconds,
    val connectionTimeout: Duration = 30.seconds,
    val maxTransactionRetryTime: Duration = 30.seconds,
) {
    init {
        require(uri.isNotBlank()) { "NEO4J_URI must not be blank" }
        require(username.isNotBlank()) { "NEO4J_USERNAME must not be blank" }
        require(password.isNotBlank()) { "NEO4J_PASSWORD must not be blank" }
        require(database.isNotBlank()) { "NEO4J_DATABASE must not be blank" }
        require(maxConnectionPoolSize > 0) { "NEO4J_MAX_CONNECTION_POOL_SIZE must be positive" }
        require(connectionAcquisitionTimeout.isPositive()) { "NEO4J_CONNECTION_ACQUISITION_TIMEOUT_SECONDS must be positive" }
        require(connectionTimeout.isPositive()) { "NEO4J_CONNECTION_TIMEOUT_SECONDS must be positive" }
        require(maxTransactionRetryTime.isPositive()) { "NEO4J_MAX_TRANSACTION_RETRY_TIME_SECONDS must be positive" }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            properties: Map<String, String> = emptyMap(),
        ): Neo4jConfig {
            fun setting(environmentName: String, propertyName: String): String? =
                environment[environmentName] ?: properties[propertyName]
            val merged = buildMap {
                putAll(environment)
                OPTIONAL_SETTINGS.forEach { (environmentName, propertyName) ->
                    setting(environmentName, propertyName)?.let { putIfAbsent(environmentName, it) }
                }
            }
            return Neo4jConfig(
                uri = setting("NEO4J_URI", "neo4j.uri") ?: "bolt://localhost:7687",
                username = setting("NEO4J_USERNAME", "neo4j.username") ?: error("NEO4J_USERNAME or neo4j.username is required"),
                password = setting("NEO4J_PASSWORD", "neo4j.password") ?: error("NEO4J_PASSWORD or neo4j.password is required"),
                database = setting("NEO4J_DATABASE", "neo4j.database") ?: "neo4j",
                maxConnectionPoolSize = merged.positiveInt("NEO4J_MAX_CONNECTION_POOL_SIZE", 50),
                connectionAcquisitionTimeout = merged.positiveSeconds("NEO4J_CONNECTION_ACQUISITION_TIMEOUT_SECONDS", 60),
                connectionTimeout = merged.positiveSeconds("NEO4J_CONNECTION_TIMEOUT_SECONDS", 30),
                maxTransactionRetryTime = merged.positiveSeconds("NEO4J_MAX_TRANSACTION_RETRY_TIME_SECONDS", 30),
            )
        }

        fun fromEnvironmentAndLocalProperties(
            environment: Map<String, String> = System.getenv(),
            path: Path = Path.of("local.properties"),
        ): Neo4jConfig {
            val properties = Properties()
            if (Files.isRegularFile(path)) Files.newInputStream(path).use(properties::load)
            return fromEnvironment(environment, properties.stringPropertyNames().associateWith(properties::getProperty))
        }

        private val OPTIONAL_SETTINGS = mapOf(
            "NEO4J_MAX_CONNECTION_POOL_SIZE" to "neo4j.maxConnectionPoolSize",
            "NEO4J_CONNECTION_ACQUISITION_TIMEOUT_SECONDS" to "neo4j.connectionAcquisitionTimeoutSeconds",
            "NEO4J_CONNECTION_TIMEOUT_SECONDS" to "neo4j.connectionTimeoutSeconds",
            "NEO4J_MAX_TRANSACTION_RETRY_TIME_SECONDS" to "neo4j.maxTransactionRetryTimeSeconds",
        )
    }
}

private fun Map<String, String>.positiveInt(name: String, default: Int): Int =
    get(name)?.toIntOrNull()?.takeIf { it > 0 } ?: if (containsKey(name)) {
        error("$name must be a positive integer")
    } else {
        default
    }

private fun Map<String, String>.positiveSeconds(name: String, default: Int): Duration =
    positiveInt(name, default).seconds

data class CatalogConfig(
    val backend: CatalogBackend,
    val neo4j: Neo4jConfig?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): CatalogConfig {
            val backend = when (environment["CATALOG_BACKEND"]?.trim()?.lowercase() ?: "json") {
                "json" -> CatalogBackend.JSON
                "neo4j" -> CatalogBackend.NEO4J
                else -> error("CATALOG_BACKEND must be json or neo4j")
            }
            return CatalogConfig(
                backend = backend,
                neo4j = if (backend == CatalogBackend.NEO4J) Neo4jConfig.fromEnvironment(environment) else null,
            )
        }
    }
}
