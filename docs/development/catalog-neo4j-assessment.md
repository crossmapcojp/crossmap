# Catalog to Neo4j assessment

## Current Crossmap architecture

- Repository: `/home/joel/code/crossmap`.
- Modules: KMP `core` and `app:shared`; JVM `crawl`, `server`, and `cli`; Android `app:androidApp`.
- Kotlin 2.4.0, Ktor 3.5.1, kotlinx.serialization 1.11.0, coroutines 1.11.0, and JVM toolchain 24.
- `ChurchRecord` in `core` is the current logical catalog contract. It contains stable Crossmap and Google IDs, multilingual names, denomination data, title languages, address and coordinates, website/email, crawled pages, social profiles, ministers, field determinations, and an update timestamp.
- The crawl pipeline generates and mutates `resources/catalog/churches.json`. Search snapshots embed serialized `ChurchRecord` values in Lucene. The server, static-site generator, crawl reconciliation, and snapshot builder currently read the JSON file directly.
- The server constructs long-lived search engines and reloads them from snapshot manifests. There is no existing database, repository, DAO, dependency-injection framework, or graph abstraction.
- Runtime HTTP contracts return existing serializable response projections. They must remain independent of Neo4j driver types.

## Local Neo4j

- Neo4j Community Server 2026.06.0 and Cypher Shell 2026.06.0 are installed.
- The service is running locally; Bolt and HTTP are enabled by the packaged configuration.
- Authentication remains enabled. No Neo4j credentials are present in the process environment or repository configuration, so authenticated connectivity must be verified after `NEO4J_USERNAME` and `NEO4J_PASSWORD` are supplied externally.
- The implementation will use the official Java driver only. It will not introduce Neo4j-OGM, Spring Data Neo4j, embedded Neo4j, Docker, or Testcontainers.

## `cmj` ontology reviewed

- Read-only reference clone: `/home/joel/code/cmj`.
- Revision: `150696e954f068444e8afd08a9ac669dd26e4117` (clean worktree at assessment time).
- Reviewed the requested `Church.kt`, `Location.kt`, `MultilingualEntity.kt`, and `OnlineThing.kt`, plus their directly referenced annotations and entity/event/online types.
- Concepts to adopt: church, denomination, alliance, parish, hierarchical location, website, social account, multilingual values, people and role events, and domain-natural named relationships.
- Useful canonical directions include `BELONGS_TO_DENOMINATION`, `BELONGS_TO_ALLIANCE`, `HAS_CHURCH`, `LOCATED_AT`, `WITHIN`, `HAS_WEBSITE`, `HAS_SOCIAL_ACCOUNT`, and bounded person-role relationships.

## `cmj` persistence patterns not to copy

- Numeric `Long` IDs are replaced by stable Crossmap string IDs and typed wrappers.
- Abstract classes and enums are not persisted as Neo4j nodes.
- Large bidirectional object collections are replaced by canonical references plus repository queries.
- Recursive location containment is represented by stable references and bounded traversals, not recursively hydrated objects.
- Default empty relationship lists are avoided where they could mean either “loaded and empty” or “not loaded.”
- Generic participant/account/link relationships are replaced with specific relationship names where current data provides the semantics.
- The mapper will not perform recursive cascade saves, lazy loading, dirty tracking, automatic graph deletion, or unrestricted polymorphic hydration.
- Current minister data justifies bounded `Person` and `RoleEvent` support, but historical event graphs, posts, subscriptions, friends, followers, and arbitrary online-content graphs are deferred.

## Initial bounded graph

Initial node labels are `Church`, `Denomination`, `Location`, `Website`, `SocialMediaAccount`, `Person`, `RoleEvent`, `SourceRecord`, `ImportRun`, and `SchemaMigration`. Crawled pages remain bounded child data during the first migration unless query or integrity evidence justifies separate `Webpage` nodes.

`Church` is the aggregate entry point. Core scalar properties and translations are stored on the church node. Relationships are changed only by explicitly named repository operations. Endpoint reads use bounded projections and parameterized Cypher; no request path may hydrate the whole catalog graph.

## Migration boundary

The existing JSON decoder remains the database-independent import and rollback contract. Migration proceeds through deterministic normalization, batched idempotent writes, logical export, canonical parity comparison, and integrity checks inside the crawler/catalog-generation workflow. Static-site generation reads bounded church-detail projections from Neo4j and materializes them into localized `church.html` files. Ktor request handling never queries Neo4j: it consumes generated search snapshots and static files, and must start and serve requests after Neo4j is stopped.
