# Codex Implementation Prompt: Migrate Crossmap from `resources/catalog/churches.json` to Local Neo4j Community Server with a Small Object-Graph Mapper

## Mission

You are working in the locally cloned **Crossmap** repository on Ubuntu 24.

A local **Neo4j Community Server** is already installed on the development machine. Do not introduce Docker, Docker Compose, Testcontainers as a runtime dependency, Neo4j Aura, an embedded Neo4j database, Spring Data Neo4j, or Neo4j-OGM.

The current Crossmap application uses:

```text
resources/catalog/churches.json
```

as the intermediate canonical store for the whole church-related catalog. The JSON file is approximately 40 MB and its nested object graph is becoming difficult to manage.

There is also a separately cloned local repository named `cmj` containing an earlier Crossmap domain-model design, including at least:

```text
Church.kt
Location.kt
MultilingualEntity.kt
OnlineThing.kt
```

Use those model files as the primary ontology and design reference. Integrate the useful concepts into the current Crossmap repository, but do not copy them blindly. Adapt them to the current data, current server architecture, current Kotlin version, and a bounded object-graph mapping strategy.

Implement:

```text
Local Neo4j Community Server
+
official Neo4j Java Driver
+
readable Crossmap Kotlin domain model
+
small Crossmap-owned object-graph mapper
+
explicit Cypher repositories for nontrivial operations
+
one-time deterministic JSON importer
+
logical JSON exporter
+
schema migrations
+
integrity checks
+
Codex/Neo4j MCP-friendly diagnostics
```

The completed system must replace normal runtime reads from `resources/catalog/churches.json` with Neo4j-backed repository queries.

---

# Core architectural decision

Use a local Neo4j server reachable through Bolt, normally:

```text
bolt://localhost:7687
```

The Crossmap server and Codex Neo4j MCP tools should connect to the same database.

The runtime architecture should become:

```text
Crossmap Ktor/JVM server
        │
        │ Neo4j Java Driver
        ▼
Local Neo4j Community Server
        ▲
        │ Neo4j MCP / cypher-shell / Browser
        │
       Codex
```

Do not place Neo4j API classes inside KMP `commonMain`. Neo4j integration belongs only in JVM-specific modules or source sets.

---

# Non-negotiable rules

## Repository safety

1. Work only in the current local Crossmap checkout unless reading the separate `cmj` clone.
2. Treat `cmj` as read-only reference material unless explicitly instructed otherwise.
3. Do not modify unrelated application behavior.
4. Do not delete `resources/catalog/churches.json` during initial implementation.
5. Do not commit passwords, `.env` files, database files, transaction logs, dumps, or generated large exports.
6. Do not silently change existing HTTP response contracts.
7. Preserve existing tests and add new tests before removing legacy behavior.
8. Make small, reviewable commits or implementation phases.
9. Before destructive operations, create a logical export or database backup.
10. Never run unbounded destructive Cypher against a non-test database.

## Persistence rules

1. Do not use Neo4j internal node IDs as domain IDs.
2. Every persisted entity must have a stable application-level identifier.
3. Do not recursively save arbitrary object graphs.
4. Do not implement lazy-loading proxies.
5. Do not implement hidden dirty tracking.
6. Do not delete relationships merely because a partially loaded object omits them.
7. Do not make all reverse graph directions permanent in-memory object collections.
8. Do not load every church, website, person, event, location, and denomination into one in-memory graph at startup.
9. Use projections for endpoint-specific reads.
10. Use parameterized Cypher only.
11. Use explicit transaction boundaries.
12. Keep the mapper small and bounded.
13. Use explicit Cypher for imports, batch operations, schema work, integrity checks, and complex graph mutations.
14. Keep logical JSON import/export as the database-independent migration contract.

## Agent-access rules

1. Assume Codex may use Neo4j MCP for schema and data inspection.
2. Default Neo4j MCP access to read-only for diagnosis.
3. Enable write access only for explicit migration or repair tasks.
4. Add safe diagnostic queries and documentation.
5. Never expose production credentials in repository files.
6. Never implement an unrestricted public HTTP Cypher endpoint.

---

# Deliverables

Create or modify the project so that it contains the following logical components. Adjust exact module and package paths to match the actual repository.

```text
domain/catalog/
    EntityId.kt
    EntityRef.kt
    MultilingualText.kt
    Church.kt
    Denomination.kt
    DenominationAlliance.kt
    Parish.kt
    Location.kt
    Website.kt
    SocialMediaAccount.kt
    Event.kt
    RoleEvent.kt
    ...

persistence/neo4j/
    Neo4jConfig.kt
    Neo4jDriverFactory.kt
    Neo4jHealthCheck.kt
    Neo4jTransactionRunner.kt
    Neo4jSchemaMigrator.kt
    GraphMetadata.kt
    GraphMetadataRegistry.kt
    CrossmapGraphMapper.kt
    Neo4jValueConverters.kt
    Neo4jChurchRepository.kt
    Neo4jCatalogRepository.kt
    Neo4jIntegrityRepository.kt
    migrations/
        V001__initial_catalog_schema.cypher
        V002__...cypher

migration/catalog/
    LegacyChurchJsonReader.kt
    LegacyChurchNormalizer.kt
    ChurchCatalogImporter.kt
    ChurchCatalogImportReport.kt
    ChurchCatalogParityValidator.kt
    ChurchCatalogExporter.kt

application/catalog/
    ChurchCatalogService.kt
    ChurchQueryService.kt
    CatalogIntegrityService.kt

docs/development/
    neo4j-local.md
    neo4j-mcp.md
    catalog-json-migration.md

scripts/
    check-neo4j.sh
    catalog-import.sh
    catalog-export.sh
    catalog-integrity.sh
```

Do not force this exact directory structure if the repository already has a clear convention. Follow the existing architecture and document any deviations.

