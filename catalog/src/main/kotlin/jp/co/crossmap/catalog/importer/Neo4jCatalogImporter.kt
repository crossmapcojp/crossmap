package jp.co.crossmap.catalog.importer

import java.security.MessageDigest
import java.util.UUID
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialProfile
import jp.co.crossmap.catalog.canonical.CanonicalChurchCatalogHasher
import jp.co.crossmap.catalog.canonical.CatalogOperationMetadata
import jp.co.crossmap.catalog.canonical.CatalogRevisionMismatchException
import jp.co.crossmap.catalog.canonical.CatalogRevisionToken
import jp.co.crossmap.catalog.neo4j.GraphQueryRunner
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import jp.co.crossmap.toNeo4jNameProperties
import jp.co.crossmap.toCanonicalNameMap
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
    val revisionId: String? = null,
    val revisionSequence: Long? = null,
    val contentHash: String = sourceChecksum,
)

class Neo4jCatalogImporter(
    private val transactions: GraphTransactionRunner,
    private val database: String,
    private val schemaVersion: Int,
    private val batchSize: Int = 250,
    private val json: Json = Json { encodeDefaults = true },
) {
    private var activeReplacementRunner: GraphQueryRunner? = null

    init {
        require(batchSize > 0) { "Catalog import batch size must be positive" }
    }

    suspend fun import(
        catalog: NormalizedCatalogImport,
        dryRun: Boolean = false,
        expectedRevision: CatalogRevisionToken? = null,
        operation: CatalogOperationMetadata = CatalogOperationMetadata(
            operation = "legacy-json-bootstrap",
            actor = "catalog-neo4j-bootstrap-from-legacy-json",
            source = catalog.sourcePath,
        ),
    ): CatalogImportReport {
        val started = System.nanoTime()
        val records = catalog.records
        val contentHash = CanonicalChurchCatalogHasher.contentHash(records.map { it.toChurchRecord() })
        val entityCounts = entityCounts(records)
        val relationshipCounts = relationshipCounts(records)
        var committedRevisionSequence: Long? = null
        var revisionId: String? = null
        if (!dryRun) {
            val runId = "catalog:${UUID.randomUUID()}"
            revisionId = runId
            try {
                check(activeReplacementRunner == null) { "A catalog replacement is already active" }
                transactions.write("catalog-import.authoritative-replacement") { runner ->
                    activeReplacementRunner = runner
                    try {
                        runBlocking {
                            lockAndVerifyExpectedRevision(expectedRevision)
                            startRun(runId, contentHash, catalog, operation)
                            records.chunked(batchSize).forEachIndexed { index, batch -> importBatch(runId, index, batch) }
                            removeAbsentManagedChurches(records.map { it.id.value })
                            cleanupManagedOrphans()
                            committedRevisionSequence = completeRun(runId, contentHash, entityCounts, relationshipCounts)
                        }
                    } finally {
                        activeReplacementRunner = null
                    }
                }
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
            revisionId = revisionId,
            revisionSequence = committedRevisionSequence,
            contentHash = contentHash,
        )
    }

    private suspend fun <T> catalogRead(name: String, block: (GraphQueryRunner) -> T): T {
        val runner = activeReplacementRunner
        return if (runner != null) block(runner) else transactions.read(name, block)
    }

    private suspend fun <T> catalogWrite(name: String, block: (GraphQueryRunner) -> T): T {
        val runner = activeReplacementRunner
        return if (runner != null) block(runner) else transactions.write(name, block)
    }

    private suspend fun lockAndVerifyExpectedRevision(expectedRevision: CatalogRevisionToken?) {
        val actual = catalogWrite("catalog-import.lock-state") { runner ->
            runner.query(
                """
                MERGE (state:CatalogState {name: 'catalog'})
                ON CREATE SET state.nextSequence = 1, state.writeLock = 0
                SET state.writeLock = coalesce(state.writeLock, 0) + 1
                WITH state
                OPTIONAL MATCH (state)-[:CURRENT_REVISION]->(revision:CatalogRevision {status: 'COMMITTED'})
                RETURN revision.id AS revisionId, revision.sequence AS revisionSequence
                """.trimIndent(),
            ).singleOrNull()?.let { row ->
                val revisionId = row["revisionId"]?.toString()?.takeIf(String::isNotBlank) ?: return@let null
                CatalogRevisionToken(revisionId, (row["revisionSequence"] as Number).toLong())
            }
        }
        if (expectedRevision != null && actual != expectedRevision) {
            throw CatalogRevisionMismatchException(expectedRevision, actual)
        }
    }

    private suspend fun startRun(
        runId: String,
        contentHash: String,
        catalog: NormalizedCatalogImport,
        operation: CatalogOperationMetadata,
    ) {
        catalogWrite("catalog-import.start") { runner ->
            runner.query(
                """
                MERGE (run:ImportRun {id: ${'$'}id})
                SET run.sourcePath = ${'$'}sourcePath,
                    run.sourceChecksum = ${'$'}sourceChecksum,
                    run.schemaVersion = ${'$'}schemaVersion,
                    run.operation = ${'$'}operation,
                    run.actor = ${'$'}actor,
                    run.operationSource = ${'$'}operationSource,
                    run.status = 'RUNNING',
                    run.startedAt = datetime(),
                    run.completedAt = null,
                    run.error = null
                MERGE (state:CatalogState {name: 'catalog'})
                ON CREATE SET state.nextSequence = 1
                WITH run, state, coalesce(state.nextSequence, 1) AS nextSequence
                MERGE (revision:CatalogRevision {id: ${'$'}id})
                ON CREATE SET revision.sequence = nextSequence
                SET revision.status = 'BUILDING',
                    revision.contentHash = ${'$'}contentHash,
                    revision.churchCount = ${'$'}churchCount,
                    revision.operation = ${'$'}operation,
                    revision.actor = ${'$'}actor,
                    revision.operationSource = ${'$'}operationSource,
                    revision.startedAt = datetime(),
                    revision.committedAt = null,
                    revision.error = null
                SET state.nextSequence = CASE
                    WHEN revision.sequence = nextSequence THEN nextSequence + 1
                    ELSE state.nextSequence
                END
                """.trimIndent(),
                mapOf(
                    "id" to runId,
                    "sourcePath" to catalog.sourcePath,
                    "sourceChecksum" to catalog.sourceChecksum,
                    "contentHash" to contentHash,
                    "churchCount" to catalog.records.size,
                    "operation" to operation.operation,
                    "actor" to operation.actor,
                    "operationSource" to operation.source,
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
                    put("id", record.id.value)
                    put("googlePlaceId", record.googlePlaceId)
                    put("primaryName", record.primaryName)
                    put("englishName", record.englishName)
                    put("normalizedName", normalizeSearchText(record.primaryName))
                    put("titleLanguages", record.titleLanguages)
                    put("category", record.category)
                    put("address", record.address)
                    put("email", record.email)
                    put("updatedAt", record.updatedAt)
                    putAll(record.names.values.toNeo4jNameProperties())
                },
                "latitude" to record.latitude,
                "longitude" to record.longitude,
            )
        }
        catalogWrite("catalog-import.batch$batchIndex.churches") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MERGE (church:Church {id: row.id})
                SET church = row.properties,
                    church.catalogManaged = true
                MERGE (location:Location {id: row.locationId})
                SET location.latitude = row.latitude,
                    location.longitude = row.longitude,
                    location.address = row.properties.address,
                    location.catalogManaged = true
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
                    put("id", denomination.id.value)
                    put("normalizedName", normalizeSearchText(denomination.id.value))
                    putAll(denomination.localizedNames.toCanonicalNameMap().toNeo4jNameProperties())
                },
            )
        }
        boundedRelationshipDelete(batchIndex, churchIds, "BELONGS_TO_DENOMINATION")
        if (rows.isEmpty()) return
            catalogWrite("catalog-import.batch$batchIndex.denominations") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (denomination:Denomination {id: row.id})
                SET denomination = row.properties,
                    denomination.catalogManaged = true
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
            catalogWrite("catalog-import.batch$batchIndex.websites") { runner ->
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
            catalogWrite("catalog-import.batch$batchIndex.pages") { runner ->
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
        val pageLinkRows = websites.flatMap { (_, website) ->
            website.pages.flatMap { page ->
                page.outgoingLinks.map { targetUrl ->
                    mapOf(
                        "sourceId" to webpageId(page.url),
                        "targetId" to webpageId(targetUrl),
                        "targetUrl" to targetUrl,
                        "targetNormalizedUrl" to normalizeUrl(targetUrl, retainFragment = true),
                    )
                }
            }
        }.distinctBy { it["sourceId"] to it["targetId"] }
        val sourcePageIds = pageRows.mapNotNull { it["id"] as? String }.distinct()
        if (sourcePageIds.isNotEmpty()) {
            catalogWrite("catalog-import.batch$batchIndex.page-links-delete") { runner ->
                runner.query(
                    "UNWIND ${'$'}sourceIds AS sourceId MATCH (source:Webpage {id: sourceId})-[link:LINKS_TO]->() DELETE link",
                    mapOf("sourceIds" to sourcePageIds),
                )
            }
        }
        if (pageLinkRows.isNotEmpty()) {
            catalogWrite("catalog-import.batch$batchIndex.page-links") { runner ->
                runner.query(
                    """
                    UNWIND ${'$'}rows AS row
                    MATCH (source:Webpage {id: row.sourceId})
                    MERGE (target:Webpage {id: row.targetId})
                    ON CREATE SET target.url = row.targetUrl, target.normalizedUrl = row.targetNormalizedUrl, target.discoveredOnly = true
                    MERGE (source)-[:LINKS_TO]->(target)
                    """.trimIndent(),
                    mapOf("rows" to pageLinkRows),
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
            catalogWrite("catalog-import.batch$batchIndex.social") { runner ->
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
        catalogWrite("catalog-import.batch$batchIndex.minister-links") { runner ->
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
            catalogWrite("catalog-import.batch$batchIndex.ministers") { runner ->
            runner.query(
                """
                UNWIND ${'$'}rows AS row
                MATCH (church:Church {id: row.churchId})
                MERGE (person:Person {id: row.personId})
                SET person = row.personProperties,
                    person.catalogManaged = true
                MERGE (role:RoleEvent {id: row.roleEventId})
                SET role = row.roleProperties,
                    role.catalogManaged = true
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
        catalogWrite("catalog-import.batch$batchIndex.sources") { runner ->
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
        catalogWrite("catalog-import.batch$batchIndex.clear-${relationship.lowercase()}") { runner ->
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

    private suspend fun removeAbsentManagedChurches(authoritativeChurchIds: List<String>) {
        catalogWrite("catalog-import.remove-absent-churches") { runner ->
            runner.query(
                """
                MATCH (church:Church {catalogManaged: true})
                WHERE NOT church.id IN ${'$'}churchIds
                DETACH DELETE church
                """.trimIndent(),
                mapOf("churchIds" to authoritativeChurchIds),
            )
        }
    }

    private suspend fun completeRun(
        runId: String,
        contentHash: String,
        entities: Map<String, Int>,
        relationships: Map<String, Int>,
    ): Long = catalogWrite("catalog-import.complete") { runner ->
            runner.query(
                """
                MATCH (run:ImportRun {id: ${'$'}id})
                MATCH (revision:CatalogRevision {id: ${'$'}id})
                SET run.status = 'COMPLETED', run.completedAt = datetime(),
                    run.entityCountsJson = ${'$'}entityCountsJson,
                    run.relationshipCountsJson = ${'$'}relationshipCountsJson,
                    revision.status = 'COMMITTED',
                    revision.contentHash = ${'$'}contentHash,
                    revision.committedAt = datetime()
                MERGE (state:CatalogState {name: 'catalog'})
                SET state.updatedAt = datetime()
                WITH state, revision
                OPTIONAL MATCH (state)-[old:CURRENT_REVISION]->(:CatalogRevision)
                DELETE old
                MERGE (state)-[:CURRENT_REVISION]->(revision)
                RETURN revision.sequence AS revisionSequence
                """.trimIndent(),
                mapOf(
                    "id" to runId,
                    "contentHash" to contentHash,
                    "entityCountsJson" to json.encodeToString(entities),
                    "relationshipCountsJson" to json.encodeToString(relationships),
                ),
            ).singleOrNull()?.get("revisionSequence").let { (it as? Number)?.toLong() ?: 0L }
        }

    private suspend fun cleanupManagedOrphans() {
        ORPHAN_CLEANUPS.forEach { cleanup ->
            val ids = catalogRead("catalog-import.cleanup.${cleanup.name}.find") { runner ->
                val row = runner.query(cleanup.findCypher).singleOrNull()
                (row?.get("ids") as? List<*>)?.map(Any?::toString).orEmpty()
            }
            if (ids.isNotEmpty()) {
                catalogWrite("catalog-import.cleanup.${cleanup.name}.delete") { runner ->
                    runner.query(cleanup.deleteCypher, mapOf("ids" to ids))
                }
            }
        }
    }

    private suspend fun failRun(runId: String, failure: Throwable) {
        runCatching {
            catalogWrite("catalog-import.fail") { runner ->
                runner.query(
                    "MERGE (run:ImportRun {id: ${'$'}id}) SET run.status = 'FAILED', run.completedAt = datetime(), run.error = ${'$'}error",
                    mapOf("id" to runId, "error" to (failure.message ?: failure::class.simpleName).orEmpty().take(2_000)),
                )
                runner.query(
                    "MERGE (revision:CatalogRevision {id: ${'$'}id}) SET revision.status = 'FAILED', revision.completedAt = datetime(), revision.error = ${'$'}error",
                    mapOf("id" to runId, "error" to (failure.message ?: failure::class.simpleName).orEmpty().take(2_000)),
                )
            }
        }
    }

    private fun ChurchImportRecord.toChurchRecord(): ChurchRecord = ChurchRecord(
        id = id.value,
        googleCid = googlePlaceId,
        name = primaryName,
        englishName = englishName,
        localizedNames = localizedNames,
        localizedDenominationNames = denomination?.localizedNames.orEmpty(),
        titleLanguages = titleLanguages,
        denominationId = denomination?.id?.value,
        category = category,
        address = address,
        location = GeoPoint(latitude, longitude),
        websiteUrl = website?.url.orEmpty(),
        email = email,
        pages = website?.pages.orEmpty(),
        socialProfiles = socialAccounts.map { account ->
            SocialProfile(
                platform = account.platform,
                url = account.url,
                handle = account.handle,
                displayName = account.displayName,
                description = account.description,
                discoveredAt = account.discoveredAt,
                contentHash = account.contentHash,
            )
        },
        ministers = ministers,
        determinations = determinations,
        updatedAt = updatedAt,
    )

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
                "depth" to page.depth,
                "outgoingLinks" to page.outgoingLinks,
                "languageCode" to page.languageCode,
            ),
        )
    }

    private fun ministerRow(churchId: String, minister: ChurchMinister): Map<String, Any?> {
        val identity = "$churchId|${minister.name.trim()}|${minister.roleId.trim()}"
        val personId = hashId("person", identity)
        val roleEventId = hashId("role", identity)
        return mapOf(
            "churchId" to churchId,
            "personId" to personId,
            "roleEventId" to roleEventId,
            "personProperties" to buildMap<String, Any?> {
                put("id", personId)
                put("name", minister.name.trim())
                putAll(minister.localizedNames.toCanonicalNameMap().toNeo4jNameProperties())
            },
            "roleProperties" to buildMap<String, Any?> {
                put("id", roleEventId)
                put("roleId", minister.roleId.trim())
                put("roleName", minister.roleName.trim())
                putAll(minister.localizedRoleNames.toCanonicalNameMap().toNeo4jNameProperties())
            },
        )
    }

    companion object {
        private data class OrphanCleanup(val name: String, val findCypher: String, val deleteCypher: String)

        private val ORPHAN_CLEANUPS = listOf(
            OrphanCleanup(
                "locations",
                "MATCH (node:Location {catalogManaged: true}) WHERE NOT (:Church)-[:LOCATED_AT]->(node) RETURN collect(node.id) AS ids",
                "MATCH (node:Location {catalogManaged: true}) WHERE node.id IN ${'$'}ids AND NOT (:Church)-[:LOCATED_AT]->(node) DETACH DELETE node",
            ),
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
                AND NOT (:Webpage)-[:LINKS_TO]->(node)
                RETURN collect(node.id) AS ids
                """.trimIndent(),
                """
                MATCH (node:Webpage)
                WHERE node.id IN ${'$'}ids AND NOT EXISTS {
                    MATCH (:Church)-[link:HAS_WEBSITE]->(:Website)-[:HAS_PAGE]->(node)
                    WHERE node.id IN coalesce(link.pageIds, [])
                }
                AND NOT (:Webpage)-[:LINKS_TO]->(node)
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
            "Webpage" to records.flatMap { record ->
                record.website?.pages.orEmpty().flatMap { page -> listOf(page.url) + page.outgoingLinks }
            }.distinct().size,
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
            "LINKS_TO" to records.flatMap { record ->
                record.website?.pages.orEmpty().flatMap { page -> page.outgoingLinks.map { page.url to it } }
            }.distinct().size,
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

private fun webpageId(page: CrawledPage): String = webpageId(page.url)

private fun webpageId(url: String): String = hashId("webpage", normalizeUrl(url, retainFragment = true))
