package jp.co.crossmap.catalog.neo4j

import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledContentType
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SermonMetadata
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class StaticChurchCatalogSnapshot(
    val churches: List<ChurchRecord>,
    val sourceChecksum: String,
)

class Neo4jStaticChurchCatalogSource(
    private val transactions: GraphTransactionRunner,
    private val pageSize: Int = 250,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    init {
        require(pageSize in 1..1_000) { "Static catalog page size must be between 1 and 1000" }
    }

    suspend fun read(): StaticChurchCatalogSnapshot {
        val metadata = transactions.read("static-catalog.metadata") { runner ->
            runner.query(
                """
                MATCH (run:ImportRun {status: 'COMPLETED'})
                RETURN run.sourceChecksum AS sourceChecksum
                ORDER BY run.completedAt DESC
                LIMIT 1
                """.trimIndent(),
            ).singleOrNull() ?: error("Neo4j catalog has no completed import")
        }
        val count = transactions.read("static-catalog.count") { runner ->
            (runner.query("MATCH (church:Church) RETURN count(church) AS count").single().getValue("count") as Number).toInt()
        }
        val churches = ArrayList<ChurchRecord>(count)
        for (offset in 0 until count step pageSize) {
            val rows = transactions.read("static-catalog.page") { runner ->
                runner.query(STATIC_DETAIL_QUERY, mapOf("offset" to offset, "limit" to pageSize))
            }
            churches += rows.map(::churchRecordFromRow)
        }
        check(churches.size == count) { "Static catalog projection count changed while reading: expected=$count actual=${churches.size}" }
        return StaticChurchCatalogSnapshot(
            churches = churches.sortedBy(ChurchRecord::id),
            sourceChecksum = metadata.getValue("sourceChecksum").toString(),
        )
    }

    internal fun churchRecordFromRow(row: Map<String, Any?>): ChurchRecord {
        val church = row.map("church")
        val location = row.map("location")
        val denomination = row.optionalMap("denomination")
        val website = row.optionalMap("website")
        val names = church.entries.asSequence()
            .filter { (key, value) -> key.startsWith("name_") && value is String && value.isNotBlank() }
            .map { (key, value) -> LocalizedName(key.removePrefix("name_"), value.toString()) }
            .sortedBy(LocalizedName::languageCode)
            .toList()
        val pages = row.listOfMaps("pages").map { page ->
            CrawledPage(
                url = page.string("url"),
                finalUrl = page.string("finalUrl", page.string("url")),
                title = page.string("title"),
                text = page.rawString("text"),
                fetchedAt = page.string("fetchedAt"),
                contentHash = page.string("contentHash"),
                status = page.number("status", 200).toInt(),
                error = page.nullableString("error"),
                contentType = page.nullableString("contentType")?.let(CrawledContentType::valueOf)
                    ?: CrawledContentType.WEBSITE_PAGE,
                sermon = page.nullableString("sermonJson")?.let { json.decodeFromString<SermonMetadata>(it) },
            )
        }.sortedWith(compareBy(CrawledPage::url, CrawledPage::fetchedAt))
        val socialProfiles = row.listOfMaps("socialAccounts").map { account ->
            SocialProfile(
                platform = SocialPlatform.valueOf(account.string("platform")),
                url = account.string("url"),
                handle = account.nullableString("handle"),
                displayName = account.nullableString("displayName"),
                description = account.nullableString("description"),
                discoveredAt = account.string("discoveredAt"),
                contentHash = account.nullableString("contentHash"),
            )
        }.sortedWith(compareBy({ it.platform.name }, SocialProfile::url))
        val ministers = row.listOfMaps("ministers").map { entry ->
            val person = entry.map("person")
            val role = entry.map("role")
            ChurchMinister(
                name = person.string("name"),
                localizedNames = person.nullableString("localizedNamesJson")
                    ?.let { json.decodeFromString<List<LocalizedName>>(it) }.orEmpty(),
                roleId = role.string("roleId"),
                roleName = role.string("roleName"),
                localizedRoleNames = role.nullableString("localizedRoleNamesJson")
                    ?.let { json.decodeFromString<List<LocalizedName>>(it) }.orEmpty(),
            )
        }.sortedWith(compareBy(ChurchMinister::name, ChurchMinister::roleId))
        val source = row.optionalMap("source")
        val determinations = source?.nullableString("determinationsJson")
            ?.let { json.decodeFromString<List<FieldDetermination>>(it) }.orEmpty()
        return ChurchRecord(
            id = church.string("id"),
            googleCid = church.nullableString("googlePlaceId"),
            name = church.string("primaryName"),
            englishName = church.string("englishName"),
            localizedNames = names,
            localizedDenominationNames = denomination?.localizedNames().orEmpty(),
            titleLanguages = church.stringList("titleLanguages"),
            denominationId = denomination?.nullableString("id"),
            category = church.nullableString("category"),
            address = church.string("address"),
            location = GeoPoint(location.number("latitude").toDouble(), location.number("longitude").toDouble()),
            websiteUrl = website?.string("url").orEmpty(),
            email = church.nullableString("email"),
            pages = pages,
            socialProfiles = socialProfiles,
            ministers = ministers,
            determinations = determinations,
            updatedAt = church.string("updatedAt"),
        )
    }

    companion object {
        private val STATIC_DETAIL_QUERY =
            """
            MATCH (church:Church)
            WITH church ORDER BY church.id SKIP ${'$'}offset LIMIT ${'$'}limit
            MATCH (church)-[:LOCATED_AT]->(location:Location)
            OPTIONAL MATCH (church)-[:BELONGS_TO_DENOMINATION]->(denomination:Denomination)
            CALL (church) {
                OPTIONAL MATCH (church)-[websiteLink:HAS_WEBSITE]->(website:Website)
                OPTIONAL MATCH (website)-[:HAS_PAGE]->(page:Webpage)
                WHERE page IS NULL OR page.id IN coalesce(websiteLink.pageIds, [])
                WITH website, page ORDER BY page.url, page.fetchedAt
                RETURN website, collect(properties(page)) AS pages
            }
            CALL (church) {
                OPTIONAL MATCH (church)-[accountLink:HAS_SOCIAL_ACCOUNT]->(account:SocialMediaAccount)
                WITH account, accountLink ORDER BY account.platform, account.normalizedUrl
                RETURN collect(
                    CASE WHEN account IS NULL THEN null
                    ELSE account {.*,
                        handle: accountLink.handle,
                        displayName: accountLink.displayName,
                        description: accountLink.description,
                        discoveredAt: accountLink.discoveredAt,
                        contentHash: accountLink.contentHash
                    } END
                ) AS socialAccounts
            }
            CALL (church) {
                OPTIONAL MATCH (person:Person)-[:HELD_ROLE]->(role:RoleEvent)-[:ROLE_AT]->(church)
                WITH person, role ORDER BY person.name, role.roleId
                RETURN collect(
                    CASE WHEN person IS NULL THEN null
                    ELSE {person: properties(person), role: properties(role)} END
                ) AS ministers
            }
            CALL (church) {
                OPTIONAL MATCH (church)-[:IMPORTED_FROM]->(source:SourceRecord)
                RETURN source ORDER BY source.recordIndex DESC LIMIT 1
            }
            RETURN properties(church) AS church,
                   properties(location) AS location,
                   properties(denomination) AS denomination,
                   properties(website) AS website,
                   pages, socialAccounts, ministers,
                   properties(source) AS source
            ORDER BY church.id
            """.trimIndent()
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
    get(key) as? Map<String, Any?> ?: error("Missing map '$key'")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.optionalMap(key: String): Map<String, Any?>? =
    (get(key) as? Map<String, Any?>)?.takeIf(Map<String, Any?>::isNotEmpty)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
    (get(key) as? List<*>)?.mapNotNull { it as? Map<String, Any?> }?.filter(Map<String, Any?>::isNotEmpty).orEmpty()

private fun Map<String, Any?>.string(key: String, default: String = ""): String = nullableString(key) ?: default
private fun Map<String, Any?>.rawString(key: String, default: String = ""): String = get(key)?.toString() ?: default
private fun Map<String, Any?>.nullableString(key: String): String? = get(key)?.toString()?.takeIf(String::isNotBlank)
private fun Map<String, Any?>.number(key: String, default: Number = 0): Number = get(key) as? Number ?: default
private fun Map<String, Any?>.stringList(key: String): List<String> = (get(key) as? List<*>)?.map(Any?::toString).orEmpty()
private fun Map<String, Any?>.localizedNames(): List<LocalizedName> = entries.asSequence()
    .filter { (key, value) -> key.startsWith("name_") && value is String && value.isNotBlank() }
    .map { (key, value) -> LocalizedName(key.removePrefix("name_"), value.toString()) }
    .sortedBy(LocalizedName::languageCode)
    .toList()