---

# Phase 0: Inspect everything before changing code

## 0.1 Inspect the Crossmap repository

Run:

```bash
pwd
git status --short
git branch --show-current

find . -maxdepth 4 -type f \
  \( -name 'settings.gradle.kts' \
  -o -name 'build.gradle.kts' \
  -o -name 'libs.versions.toml' \
  -o -name 'gradle.properties' \
  -o -name 'application.conf' \
  -o -name 'application.yaml' \
  -o -name 'application.yml' \
  -o -name '.env.example' \) \
  -print
```

Determine:

- repository root;
- Gradle modules;
- Kotlin version;
- JDK/toolchain version;
- KMP source sets;
- JVM server module;
- Ktor version and startup entry point;
- dependency injection or service-locator pattern;
- application lifecycle hooks;
- configuration-loading conventions;
- logging conventions;
- test framework;
- current catalog classes;
- current JSON serializer configuration;
- all code paths reading `resources/catalog/churches.json`;
- all endpoints depending on the loaded catalog;
- whether catalog data is cached in memory;
- whether the JSON file is generated, edited manually, or produced by import pipelines;
- current ID fields and external IDs;
- existing database or repository abstractions.

Use:

```bash
rg -n 'churches\.json|resources/catalog|catalog/churches' .
rg -n 'decodeFromString|decodeFromStream|kotlinx\.serialization|Json\s*\{' .
rg -n 'embeddedServer|Application\.module|ApplicationStarted|ApplicationStopping' .
rg -n 'class Church|data class Church|interface Church|ChurchDto|ChurchRecord' .
rg -n 'placeId|googlePlace|latitude|longitude|denomination|website|facebook|instagram|youtube' .
rg -n 'Repository|Dao|Store|DataSource|CatalogService' .
```

Identify the exact JSON root structure. Do not assume it is a simple list.

## 0.2 Locate the local `cmj` clone

Search:

```bash
find .. -maxdepth 4 -type d -name cmj -print
```

If multiple paths exist, select the clone that contains the requested model files and is a Git repository.

Inspect:

```bash
git -C <CMJ_PATH> status --short
git -C <CMJ_PATH> log -1 --oneline

find <CMJ_PATH> -type f \
  \( -name 'Church.kt' \
  -o -name 'Location.kt' \
  -o -name 'MultilingualEntity.kt' \
  -o -name 'OnlineThing.kt' \) \
  -print
```

Read those files completely, plus all directly referenced model and annotation files, including definitions of:

```text
NodeEntity
Relationship
Direction
Id
Properties
Person
NonePersonEntity
Thing
Event
RoleEvent
Occupation
Website
SocialMediaAccount
```

## 0.3 Analyze the supplied ontology

The prior `cmj` model includes concepts similar to:

```text
MultilingualEntity
├── Who
│   ├── Person
│   └── NonePersonEntity
├── Thing
│   └── OnlineThing
├── Location
└── Event
```

It also models:

```text
DenominationAlliance
Denomination
Parish
Church
Country
StateLevelMunicipality
CityLevelMunicipality
Website
Webpage
Blog
SocialMediaAccount
SocialPost
InstantEvent
DurationEvent
RoleEvent
```

Evaluate which of these concepts are already supported by current Crossmap data and which are future-facing.

Do not implement every future concept merely because a class exists. Establish a minimal supported graph for the current church catalog while retaining extension points.

## 0.4 Identify dangerous patterns in the old model

Explicitly document:

- bidirectional full-object references;
- circular relationships;
- large child collections;
- recursive location containment;
- duplicated semantic relationships;
- generic participant relationships where specific relationship names are clearer;
- abstract classes marked as node entities;
- enums marked as node entities;
- default empty lists that may look like authoritative loaded state;
- serialization cycles;
- nullable date/time bugs;
- JVM-only annotations in common code;
- relationship direction inconsistencies;
- constructors that force complete subgraphs;
- methods such as `getPastors()` that depend on the entire event graph already being loaded.

## 0.5 Create an assessment document

Before implementation, create:

```text
docs/development/catalog-neo4j-assessment.md
```

Include:

1. current repository architecture;
2. current JSON schema;
3. current catalog startup flow;
4. current endpoint dependencies;
5. actual `cmj` path and commit;
6. useful `cmj` concepts;
7. incompatible or unsafe old-model patterns;
8. proposed target model;
9. proposed graph labels and relationships;
10. ID strategy;
11. import strategy;
12. runtime cutover strategy;
13. rollback strategy;
14. testing strategy;
15. unresolved assumptions and the conservative decision taken.

Continue implementation after writing the assessment. Do not stop merely because assumptions exist.

---

# Phase 1: Verify local Neo4j Community Server

## 1.1 Inspect the installed server

Determine how Neo4j is installed and managed:

```bash
which neo4j || true
which cypher-shell || true
neo4j version || true
cypher-shell --version || true
systemctl status neo4j --no-pager || true
```

Find configuration and data directories using the installation’s documented layout. Do not hard-code assumptions without checking.

Likely areas may include:

```text
/etc/neo4j/
/var/lib/neo4j/
/var/log/neo4j/
```

but verify actual paths.

## 1.2 Verify connectivity

Use environment variables, not literal credentials:

```bash
export NEO4J_URI='bolt://localhost:7687'
export NEO4J_USERNAME='neo4j'
export NEO4J_PASSWORD='...'
export NEO4J_DATABASE='neo4j'
```

Test:

```bash
cypher-shell \
  -a "$NEO4J_URI" \
  -u "$NEO4J_USERNAME" \
  -p "$NEO4J_PASSWORD" \
  -d "$NEO4J_DATABASE" \
  "RETURN 'connected' AS status"
```

Do not continue until connectivity succeeds.

