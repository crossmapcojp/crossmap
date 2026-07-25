package jp.co.crossmap.catalog.importer

import java.security.MessageDigest
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CatalogImportReport(
    val sourcePath: String,
    val sourceChecksum: String,
    val dryRun: Boolean,
    val entityCounts: Map<String, Int>,
    val relationshipCounts: Map<String, Int>,
    val rejectedRecords: List<RejectedChurchImportRecord>,
    val warnings: List<String>,
    val duplicateCollapses: Int,
    val batches: Int,
    val durationMillis: Long,
    val schemaVersion: Int,
    val database: String,
)

class Neo4jCatalogImporter(
    private val transactions: GraphTransactionRunner,
    private val database: String,
    private val schemaVersion: Int,
    private val batchSize: Int = 250,
    private val json: Json = Json { encodeDefaults = true },
) {
    init {
        require(batchSize > 0) { "Catalog import batch size must be positive" }
    }

    suspend fun import(catalog: NormalizedCatalogImport, dryRun: Boolean = false): CatalogImportReport {
        val started = System.nanoTime()
        val records = catalog.records
        val entityCounts = entityCounts(records)
        val relationshipCounts = relationshipCounts(records)
        if (!dryRun) {
            val runId = "catalog:${catalog.sourceChecksum}"
            startRun(runId, catalog)
            try {
                records.chunked(batchSize).forEachIndexed { index, batch -> importBatch(runId, index, batch) }
                cleanupManagedOrphans()
                completeRun(runId, entityCounts, relationshipCounts)
            } catch (failure: Throwable) {
                failRun(runId, failure)
                throw failure
            }
        }
        return CatalogImportReport(
            sourcePath = catalog.sourcePath,
            sourceChecksum = catalog.sourceChecksum,
            dryRun = dryRun,
            entityCounts = entityCounts,
            relationshipCounts = relationshipCounts,
            rejectedRecords = catalog.rejectedRecords,
            warnings = catalog.warnings,
            duplicateCollapses = catalog.duplicateCollapses,
            batches = records.chunked(batchSize).size,
            durationMillis = (System.nanoTime() - started) / 1_000_000,
            schemaVersion = schemaVersion,
            database = database,
        )
    }

    private suspend fun startRun(runId: String, catalog: NormalizedCatalogImport) {
        transactions.write("catalog-import.start") { runner ->
            runner.query(
                """
                MERGE (run:ImportRun {id: ${'$'}id})
                SET run.sourcePath = ${'$'}sourcePath,
                    run.sourceChecksum = ${'$'}sourceChecksum,
                    run.schemaVersion = ${'$'}schemaVersion,
                    run.status = 'RUNNING',
                    run.startedAt = datetime(),
                    run.completedAt = null,
                    run.error = null
                """.trimIndent(),
                mapOf(
                    "id" to runId,
                    "sourcePath" to catalog.sourcePath,
                    "sourceChecksum" to catalog.sourceChecksum,
                    "schemaVersion" to schemaVersion,
                ),
            )
        }
    }

    private suspend fun importBatch(runId: String, batchIndex: Int, records: List<ChurchImportRecord>) {
        val churchIds = records.map { it.id.value }
        val churchRows = records.map { record ->
            mapOf(
                "id" to record.id.value,
                "locationId" to "church-location:${record.id.value}",
                "properties" to buildMap<String, Any?> {
                    put("googlePlaceId", record.googlePlaceId)
                    put("primaryName", record.primaryName)
                    put("englishName", record.englishName)
                    put("normalizedName", normalizeSearchText(record.primaryName))
                    put("titleLanguages", record.titleLanguages)
                    put("category", record.category)
                    put("address", record.address)
                    put("email", record.email)
                    put("updatedAt", record.updatedAt)
                    record.names.values.forEach { (language, value) -> put("name_$language", value) }
                },
                "latitude" to record.latitude,
                "longitude" to record.longitude,
            )
        }
        transactions.write("catalog-import.batch$batchIndex.churches") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MERGE (church:Church {id: row.id})
                SET church += row.properties
                MERGE (location:Location {id: row.locationId})
                SET location.latitude = row.latitude, location.longitude = row.longitude, location.address = row.properties.address
                MERGE (church)-[:LOCATED_AT]->(location)
                """.trimIndent(),
                mapOf("rows" to churchRows),
            )
        }
        replaceDenominations(batchIndex, churchIds, records)
        replaceWebsitesAndPages(batchIndex, churchIds, records)
        replaceSocialAccounts(batchIndex, churchIds, records)
        replaceMinisters(batchIndex, churchIds, records)
        replaceSources(runId, batchIndex, churchIds, records)
    }

    private suspend fun replaceDenominations(batchIndex: Int, churchIds: List<String>, records: List<ChurchImportRecord>) {
        val rows = records.mapNotNull { record -> record.denomination?.let { record.id.value to it } }.map { (churchId, denomination) ->
            mapOf(
                "churchId" to churchId,
                "id" to denomination.id.value,
                "properties" to buildMap<String, Any?> {
                    put("normalizedName", normalizeSearchText(denomination.id.value))
                    denomination.localizedNames.forEach { put("name_${it.languageCode.substringBefore('-').lowercase()}", it.name) }
                },
            )
        }
        boundedRelationshipDelete(batchIndex, churchIds, "BELONGS_TO_DENOMINATION")
        if (rows.isEmpty()) return
        transactions.write("catalog-import.batch$batchIndex.denominations") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (denomination:Denomination {id: row.id})
                SET denomination += row.properties
                MERGE (church)-[:BELONGS_TO_DENOMINATION]->(denomination)
                """.trimIndent(),
                mapOf("rows" to rows),
            )
        }
    }

    private suspend fun replaceWebsitesAndPages(batchIndex: Int, churchIds: List<String>, records: List<ChurchImportRecord>) {
        val websites = records.mapNotNull { record -> record.website?.let { record.id.value to it } }
        boundedRelationshipDelete(batchIndex, churchIds, "HAS_WEBSITE")
        if (websites.isEmpty()) return
        val rows = websites.map { (churchId, website) ->
            mapOf(
                "churchId" to churchId,
                "id" to website.id,
                "url" to website.url,
                "normalizedUrl" to website.normalizedUrl,
                "pageIds" to website.pages.map(::webpageId).distinct(),
            )
        }
        transactions.write("catalog-import.batch$batchIndex.websites") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (website:Website {id: row.id})
                SET website.url = row.url, website.normalizedUrl = row.normalizedUrl
                MERGE (church)-[link:HAS_WEBSITE]->(website)
                SET link.pageIds = row.pageIds
                """.trimIndent(),
                mapOf("rows" to rows),
            )
        }
        val pageRows = websites.flatMap { (_, website) -> website.pages.map { page -> pageRow(website.id, page) } }
        if (pageRows.isNotEmpty()) {
            transactions.write("catalog-import.batch$batchIndex.pages") { runner ->
                runner.query(
                    """
                    UNWIND ${'$'}rows AS row
                    MATCH (website:Website {id: row.websiteId})
                    MERGE (page:Webpage {id: row.id})
                    SET page += row.properties
                    MERGE (website)-[:HAS_PAGE]->(page)
                    """.trimIndent(),
                    mapOf("rows" to pageRows),
                )
            }
        }
    }

    private suspend fun replaceSocialAccounts(batchIndex: Int, churchIds: List<String>, records: List<ChurchImportRecord>) {
        boundedRelationshipDelete(batchIndex, churchIds, "HAS_SOCIAL_ACCOUNT")
        val rows = records.flatMap { record -> record.socialAccounts.map { account ->
            mapOf(
                "churchId" to record.id.value,
                "id" to account.id,
                    "properties" to mapOf(
                        "platform" to account.platform.name,
                        "url" to account.url,
                        "normalizedUrl" to account.normalizedUrl,
                    ),
                    "relationshipProperties" to mapOf(
                        "handle" to account.handle,
                        "displayName" to account.displayName,
                    "description" to account.description,
                    "discoveredAt" to account.discoveredAt,
                    "contentHash" to account.contentHash,
                ),
            )
        } }
        if (rows.isEmpty()) return
        transactions.write("catalog-import.batch$batchIndex.social") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (account:SocialMediaAccount {id: row.id})
                SET account += row.properties
                MERGE (church)-[link:HAS_SOCIAL_ACCOUNT]->(account)
                SET link += row.relationshipProperties
                """.trimIndent(),
                mapOf("rows" to rows),
            )
        }
    }

    private suspend fun replaceMinisters(batchIndex: Int, churchIds: List<String>, records: List<ChurchImportRecord>) {
        val rows = records.flatMap { record -> record.ministers.map { minister -> ministerRow(record.id.value, minister) } }
        transactions.write("catalog-import.batch$batchIndex.minister-links") { runner ->
            runner.query(
                """
                UNWIND ${'$'}churchIds AS churchId
                MATCH (church:Church {id: churchId})
                OPTIONAL MATCH (role:RoleEvent)-[at:ROLE_AT]->(church)
                DELETE at
                """.trimIndent(),
                mapOf("churchIds" to churchIds),
            )
        }
        if (rows.isEmpty()) return
        transactions.write("catalog-import.batch$batchIndex.ministers") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (person:Person {id: row.personId})
                SET person.name = row.name, person.localizedNamesJson = row.localizedNamesJson
                MERGE (role:RoleEvent {id: row.roleEventId})
                SET role.roleId = row.roleId, role.roleName = row.roleName, role.localizedRoleNamesJson = row.localizedRoleNamesJson
                MERGE (person)-[:HELD_ROLE]->(role)
                MERGE (role)-[:ROLE_AT]->(church)
                """.trimIndent(),
                mapOf("rows" to rows),
            )
        }
    }

    private suspend fun replaceSources(runId: String, batchIndex: Int, churchIds: List<String>, records: List<ChurchImportRecord>) {
        boundedRelationshipDelete(batchIndex, churchIds, "IMPORTED_FROM")
        val rows = records.map { record ->
            val sourceId = "source:${record.source.checksum}:${record.source.recordIndex}"
            mapOf(
                "churchId" to record.id.value,
                "sourceId" to sourceId,
                "runId" to runId,
                "recordIndex" to record.source.recordIndex,
                "determinationsJson" to json.encodeToString(record.determinations),
            )
        }
        transactions.write("catalog-import.batch$batchIndex.sources") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId}), (run:ImportRun {id: row.runId})
                MERGE (source:SourceRecord {id: row.sourceId})
                SET source.recordIndex = row.recordIndex, source.determinationsJson = row.determinationsJson
                MERGE (church)-[:IMPORTED_FROM]->(source)
                MERGE (run)-[:IMPORTED]->(source)
                """.trimIndent(),
                mapOf("rows" to rows),
            )
        }
    }

    private suspend fun boundedRelationshipDelete(batchIndex: Int, churchIds: List<String>, relationship: String) {
        require(relationship in REPLACEABLE_CHURCH_RELATIONSHIPS)
        transactions.write("catalog-import.batch$batchIndex.clear-${relationship.lowercase()}") { runner ->
            runner.query(
                """
                UNWIND ${'$'}churchIds AS churchId
                MATCH (church:Church {id: churchId})
                OPTIONAL MATCH (church)-[relationship:$relationship]->()
                DELETE relationship
                """.trimIndent(),
                mapOf("churchIds" to churchIds),
            )
        }
    }

    private suspend fun completeRun(runId: String, entities: Map<String, Int>, relationships: Map<String, Int>) {
        transactions.write("catalog-import.complete") { runner ->
            runner.query(
                """
                MATCH (run:ImportRun {id: ${'$'}id})
                SET run.status = 'COMPLETED', run.completedAt = datetime(),
                    run.entityCountsJson = ${'$'}entityCountsJson,
                    run.relationshipCountsJson = ${'$'}relationshipCountsJson
                """.trimIndent(),
                mapOf("id" to runId, "entityCountsJson" to json.encodeToString(entities), "relationshipCountsJson" to json.encodeToString(relationships)),
            )
        }
    }

    private suspend fun cleanupManagedOrphans() {
        ORPHAN_CLEANUPS.forEach { cleanup ->
            val ids = transactions.read("catalog-import.cleanup.${cleanup.name}.find") { runner ->
                val row = runner.query(cleanup.findCypher).single()
                (row["ids"] as? List<*>)?.map(Any?::toString).orEmpty()
            }
            if (ids.isNotEmpty()) {
                transactions.write("catalog-import.cleanup.${cleanup.name}.delete") { runner ->
                    runner.query(cleanup.deleteCypher, mapOf("ids" to ids))
                }
            }
        }
    }

    private suspend fun failRun(runId: String, failure: Throwable) {
        runCatching {
            transactions.write("catalog-import.fail") { runner ->
                runner.query(
                    "MATCH (run:ImportRun {id: ${'$'}id}) SET run.status = 'FAILED', run.completedAt = datetime(), run.error = ${'$'}error",
                    mapOf("id" to runId, "error" to (failure.message ?: failure::class.simpleName).orEmpty().take(2_000)),
                )
            }
        }
    }

    private fun pageRow(websiteId: String, page: CrawledPage): Map<String, Any?> {
        val normalizedUrl = normalizeUrl(page.url)
        return mapOf(
            "websiteId" to websiteId,
            "id" to webpageId(page),
            "properties" to mapOf(
                "url" to page.url,
                "normalizedUrl" to normalizedUrl,
                "finalUrl" to page.finalUrl,
                "title" to page.title,
                "text" to page.text,
                "fetchedAt" to page.fetchedAt,
                "contentHash" to page.contentHash,
                "status" to page.status,
                "error" to page.error,
                "contentType" to page.contentType.name,
                "sermonJson" to page.sermon?.let { json.encodeToString(it) },
            ),
        )
    }

    private fun ministerRow(churchId: String, minister: ChurchMinister): Map<String, Any?> {
        val identity = "$churchId|${minister.name.trim()}|${minister.roleId.trim()}"
        return mapOf(
            "churchId" to churchId,
            "personId" to hashId("person", identity),
            "roleEventId" to hashId("role", identity),
            "name" to minister.name.trim(),
            "localizedNamesJson" to json.encodeToString(minister.localizedNames),
            "roleId" to minister.roleId.trim(),
            "roleName" to minister.roleName.trim(),
            "localizedRoleNamesJson" to json.encodeToString(minister.localizedRoleNames),
        )
    }

    companion object {
        private data class OrphanCleanup(val name: String, val findCypher: String, val deleteCypher: String)

        private val ORPHAN_CLEANUPS = listOf(
            OrphanCleanup(
                "websites",
                "MATCH (node:Website) WHERE NOT (:Church)-[:HAS_WEBSITE]->(node) RETURN collect(node.id) AS ids",
                "MATCH (node:Website) WHERE node.id IN ${'$'}ids AND NOT (:Church)-[:HAS_WEBSITE]->(node) DETACH DELETE node",
            ),
            OrphanCleanup(
                "webpages",
                """
                MATCH (node:Webpage)
                WHERE NOT EXISTS {
                    MATCH (:Church)-[link:HAS_WEBSITE]->(:Website)-[:HAS_PAGE]->(node)
                    WHERE node.id IN coalesce(link.pageIds, [])
                }
                RETURN collect(node.id) AS ids
                """.trimIndent(),
                """
                MATCH (node:Webpage)
                WHERE node.id IN ${'$'}ids AND NOT EXISTS {
                    MATCH (:Church)-[link:HAS_WEBSITE]->(:Website)-[:HAS_PAGE]->(node)
                    WHERE node.id IN coalesce(link.pageIds, [])
                }
                DETACH DELETE node
                """.trimIndent(),
            ),
            OrphanCleanup(
                "social-accounts",
                "MATCH (node:SocialMediaAccount) WHERE NOT (:Church)-[:HAS_SOCIAL_ACCOUNT]->(node) RETURN collect(node.id) AS ids",
                "MATCH (node:SocialMediaAccount) WHERE node.id IN ${'$'}ids AND NOT (:Church)-[:HAS_SOCIAL_ACCOUNT]->(node) DETACH DELETE node",
            ),
            OrphanCleanup(
                "role-events",
                "MATCH (node:RoleEvent) WHERE NOT (node)-[:ROLE_AT]->(:Church) RETURN collect(node.id) AS ids",
                "MATCH (node:RoleEvent) WHERE node.id IN ${'$'}ids AND NOT (node)-[:ROLE_AT]->(:Church) DETACH DELETE node",
            ),
            OrphanCleanup(
                "people",
                "MATCH (node:Person) WHERE NOT (node)-[:HELD_ROLE]->(:RoleEvent) RETURN collect(node.id) AS ids",
                "MATCH (node:Person) WHERE node.id IN ${'$'}ids AND NOT (node)-[:HELD_ROLE]->(:RoleEvent) DETACH DELETE node",
            ),
        )

        private val REPLACEABLE_CHURCH_RELATIONSHIPS = setOf(
            "BELONGS_TO_DENOMINATION", "HAS_WEBSITE", "HAS_SOCIAL_ACCOUNT", "IMPORTED_FROM",
        )

        private fun entityCounts(records: List<ChurchImportRecord>) = linkedMapOf(
            "Church" to records.size,
            "Denomination" to records.mapNotNull { it.denomination?.id }.distinct().size,
            "Location" to records.size,
            "Website" to records.mapNotNull(ChurchImportRecord::website).distinctBy(WebsiteImportRecord::id).size,
            "Webpage" to records.flatMap { it.website?.pages.orEmpty() }.distinctBy(CrawledPage::url).size,
            "SocialMediaAccount" to records.flatMap(ChurchImportRecord::socialAccounts).distinctBy(SocialAccountImportRecord::id).size,
            "Person" to records.sumOf { it.ministers.size },
            "SourceRecord" to records.size,
            "ImportRun" to 1,
        )

        private fun relationshipCounts(records: List<ChurchImportRecord>) = linkedMapOf(
            "LOCATED_AT" to records.size,
            "BELONGS_TO_DENOMINATION" to records.count { it.denomination != null },
            "HAS_WEBSITE" to records.count { it.website != null },
            "HAS_PAGE" to records.sumOf { it.website?.pages?.size ?: 0 },
            "HAS_SOCIAL_ACCOUNT" to records.sumOf { it.socialAccounts.size },
            "HELD_ROLE" to records.sumOf { it.ministers.size },
            "ROLE_AT" to records.sumOf { it.ministers.size },
            "IMPORTED_FROM" to records.size,
            "IMPORTED" to records.size,
        )
    }
}

private fun normalizeSearchText(value: String): String = value.trim().lowercase()

private fun hashId(namespace: String, value: String): String = "$namespace:${MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)}"

private fun webpageId(page: CrawledPage): String = hashId(
    "webpage",
    normalizeUrl(page.url, retainFragment = true) + "|" + page.contentHash,
)
