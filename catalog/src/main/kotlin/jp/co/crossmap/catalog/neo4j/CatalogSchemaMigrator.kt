package jp.co.crossmap.catalog.neo4j

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest

private val migrationLogger = KotlinLogging.logger {}

data class CatalogSchemaMigration(
    val version: Int,
    val name: String,
    val resource: String,
)

data class CatalogSchemaMigrationResult(
    val version: Int,
    val appliedVersions: List<Int>,
)

class CatalogSchemaMigrator(
    private val transactions: GraphTransactionRunner,
    private val migrations: List<CatalogSchemaMigration> = DEFAULT_MIGRATIONS,
    private val resourceLoader: (String) -> String = ::loadMigrationResource,
) {
    init {
        require(migrations.map { it.version } == migrations.map { it.version }.sorted()) {
            "Catalog schema migrations must be ordered"
        }
        require(migrations.map { it.version }.distinct().size == migrations.size) {
            "Catalog schema migration versions must be unique"
        }
    }

    suspend fun migrate(): CatalogSchemaMigrationResult {
        val applied = transactions.read("catalog-schema.applied") { runner ->
            runner.query(
                """
                MATCH (migration:SchemaMigration)
                RETURN migration.version AS version, migration.checksum AS checksum
                ORDER BY migration.version
                """.trimIndent(),
            ).associate { row ->
                (row.getValue("version") as Number).toInt() to row["checksum"]?.toString()
            }
        }
        val knownVersions = migrations.mapTo(mutableSetOf()) { it.version }
        check(applied.keys.all(knownVersions::contains)) {
            "Database contains a catalog schema migration newer than this application: ${applied.keys - knownVersions}"
        }

        val newlyApplied = mutableListOf<Int>()
        migrations.forEach { migration ->
            val source = resourceLoader(migration.resource)
            val checksum = source.sha256()
            val previousChecksum = applied[migration.version]
            if (previousChecksum != null) {
                check(previousChecksum == checksum) {
                    "Catalog schema migration V${migration.version.toString().padStart(3, '0')} checksum changed"
                }
                return@forEach
            }

            val statements = splitCypherStatements(source)
            check(statements.isNotEmpty()) { "Catalog schema migration ${migration.resource} is empty" }
            statements.forEachIndexed { index, statement ->
                transactions.write("catalog-schema.v${migration.version}.statement${index + 1}") { runner ->
                    runner.query(statement)
                }
            }
            transactions.write("catalog-schema.v${migration.version}.record") { runner ->
                runner.query(
                    """
                    MERGE (migration:SchemaMigration {version: ${'$'}version})
                    ON CREATE SET migration.name = ${'$'}name,
                                  migration.checksum = ${'$'}checksum,
                                  migration.appliedAt = datetime()
                    WITH migration
                    MERGE (schema:CrossmapSchema {name: 'catalog'})
                    SET schema.version = ${'$'}version,
                        schema.updatedAt = datetime()
                    RETURN schema.version AS version
                    """.trimIndent(),
                    mapOf("version" to migration.version, "name" to migration.name, "checksum" to checksum),
                )
            }
            newlyApplied += migration.version
            migrationLogger.info { "catalog-schema: applied version=${migration.version} name=${migration.name}" }
        }
        return CatalogSchemaMigrationResult(
            version = migrations.lastOrNull()?.version ?: 0,
            appliedVersions = newlyApplied,
        )
    }

    companion object {
        val DEFAULT_MIGRATIONS = listOf(
            CatalogSchemaMigration(1, "initial_catalog_schema", "/catalog-migrations/V001__initial_catalog_schema.cypher"),
        )
        val EXPECTED_VERSION: Int = DEFAULT_MIGRATIONS.last().version
    }
}

internal fun splitCypherStatements(source: String): List<String> = source
    .lineSequence()
    .filterNot { it.trimStart().startsWith("//") }
    .joinToString("\n")
    .split(';')
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun loadMigrationResource(path: String): String =
    CatalogSchemaMigrator::class.java.getResource(path)?.readText()
        ?: error("Missing catalog schema migration resource: $path")

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