## 1.3 Check server compatibility

Record:

- Neo4j server version;
- Java runtime used by Neo4j;
- Bolt address;
- HTTP Browser address;
- active database name;
- APOC availability;
- whether authentication is enabled.

Check APOC safely:

```cypher
RETURN apoc.version() AS version
```

If unavailable, do not require APOC for the application’s core behavior. Document optional APOC installation for MCP schema introspection only.

## 1.4 Do not modify global server configuration unnecessarily

Only make configuration changes that are required and document them.

Do not:

- bind Bolt or HTTP to public interfaces;
- disable authentication;
- grant unrestricted procedures broadly without need;
- add GDS without a concrete requirement;
- store application secrets in `neo4j.conf`.

---

# Phase 2: Add Neo4j Java Driver configuration

## 2.1 Dependency

Add the official Neo4j Java Driver to the JVM server module.

Use the repository’s version catalog if present. Pin a stable driver version compatible with the installed Neo4j server.

Do not add:

```text
org.neo4j:neo4j
neo4j-ogm
spring-data-neo4j
```

The required dependency is the official Java Driver artifact.

## 2.2 Configuration model

Create a configuration type similar to:

```kotlin
data class Neo4jConfig(
    val uri: String,
    val username: String,
    val password: String,
    val database: String,
    val maxConnectionPoolSize: Int,
    val connectionAcquisitionTimeout: Duration,
    val connectionTimeout: Duration,
    val maxTransactionRetryTime: Duration,
)
```

Adapt to existing configuration conventions.

Read values from environment/application configuration:

```text
NEO4J_URI
NEO4J_USERNAME
NEO4J_PASSWORD
NEO4J_DATABASE
```

Optional settings may use safe defaults.

Do not log the password.

Add `.env.example` entries if such a file already exists. Otherwise document variables without committing a real `.env`.

## 2.3 Driver lifecycle

Create exactly one application-wide `Driver`.

Requirements:

- verify connectivity during startup;
- fail startup clearly when Neo4j is unavailable;
- close the driver exactly once during shutdown;
- do not create a new driver per request;
- do not expose the driver directly to HTTP route code.

Integrate with Ktor lifecycle hooks.

## 2.4 Health check

Implement a health-check operation using:

```cypher
RETURN 1 AS ok
```

Add it to the existing health endpoint or create an internal application health component consistent with existing conventions.

Distinguish:

- application alive;
- Neo4j reachable;
- expected schema version installed;
- catalog imported.

---

# Phase 3: Define a readable domain model

## 3.1 Preserve ontology, not old persistence behavior

Use `cmj` as the terminology and hierarchy reference, but design current classes to avoid massive cyclic in-memory graphs.

The domain layer must not depend on Neo4j driver classes.

## 3.2 Stable typed IDs

Prefer typed wrappers, adapted to project conventions:

```kotlin
@JvmInline
value class ChurchId(val value: String)

@JvmInline
value class DenominationId(val value: String)

@JvmInline
value class LocationId(val value: String)

@JvmInline
value class WebsiteId(val value: String)

@JvmInline
value class PersonId(val value: String)

@JvmInline
value class EventId(val value: String)
```

If existing JSON contains stable `Long` IDs, preserve them through typed wrappers instead of changing IDs unnecessarily.

Do not derive domain identity from Neo4j node IDs.

## 3.3 Entity references

Introduce bounded references:

```kotlin
data class EntityRef<ID>(
    val id: ID,
)
```

or dedicated ID properties.

Prefer:

```kotlin
data class Church(
    val id: ChurchId,
    val names: MultilingualText,
    val denominationId: DenominationId?,
    val allianceIds: Set<DenominationAllianceId>,
)
```

over:

```kotlin
data class Church(
    val denomination: Denomination?,
    val alliance: DenominationAlliance?,
)
```

when full hydration is not required.

## 3.4 Multilingual values

The old model used a translation map. Preserve that concept with a clear value type:

```kotlin
data class MultilingualText(
    val values: Map<String, String>,
)
```

Validate language-code keys according to current Crossmap conventions.

Decide and document how names are stored in Neo4j:

### Preferred initial representation

Store the translation map as a Neo4j map property only if the driver/server combination supports the exact value shape reliably and it remains queryable enough for current needs.

Otherwise use separate properties:

```text
name_ja
name_en
name_ko
name_zh
...
```

or `Name` nodes only if provenance/history requires independent identity.

Do not over-normalize multilingual names initially. Choose the representation based on actual search/query needs.

## 3.5 Minimal current graph model

Start with entities required by the current JSON and current endpoints.

Likely initial labels:

```text
Church
Denomination
DenominationAlliance
Parish
Location
Country
StateLevelMunicipality
CityLevelMunicipality
Website
SocialMediaAccount
SourceRecord
ImportRun
```

Add `Person`, `RoleEvent`, and other event types only if current data actually contains them or current application behavior requires them.

Future-facing classes may exist in the domain model without being imported yet, but do not create unused persistence complexity.

## 3.6 Relationship directions

Use domain-natural canonical directions.

Recommended examples:

```text
(:Church)-[:BELONGS_TO_DENOMINATION]->(:Denomination)
(:Church)-[:BELONGS_TO_ALLIANCE]->(:DenominationAlliance)
(:Parish)-[:BELONGS_TO_DENOMINATION]->(:Denomination)
(:Parish)-[:HAS_SUB_PARISH]->(:Parish)
(:Parish)-[:HAS_CHURCH]->(:Church)
(:Church)-[:LOCATED_AT]->(:Location)
(:Location)-[:WITHIN]->(:Location)
(:Church)-[:HAS_WEBSITE]->(:Website)
(:Church)-[:HAS_SOCIAL_ACCOUNT]->(:SocialMediaAccount)
(:Website)-[:HAS_PAGE]->(:Webpage)
(:Person)-[:HELD_ROLE]->(:RoleEvent)
(:RoleEvent)-[:ROLE_AT]->(:Church)
(:RoleEvent)-[:ROLE_AS]->(:Occupation)
```

