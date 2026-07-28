package jp.co.crossmap.catalog.importer

import jp.co.crossmap.catalog.ChurchId
import jp.co.crossmap.catalog.MultilingualText
import jp.co.crossmap.catalog.neo4j.GraphQueryRunner
import jp.co.crossmap.catalog.neo4j.GraphTransactionRunner
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.CrawledPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class Neo4jCatalogImporterTest {
    @Test
    fun dryRunComputesCountsWithoutWriting() = runBlocking {
        val transactions = ImportRecordingTransactions()
        val report = importer(transactions).import(catalog(), dryRun = true)
        assertTrue(transactions.queries.isEmpty())
        assertEquals(1, report.entityCounts.getValue("Church"))
        assertEquals(1, report.relationshipCounts.getValue("LOCATED_AT"))
    }

    @Test
    fun realImportUsesBoundedParameterizedMergeAndCompletesRun() = runBlocking {
        val transactions = ImportRecordingTransactions()
        importer(transactions).import(catalog())
        assertTrue(transactions.queries.first().contains("MERGE (run:ImportRun"))
        assertTrue(transactions.queries.any { it.contains("UNWIND ${'$'}rows AS row") })
        assertTrue(transactions.queries.any { it.contains("UNWIND ${'$'}churchIds AS churchId") })
        assertTrue(transactions.queries.last().contains("run.status = 'COMPLETED'"))
        assertTrue(transactions.queries.any { it.contains("MERGE (source)-[:LINKS_TO]->(target)") })
        assertTrue(transactions.parameters.all { parameters -> parameters.keys.none { it.contains("password", true) } })
        val churchProperties = transactions.parameters.asSequence()
            .flatMap { (it["rows"] as? List<*>)?.asSequence() ?: emptySequence() }
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { it["properties"] as? Map<*, *> }
            .first { "localizedNamesJson" in it }
        assertEquals("简体教会", churchProperties["name_zh_Hans"])
        assertTrue(churchProperties["localizedNamesJson"].toString().contains("zh-Hans"))
    }

    private fun importer(transactions: ImportRecordingTransactions) = Neo4jCatalogImporter(
        transactions = transactions,
        database = "crossmap-test",
        schemaVersion = 1,
        batchSize = 1,
    )

    private fun catalog() = NormalizedCatalogImport(
        sourcePath = "churches.json",
        sourceChecksum = "checksum",
        records = listOf(
            ChurchImportRecord(
                id = ChurchId("google:1"), googlePlaceId = "1",
                names = MultilingualText(mapOf("ja" to "教会", "zh-Hans" to "简体教会", "zh-Hant" to "繁體教會")),
                localizedNames = listOf(LocalizedName("zh-Hans", "简体教会"), LocalizedName("zh-Hant", "繁體教會")),
                primaryName = "教会", englishName = "Church", titleLanguages = listOf("ja"), denomination = null,
                category = null, address = "Tokyo", latitude = 35.0, longitude = 139.0,
                website = WebsiteImportRecord(
                    id = "website:1",
                    url = "https://example.com/",
                    normalizedUrl = "https://example.com/",
                    pages = listOf(
                        CrawledPage(
                            url = "https://example.com/",
                            contentHash = "hash",
                            outgoingLinks = listOf("https://example.com/j/"),
                        ),
                    ),
                ),
                email = null, socialAccounts = emptyList(), ministers = emptyList(), determinations = emptyList(),
                updatedAt = "2026-07-25T00:00:00Z", source = SourceMetadata("churches.json", "checksum", 0),
            ),
        ),
        rejectedRecords = emptyList(), warnings = emptyList(), duplicateCollapses = 0,
    )
}

private class ImportRecordingTransactions : GraphTransactionRunner {
    val queries = mutableListOf<String>()
    val parameters = mutableListOf<Map<String, Any?>>()
    override suspend fun <T> read(queryName: String, block: (GraphQueryRunner) -> T): T = block(
        object : GraphQueryRunner {
            override fun query(cypher: String, parameters: Map<String, Any?>): List<Map<String, Any?>> =
                listOf(mapOf("ids" to emptyList<String>()))
        },
    )
    override suspend fun <T> write(queryName: String, block: (GraphQueryRunner) -> T): T = block(
        object : GraphQueryRunner {
            override fun query(cypher: String, parameters: Map<String, Any?>): List<Map<String, Any?>> {
                queries += cypher
                this@ImportRecordingTransactions.parameters += parameters
                return emptyList()
            }
        },
    )
}