Do not persist both `HAS_CHURCH` and `BELONGS_TO_DENOMINATION` unless both relationships have distinct semantics. Neo4j can traverse relationships in reverse.

## 3.7 Domain projections

Create endpoint-specific views rather than fully hydrated graphs:

```kotlin
data class ChurchSummary(...)
data class ChurchDetails(...)
data class ChurchMapMarker(...)
data class ChurchSearchDocument(...)
data class DenominationSummary(...)
data class CatalogStats(...)
```

Do not use one giant `Church` object for every endpoint.

---

# Phase 4: Implement small graph metadata

## 4.1 Purpose

The mapper exists to keep model classes readable and reduce repetitive scalar mapping. It is not a full OGM.

It may support:

- node label metadata;
- stable ID property;
- scalar property metadata;
- simple value converters;
- direct entity references;
- bounded one-hop relationship mapping;
- explicit projection construction.

It must not support:

- recursive cascade save;
- automatic graph deletion;
- lazy loading;
- arbitrary reflection traversal;
- change tracking;
- session identity maps;
- unrestricted polymorphic hydration.

## 4.2 Crossmap-owned annotations or metadata

If annotations improve readability, create small annotations such as:

```kotlin
@Target(AnnotationTarget.CLASS)
annotation class GraphNode(
    val primaryLabel: String,
)

@Target(AnnotationTarget.PROPERTY)
annotation class GraphId(
    val property: String = "id",
)

@Target(AnnotationTarget.PROPERTY)
annotation class GraphProperty(
    val name: String = "",
)

@Target(AnnotationTarget.PROPERTY)
annotation class GraphRelationship(
    val type: String,
    val direction: GraphDirection = GraphDirection.OUTGOING,
)
```

Do not force annotations if explicit metadata objects are more compatible with KMP.

An alternative:

```kotlin
object ChurchGraphMetadata : NodeMetadata<Church> {
    override val label = "Church"
    override val idProperty = "id"
    ...
}
```

Choose one approach after inspecting current modules.

## 4.3 Metadata registry

Create a registry that explicitly registers supported entity types.

Example:

```kotlin
class GraphMetadataRegistry(
    metadata: List<NodeMetadata<*>>,
)
```

Fail fast on duplicate labels or duplicate metadata registrations.

Do not scan the entire classpath at runtime.

## 4.4 Value converters

Implement explicit converters for:

- typed IDs;
- strings;
- booleans;
- integers and longs;
- doubles;
- nullable values;
- language maps;
- URL;
- timestamps;
- time zones;
- coordinates;
- bounded boxes if currently required;
- sets/lists of scalar values when Neo4j supports them.

Reject unsupported nested objects with a clear exception.

## 4.5 Mapper contract

Create a mapper with a bounded contract similar to:

```kotlin
interface CrossmapGraphMapper {
    fun <T : Any> toNodeProperties(
        value: T,
        metadata: NodeMetadata<T>,
    ): Map<String, Any?>

    fun <T : Any> fromRecord(
        record: Record,
        projection: GraphProjection<T>,
    ): T
}
```

or:

```kotlin
interface NodePropertyMapper<T> {
    fun toProperties(value: T): Map<String, Any?>
    fun fromProperties(properties: Map<String, Any?>): T
}
```

The mapper must not execute database calls by itself unless wrapped in a narrowly defined persistence operation.

Keep Cypher in repositories or query objects.

## 4.6 Generated metadata

Consider KSP only if:

- the project already uses KSP; or
- handwritten metadata becomes clearly repetitive.

Do not introduce KSP merely for elegance during the first migration.

Handwritten metadata is acceptable and easier to review.

---

# Phase 5: Neo4j transaction and repository infrastructure

## 5.1 Transaction runner

Create an application-owned transaction abstraction:

```kotlin
interface GraphTransactionRunner {
    suspend fun <T> read(
        block: suspend (QueryRunner) -> T,
    ): T

    suspend fun <T> write(
        block: suspend (QueryRunner) -> T,
    ): T
}
```

Adapt to driver API and coroutine conventions.

Requirements:

- specify the configured database;
- use managed transactions when appropriate;
- apply retry behavior only where safe;
- never retry non-idempotent external side effects;
- map Neo4j exceptions into clear application exceptions;
- log query names and durations, not credentials or full sensitive payloads.

## 5.2 Repository interfaces

Define domain-facing interfaces such as:

```kotlin
interface ChurchRepository {
    suspend fun findById(id: ChurchId): ChurchDetails?
    suspend fun findSummaryById(id: ChurchId): ChurchSummary?
    suspend fun listPage(page: PageRequest): Page<ChurchSummary>
    suspend fun findMapMarkers(bounds: GeoBounds?): List<ChurchMapMarker>
    suspend fun count(): Long
    suspend fun upsertCore(church: Church)
    suspend fun setDenomination(
        churchId: ChurchId,
        denominationId: DenominationId?,
    )
    suspend fun replaceWebsites(
        churchId: ChurchId,
        websites: List<Website>,
    )
}
```

Adjust methods to actual application needs.

## 5.3 Explicit Cypher

Use named query constants or `.cypher` resource files.

Example bounded upsert:

```cypher
MERGE (c:Church {id: $id})
SET c += $properties
RETURN c.id AS id
```

Relationship update:

```cypher
MATCH (c:Church {id: $churchId})
OPTIONAL MATCH (c)-[old:BELONGS_TO_DENOMINATION]->(:Denomination)
DELETE old
WITH c
MATCH (d:Denomination {id: $denominationId})
MERGE (c)-[:BELONGS_TO_DENOMINATION]->(d)
```

Handle nullable denomination with separate explicit branches. Do not interpolate labels, properties, IDs, or values into Cypher strings.

## 5.4 Bounded write semantics

Document each repository mutation.

For example:

```text
upsertCore(church)
- creates or updates only the Church node and scalar properties;
- does not recursively save related nodes;
- does not delete unspecified relationships.

replaceWebsites(churchId, websites)
- affects only HAS_WEBSITE relationships for the specified church;
- upserts explicitly supplied websites;
- removes only relationships within this explicitly named aggregate operation.
```

This is essential for human review and agent safety.

---

# Phase 6: Schema migrations

## 6.1 Do not rely on application startup ad hoc schema creation

Implement versioned schema migrations.

If the project already uses a Neo4j migration library compatible with the stack, evaluate it. Otherwise implement a small migration runner using explicit versioned Cypher resources and a metadata node.

Example metadata:

```text
(:CrossmapSchema {
    name: "catalog",
    version: 3,
    updatedAt: datetime(...)
})
```

Migration files:

```text
V001__initial_catalog_schema.cypher
V002__website_normalized_url.cypher
V003__source_record_constraints.cypher
```

## 6.2 Initial constraints and indexes

Create constraints based on actual IDs.

Likely examples:

```cypher
CREATE CONSTRAINT church_id_unique IF NOT EXISTS
FOR (c:Church)
REQUIRE c.id IS UNIQUE
```

Repeat for persisted entity labels.

Add indexes only for actual query predicates, such as:

```text
Church.googlePlaceId
Church.normalizedName
Website.normalizedUrl
Location.cityCode
Denomination.normalizedName
```

Do not create indexes speculatively.

## 6.3 Migration properties

Migrations must be:

- ordered;
- idempotently tracked;
- transactional where supported;
- fail-fast;
- logged;
- covered by integration tests;
- safe to run on every startup;
- prohibited from silently skipping failed versions.

---

# Phase 7: Implement the legacy JSON importer

## 7.1 Preserve the existing decoder

Reuse the current JSON models and serializer initially. Do not combine JSON decoding, normalization, and Neo4j persistence into one class.

Create three stages:

```text
Legacy JSON decoding
        ↓
Normalized import model
        ↓
Neo4j batch writer
```

## 7.2 Normalized import model

Create explicit import records:

```kotlin
data class ChurchImportRecord(
    val id: ChurchId,
    val names: MultilingualText,
    val latitude: Double?,
    val longitude: Double?,
    val googlePlaceId: String?,
    val denomination: DenominationImportRef?,
    val websites: List<WebsiteImportRecord>,
    val socialAccounts: List<SocialAccountImportRecord>,
    val source: SourceMetadata,
)
```

Use the actual fields from current JSON.

Normalization should handle:

- trimmed names;
- normalized URLs;
- normalized social URLs;
- language keys;
- empty strings to null where appropriate;
- duplicate URLs;
- duplicate social accounts;
- stable IDs;
- coordinate validation;
- invalid records;
- missing denomination references;
- provenance;
- deterministic ordering.

Do not silently discard invalid records. Record them in an import report.

## 7.3 Stable ID policy

Choose IDs in this priority:

1. existing stable Crossmap ID;
2. existing external stable ID such as Google Place ID where semantically valid;
3. deterministic namespace-based hash/UUID derived from stable source fields;
4. newly generated persistent ID only as a last resort.

Never generate a fresh random ID on every import.

Document collision behavior.

## 7.4 Import runs

Create an `ImportRun` node or equivalent metadata containing:

```text
importRunId
sourcePath
sourceSha256
startedAt
completedAt
status
recordCount
createdNodeCount
updatedNodeCount
relationshipCount
warningCount
errorCount
applicationVersion
schemaVersion
```

## 7.5 Idempotency

The importer must be idempotent:

```text
Import same file twice
→ same logical graph
→ no duplicate churches
→ no duplicate websites
→ no duplicate relationships
```

Use `MERGE` carefully with stable keys.

Do not use one giant transaction for the full 40 MB file.

## 7.6 Batch import

Use `UNWIND` batches.

Start with a conservative configurable batch size, for example 200–1000 records, based on actual payload size.

Example:

```cypher
UNWIND $churches AS church
MERGE (c:Church {id: church.id})
SET c += church.properties
```

Import related entity types in dependency-safe phases:

1. locations;
2. denomination alliances;
3. denominations;
4. parishes;
5. churches;
6. websites;
7. social accounts;
8. relationships;
9. source/provenance links;
10. import metadata.

Adjust based on actual JSON.

## 7.7 Import command

Expose import through a Gradle task or dedicated CLI entry point.

Preferred behavior:

```bash
./gradlew :<server-module>:importChurchCatalog \
  --args="--input resources/catalog/churches.json --database neo4j"
```

or an existing project CLI convention.

Required options:

```text
--input
--database
--dry-run
--batch-size
--fail-on-warning
--replace
--resume
--report
```

Not every option must be implemented if unsupported, but `--input`, `--dry-run`, and `--report` are required.

`--replace` must be explicitly destructive and guarded.

## 7.8 Import report

Write a machine-readable and human-readable report:

```text
build/reports/catalog-import/report.json
build/reports/catalog-import/report.md
```

Include:

- source checksum;
- counts by entity type;
- counts by relationship type;
- rejected records;
- warnings;
- duplicate collapses;
- unresolved references;
- duration;
- database/schema version;
- parity summary.

---

# Phase 8: Logical JSON exporter

## 8.1 Purpose

The Neo4j store files are not the portability contract.

Implement deterministic logical export.

## 8.2 Export format

Prefer a versioned, non-recursive format:

```json
{
  "formatVersion": 1,
  "exportedAt": "...",
  "schemaVersion": 1,
  "nodes": [
    {
      "id": "church:...",
      "labels": ["Church"],
      "properties": {}
    }
  ],
  "relationships": [
    {
      "type": "BELONGS_TO_DENOMINATION",
      "from": "church:...",
      "to": "denomination:...",
      "properties": {}
    }
  ]
}
```

Alternatively preserve the legacy JSON shape if downstream consumers require it, but also consider a graph-native export.

The export must:

- be deterministically ordered;
- use stable IDs;
- avoid internal Neo4j IDs;
- include schema/format version;
- support round-trip tests;
- avoid recursive cycles.

## 8.3 Export command

Provide:

```bash
./gradlew :<server-module>:exportChurchCatalog \
  --args="--output build/catalog/churches-export.json"
```

---

# Phase 9: Parity validation

## 9.1 Compare old and new systems before cutover

Implement a parity validator comparing:

```text
legacy JSON-derived normalized records
versus
Neo4j query results
```

Compare at minimum:

- church count;
- stable IDs;
- names/translations;
- coordinates;
- external IDs;
- denomination references;
- alliance references;
- websites;
- social accounts;
- location relationships;
- endpoint-visible fields.

## 9.2 Canonical comparison

Normalize ordering and irrelevant formatting before comparison.

Do not compare raw JSON byte-for-byte unless deterministic export is explicitly intended to match.

## 9.3 Parity report

Create:

```text
build/reports/catalog-parity/report.md
build/reports/catalog-parity/report.json
```

List missing, additional, and differing entities.

Cutover is prohibited until unexplained differences are zero or explicitly documented and approved by code comments/tests.

---

# Phase 10: Replace runtime JSON reads

## 10.1 Introduce a repository-backed service

Replace direct JSON access behind an interface.

Example:

```kotlin
interface ChurchCatalogSource {
    suspend fun findById(id: ChurchId): ChurchDetails?
    suspend fun search(query: ChurchSearchQuery): List<ChurchSummary>
    suspend fun mapMarkers(bounds: GeoBounds?): List<ChurchMapMarker>
}
```

Provide:

```text
LegacyJsonChurchCatalogSource
Neo4jChurchCatalogSource
```

temporarily during migration.

## 10.2 Feature flag

Add a temporary configuration:

```text
CATALOG_BACKEND=json
CATALOG_BACKEND=neo4j
```

Default to JSON until migration tests pass, then change development default to Neo4j.

Do not maintain dual writes.

## 10.3 Cutover sequence

1. import current JSON;
2. run parity validation;
3. run all tests;
4. run server with Neo4j backend;
5. test existing endpoints with WebClient;
6. compare representative responses;
7. change default backend to Neo4j;
8. retain JSON backend for one rollback period;
9. later remove normal JSON runtime loading;
10. keep importer/exporter.

## 10.4 No silent fallback

When configured for Neo4j, the application must not silently fall back to JSON if Neo4j is unavailable.

Fail clearly.

---

# Phase 11: HTTP and WebClient verification

Use the project’s existing web-client test setup.

Test representative flows:

- application startup;
- catalog health;
- church count;
- fetch church by ID;
- search by Japanese name;
- search by alternate-language name;
- geographic/map query;
- denomination filtering;
- website/social link rendering;
- missing church;
- malformed query;
- Neo4j unavailable behavior.

Where practical, compare HTTP response data with direct Cypher results.

Do not expose arbitrary Cypher through a public route.

A development-only integrity route is acceptable only if:

- disabled by default;
- protected;
- returns predefined integrity checks;
- cannot accept arbitrary Cypher.

---

# Phase 12: Integrity checks for Codex and MCP

Create named, read-only integrity checks.

## 12.1 Basic counts

```cypher
MATCH (c:Church)
RETURN count(c) AS churchCount
```

## 12.2 Missing stable IDs

```cypher
MATCH (n)
WHERE n.id IS NULL
RETURN labels(n) AS labels, count(*) AS count
```

## 12.3 Duplicate church IDs

The uniqueness constraint should prevent this, but retain a diagnostic query.

## 12.4 Missing names

```cypher
MATCH (c:Church)
WHERE c.nameJa IS NULL AND c.nameEn IS NULL
RETURN c.id
LIMIT 100
```

Adapt to chosen multilingual storage.

## 12.5 Invalid coordinates

```cypher
MATCH (c:Church)
WHERE c.latitude < -90 OR c.latitude > 90
   OR c.longitude < -180 OR c.longitude > 180
RETURN c.id, c.latitude, c.longitude
```

## 12.6 Multiple denomination relationships

```cypher
MATCH (c:Church)-[:BELONGS_TO_DENOMINATION]->(d:Denomination)
WITH c, count(d) AS denominationCount
WHERE denominationCount > 1
RETURN c.id, denominationCount
```

Only apply if current domain cardinality is zero-or-one.

## 12.7 Orphan websites

```cypher
MATCH (w:Website)
WHERE NOT ()-[:HAS_WEBSITE]->(w)
RETURN w.id, w.url
LIMIT 100
```

## 12.8 Duplicate normalized URLs

```cypher
MATCH (w:Website)
WHERE w.normalizedUrl IS NOT NULL
WITH w.normalizedUrl AS url, collect(w.id) AS ids
WHERE size(ids) > 1
RETURN url, ids
```

## 12.9 Missing location

```cypher
MATCH (c:Church)
WHERE NOT (c)-[:LOCATED_AT]->(:Location)
RETURN c.id
LIMIT 100
```

Only treat as an error if current data requires every church to have a location.

## 12.10 Import provenance

Find imported nodes without source references if provenance is implemented.

Create a `CatalogIntegrityService` and CLI/script that runs all applicable checks and exits nonzero on errors.

Produce:

```text
build/reports/catalog-integrity/report.md
build/reports/catalog-integrity/report.json
```

---

# Phase 13: Neo4j MCP documentation

Create:

```text
docs/development/neo4j-mcp.md
```

Document installation of Neo4j agent skills:

```bash
codex plugin marketplace add neo4j-contrib/neo4j-skills
codex plugin add neo4j-skills@neo4j-skills-marketplace
```

Document environment configuration:

```text
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=...
NEO4J_DATABASE=neo4j
NEO4J_READ_ONLY=true
```

Default to read-only.

Document a smoke test:

```cypher
RETURN 'connected' AS status
```

Document safe example Codex tasks:

```text
Inspect the live Neo4j schema and compare it with the Crossmap graph metadata.
Do not modify data. Report missing constraints, unexpected labels,
relationship cardinality violations, and orphaned nodes.
```

```text
Run the catalog integrity checks through Neo4j MCP, compare the results
with the HTTP endpoint output, and add regression tests for every confirmed bug.
Do not write to the database.
```

Document that write mode must be explicitly enabled only for controlled migration work.

---

# Phase 14: Tests

## 14.1 Unit tests

Test:

- stable ID generation;
- URL normalization;
- multilingual value conversion;
- graph property mapping;
- unsupported nested value rejection;
- metadata registration;
- import normalization;
- deterministic ordering;
- parity comparison.

## 14.2 Integration tests

Because a local Neo4j server is already installed, support integration tests against a dedicated test database if the installed Community edition and version support the required database arrangement.

If multi-database support is unavailable or unsuitable, use one of these, in priority order:

1. a separately configured local test Neo4j instance/port;
2. a dedicated database with strict cleanup;
3. a test-only unique label/property namespace;
4. an opt-in integration-test profile.

Do not point destructive integration tests at the developer’s main catalog database.

Require explicit test settings:

```text
NEO4J_TEST_URI
NEO4J_TEST_USERNAME
NEO4J_TEST_PASSWORD
NEO4J_TEST_DATABASE
```

Skip integration tests with a clear message when test configuration is absent, unless CI explicitly requires them.

Do not introduce Docker solely for tests unless the repository owner later requests it.

## 14.3 Integration scenarios

Test:

- schema migration from empty database;
- repeated migration;
- church upsert;
- relationship creation;
- bounded relationship replacement;
- import first run;
- identical second import;
- changed record import;
- failed reference behavior;
- rollback on transaction failure;
- export;
- round trip;
- repository projections;
- integrity checks.

## 14.4 Regression tests

Every bug discovered during import/parity/MCP inspection must receive a regression test before correction is considered complete.

---

# Phase 15: Logging, metrics, and observability

Log:

- driver startup and database name;
- connectivity result;
- schema migration versions;
- import run ID;
- source checksum;
- batch progress;
- warnings/errors;
- query name and duration for slow operations;
- parity summary;
- integrity summary.

Do not log:

- Neo4j password;
- full church records unnecessarily;
- sensitive personal data;
- huge query parameter payloads.

If the project already uses metrics, add:

```text
neo4j.query.duration
neo4j.query.errors
catalog.import.records
catalog.import.errors
catalog.integrity.failures
```

Do not add a new metrics framework solely for this migration.

---

# Phase 16: Performance requirements

The old implementation may load all JSON into memory. The new implementation should avoid replacing it with another whole-catalog load.

Requirements:

- paginated queries;
- projections;
- batch writes;
- indexes for actual predicates;
- no `MATCH (n) RETURN n` in runtime code;
- no unbounded relationship expansion;
- no N+1 queries for church lists;
- no full HTML content returned in summary projections;
- query profiling for slow endpoints.

For relevant queries, run:

```cypher
EXPLAIN ...
```

and when safe:

```cypher
PROFILE ...
```

Record important findings in documentation.

---

# Phase 17: Security

1. Keep Neo4j bound to localhost for development unless explicitly required otherwise.
2. Keep authentication enabled.
3. Do not commit credentials.
4. Use least privilege where supported.
5. MCP defaults to read-only.
6. Do not expose arbitrary Cypher over HTTP.
7. Parameterize all values.
8. Validate pagination limits.
9. Restrict import/export file paths.
10. Prevent path traversal.
11. Ensure destructive commands require explicit flags.
12. Clearly label development-only tooling.

---

# Phase 18: Documentation

Create or update:

## `docs/development/neo4j-local.md`

Include:

- installed Neo4j version;
- required Java version;
- start/stop/status commands;
- Browser URL;
- Bolt URI;
- `cypher-shell` connection;
- configuration locations;
- log locations;
- data locations;
- environment variables;
- password reset procedure reference;
- health check;
- schema migration command;
- import command;
- export command;
- integrity command;
- backup guidance;
- upgrade guidance.

## `docs/development/catalog-json-migration.md`

Include:

- old architecture;
- new architecture;
- model mapping;
- importer phases;
- ID strategy;
- graph labels;
- relationship types;
- parity validation;
- cutover;
- rollback;
- when `churches.json` can eventually be removed from runtime resources.

## `docs/development/crossmap-graph-model.md`

Include:

- supported nodes;
- supported relationships;
- cardinalities;
- ownership semantics;
- bounded mapper behavior;
- projection strategy;
- future model concepts from `cmj`;
- concepts intentionally deferred.

Use Mermaid diagrams where useful.

---

# Phase 19: Scripts and developer commands

Create scripts consistent with repository conventions.

## `scripts/check-neo4j.sh`

Must:

- verify required environment variables;
- call `cypher-shell`;
- execute `RETURN 'connected'`;
- report database/schema/import status;
- avoid printing password.

## `scripts/catalog-import.sh`

Must:

- require input path;
- support dry run;
- call the project import entry point;
- produce report paths.

## `scripts/catalog-export.sh`

Must:

- require output path;
- refuse unsafe overwrite unless explicit;
- call the exporter.

## `scripts/catalog-integrity.sh`

Must:

- run predefined read-only checks;
- produce report;
- exit nonzero on errors.

Use `set -euo pipefail`.

---

# Phase 20: Cutover acceptance criteria

Do not declare completion until all applicable criteria pass.

## Build

- [ ] Gradle build passes.
- [ ] Existing tests pass.
- [ ] New unit tests pass.
- [ ] Configured integration tests pass.
- [ ] No Neo4j types leak into KMP common code.

## Neo4j

- [ ] Driver connects to local server.
- [ ] Startup health check works.
- [ ] Schema migrations are repeatable.
- [ ] Required constraints exist.
- [ ] Required indexes exist.
- [ ] Driver closes cleanly.

## Import

- [ ] Existing JSON decodes successfully.
- [ ] Dry run works.
- [ ] Full import completes.
- [ ] Import report is generated.
- [ ] Second identical import produces no duplicates.
- [ ] Stable IDs remain stable.
- [ ] Invalid records are reported.
- [ ] Source checksum is stored.

## Parity

- [ ] Church counts match.
- [ ] Stable ID sets match.
- [ ] Endpoint-visible fields match.
- [ ] Website/social relationships match.
- [ ] Denomination/location relationships match.
- [ ] Every intentional difference is documented.

## Runtime

- [ ] Normal catalog reads use Neo4j.
- [ ] No full `churches.json` load occurs in Neo4j mode.
- [ ] Existing HTTP contracts remain compatible.
- [ ] Representative WebClient queries pass.
- [ ] Neo4j unavailability fails clearly.
- [ ] No silent JSON fallback occurs.

## Mapper

- [ ] Model classes are readable.
- [ ] Mapper supports only documented bounded operations.
- [ ] No recursive cascade save exists.
- [ ] No lazy proxies exist.
- [ ] No hidden relationship deletion exists.
- [ ] Unsupported mapping fails explicitly.

## Export and rollback

- [ ] Logical export works.
- [ ] Export is deterministic.
- [ ] Export excludes Neo4j internal IDs.
- [ ] Rollback procedure is documented.
- [ ] Legacy JSON source remains available during rollout.

## Agent diagnostics

- [ ] MCP documentation exists.
- [ ] Read-only MCP configuration is documented.
- [ ] Integrity queries exist.
- [ ] Codex can inspect schema and data without application code changes.

---

# Required implementation sequence

Follow this exact high-level order unless repository facts require a documented adjustment:

1. Inspect Crossmap and `cmj`.
2. Write assessment document.
3. Verify local Neo4j server and connectivity.
4. Add driver configuration and lifecycle.
5. Add schema migration mechanism.
6. Define minimal domain model and projections.
7. Implement explicit graph metadata.
8. Implement small bounded mapper.
9. Implement transaction runner.
10. Implement Neo4j repositories.
11. Preserve and isolate legacy JSON decoder.
12. Implement normalized import model.
13. Implement dry-run importer.
14. Implement real batched importer.
15. Implement import reporting.
16. Implement logical exporter.
17. Implement parity validator.
18. Add temporary backend feature flag.
19. Run import into development database.
20. Run parity validation.
21. Fix discrepancies with regression tests.
22. Switch runtime reads to Neo4j in development.
23. Run WebClient verification.
24. Add integrity service and scripts.
25. Add MCP documentation.
26. Change default backend only after all acceptance criteria pass.
27. Keep legacy JSON rollback path temporarily.
28. Produce final implementation report.

---

# Required final report

At completion, create:

```text
docs/development/catalog-neo4j-implementation-report.md
```

Include:

- summary of changes;
- actual module/package paths;
- Neo4j and driver versions;
- `cmj` commit/path reviewed;
- model concepts adopted;
- model concepts deferred;
- schema labels;
- relationship types;
- constraints/indexes;
- importer behavior;
- import counts;
- parity results;
- integrity results;
- tests run and exact commands;
- WebClient checks performed;
- configuration variables;
- known limitations;
- rollback instructions;
- next recommended work.

Also print a concise terminal summary containing:

```text
Files changed
Commands run
Tests passed
Import report path
Parity report path
Integrity report path
Remaining risks
```

---

# Important design guidance

## Keep the readable object model

A human should be able to inspect the Kotlin model and understand:

- what a Church is;
- how it relates to a Denomination;
- how locations are represented;
- how multilingual names are represented;
- how websites and social accounts connect;
- how future events and pastors can be represented.

## Do not mistake object readability for automatic graph persistence

The mapper should help convert bounded objects and projections.

Important operations must remain explicit:

```text
save Church core properties
set Church denomination
replace Church websites
record RoleEvent
attach source evidence
```

Avoid ambiguous operations such as:

```text
save entire Church graph
```

## Prefer graph truth over duplicated object collections

Do not keep both:

```text
Denomination.churches: List<Church>
Church.denomination: Denomination
```

as permanently hydrated full objects.

Keep a canonical reference direction and use repository queries for reverse traversal.

## Preserve future extensibility

The old `cmj` ontology contains useful long-term concepts. Design labels and IDs so future work can add:

```text
Person
Pastor
RoleEvent
Occupation
FoundedEvent
TerminationEvent
Webpage
Blog
Post
Video
Book
Article
Sermon
School
MissionOrganization
```

without redesigning existing church IDs or core relationships.

Do not implement all of them now without current requirements.

---

# First action

Begin by inspecting the current Crossmap repository and locating the local `cmj` clone.

Do not write implementation code before producing:

```text
docs/development/catalog-neo4j-assessment.md
```

Then proceed phase by phase, running tests and verifying the live Neo4j database after each substantial persistence change.
