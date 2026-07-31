# Codex Implementation Prompt: Complete the Crossmap Neo4j Source-of-Truth Migration

## Repository

- Repository: `crossmapcojp/crossmap`
- Default branch: `master`
- Language and stack: Kotlin, Kotlin Multiplatform, Gradle, Neo4j, lucene-kmp, Ktor
- Work in the existing local checkout.
- Do not create a commit or push unless I explicitly ask.
- Preserve existing behavior unless this prompt explicitly changes the architecture.
- Do not merely write a design document. Inspect the repository, implement the migration, update tests and documentation, and run the relevant verification commands.

---

## Product and architectural context

Crossmap is evolving from a **church search engine** into a broader **Christianity search engine**.

The catalog will eventually represent entities and relationships such as:

- churches;
- denominations;
- people and pastors;
- role and employment history;
- books and authors;
- sermons and preachers;
- news articles and the entities mentioned by them;
- church renames;
- church splits;
- church mergers;
- historical predecessor and successor organizations;
- schools, seminaries, publishers, ministries, and other Christian organizations.

A flat `List<ChurchRecord>` stored in `resources/catalog/churches.json` was appropriate for the original church-only system, but it is not a sustainable canonical representation for this graph-oriented domain.

Neo4j was introduced specifically to become the canonical catalog.

The repository is currently in a partially migrated state:

- `resources/catalog/churches.json` is still described and used as the canonical church catalog.
- crawler and cleanup workflows still read and replace `churches.json`;
- `SnapshotBuilder` still reads `churches.json` and hashes its bytes;
- server snapshot freshness checks still compare against `churches.json`;
- a legacy JSON-to-Neo4j importer exists;
- static church-page generation already reads Neo4j;
- Neo4j export and parity commands exist;
- the importer currently upserts current church IDs but does not reliably remove `Church` nodes absent from a later full catalog;
- some multilingual values are stored as direct `name_*` properties, while person and role names are still serialized into JSON string properties.

This creates two operational sources for downstream products and makes stale divergence possible.

The goal of this work is to **finish the migration so Neo4j is the only mutable canonical catalog**.

---

# Non-negotiable decisions

## 1. Neo4j is the sole source of truth

After the migration:

- canonical entities and graph relationships are stored in Neo4j;
- crawler, cleanup, reconciliation, and editorial-application workflows commit their resolved results to Neo4j;
- no production workflow treats `resources/catalog/churches.json` as authoritative;
- Lucene snapshots derive from Neo4j;
- static pages derive from Neo4j;
- generated API/detail artifacts derive from Neo4j;
- any JSON church catalog is an explicit generated projection, compatibility export, bootstrap artifact, or diagnostic artifact only.

Do not preserve a dual-authority design.

## 2. Use direct multilingual properties on entities

Use **Option A: properties on the entity**.

Examples:

```text
name_ja
name_en
name_ko
name_pt
name_id
name_zh_Hans
name_zh_Hant
```

Do not create `LocalizedName` nodes.

Do not introduce Redis or another datastore for names.

Do not store canonical multilingual names as serialized JSON maps or JSON lists.

The same convention must be reusable for future named entities such as:

```text
Church
Denomination
Person
Book
Sermon
Article
Organization
School
Publisher
```

Only implement current entity support now, but design the naming utility so later labels can use the same convention.

## 3. Canonical data stores only resolved published values

The canonical Neo4j entity should contain the final resolved name that Crossmap publishes for a language.

The following belong in intermediate logs, evidence files, review artifacts, or translation-run reports rather than as separate canonical name variants:

- an unofficial candidate found on social media;
- a rejected translation;
- rule translation candidates;
- LLM translation candidates;
- conflicting source assertions;
- superseded automatic attempts;
- comparison and scoring details.

Historically meaningful domain facts are different. A real organization rename or former official name may later be modeled as a historical event. Do not confuse actual church history with translation-pipeline candidate history.

Preserve existing cleanup/evidence/review files that are required to reproduce editorial decisions. Do not expand Neo4j into a candidate/provenance graph as part of this migration.

## 4. Use correct language tags

Use BCP 47 language tags at the model and application boundary.

Required distinctions include:

```text
ja
en
ko
pt
id
zh-Hans
zh-Hant
```

Do not use `cn` as a language code.

Neo4j property names cannot contain the hyphen convention conveniently in dynamic Cypher property access, so use a deliberate reversible storage suffix mapping:

```text
zh-Hans -> zh_Hans
zh-Hant -> zh_Hant
```

Do not use `substringBefore('-')`; that would collapse `zh-Hans` and `zh-Hant` into the same `zh` property.

## 5. Keep Neo4j out of the Ktor request-time runtime

Preserve the current architectural boundary:

- Neo4j is a crawler/build-time catalog;
- the Ktor server does not open a Neo4j connection while serving requests;
- mobile clients and the CLI use generated lucene-kmp snapshots;
- the static site consists of generated assets;
- Neo4j being unavailable must not break an already-built deployed runtime.

## 6. Large content is outside this migration

Do not introduce article bodies, books, sermon transcripts, archived HTML, or binary content into this migration.

This task is about canonical catalog authority, multilingual entity properties, graph writes, and generated projections.

---

# Required final architecture

The final data flow must be equivalent to:

```text
raw inputs / cached websites / directories / review decisions
                              |
                              v
              crawl, cleanup, translation, resolution
                              |
                              v
                  committed Neo4j catalog revision
                 /               |                 \
                v                v                  v
       Lucene snapshot     static-page data     compatibility export
                |                |                  |
                v                v                  v
       CLI/app/Ktor API     Cloudflare site      diagnostic JSON
```

All generated artifacts from one build must identify the same committed Neo4j catalog revision.

`churches.json` must no longer sit between canonical writes and only one of the downstream consumers.

---

# Current code areas to inspect first

Before editing, inspect at least these files and all callers/usages:

```text
README.md

catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/CatalogSchemaMigrator.kt
catalog/src/main/resources/catalog-migrations/V001__initial_catalog_schema.cypher
catalog/src/main/kotlin/jp/co/crossmap/catalog/importer/LegacyJsonChurchCatalogSource.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/importer/Neo4jCatalogImporter.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/Neo4jStaticChurchCatalogSource.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/export/CatalogLogicalExporter.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/export/CatalogParityValidator.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/integrity/CatalogIntegrityService.kt
catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/Neo4jDriverManager.kt

crawl/src/main/kotlin/jp/co/crossmap/crawl/CatalogNeo4jCommands.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/Main.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/SnapshotBuilder.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/GoogleSavedPlacesCleanupWorkflow.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/PostCrawlCleanup.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/ChurchEnglishNameResolver.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/GoogleSocialDataMergePipeline.kt
crawl/src/main/kotlin/jp/co/crossmap/crawl/denomination/
crawl/build.gradle.kts

server/src/main/kotlin/jp/co/crossmap/StaticSiteGeneratorCli.kt
server/src/main/kotlin/jp/co/crossmap/Application.kt
server/build.gradle.kts

core/INDEX.md
core/SEARCH.md
docs/development/neo4j-local.md
scripts/catalog-import.sh
```

Also run repository-wide searches before editing:

```sh
rg -n 'churches\.json|LegacyJsonChurchCatalogSource|primaryName|englishName|localizedNamesJson|localizedRoleNamesJson|name_[A-Za-z_]+|sourceSha256|sourceChecksum|ImportRun' .
```

Classify every `churches.json` read or write as one of:

1. legacy bootstrap/import;
2. explicit generated export;
3. test fixture;
4. invalid production dependency that must be removed.

Write this inventory in your progress report before making large changes.

---

# Implementation strategy

Implement this in coherent steps. Keep the project compiling and tests meaningful after each major step.

Do not perform an uncontrolled rewrite of the whole crawler at once.

Prefer small repository/service abstractions and adapt callers incrementally.

---

## Step 1: Establish a central multilingual property convention

Create a single shared Kotlin abstraction for supported canonical entity-name languages and Neo4j property mapping.

Use an appropriate current module that can be depended on by both `catalog` and consumers without introducing a dependency cycle. Prefer `core` for the public language-tag definition and `catalog` for Neo4j-specific property helpers if that separation is cleaner.

The abstraction must provide:

```kotlin
languageTag: String
neo4jPropertySuffix: String
neo4jNameProperty: String
```

Expected examples:

```text
ja       -> ja       -> name_ja
en       -> en       -> name_en
ko       -> ko       -> name_ko
pt       -> pt       -> name_pt
id       -> id       -> name_id
zh-Hans  -> zh_Hans  -> name_zh_Hans
zh-Hant  -> zh_Hant  -> name_zh_Hant
```

Requirements:

- exact, reversible mapping;
- reject unsupported malformed tags instead of silently truncating them;
- preserve existing supported-language ordering where it affects deterministic output;
- generic helpers for converting between:
  - `List<LocalizedName>`;
  - `Map<String, String>`;
  - Neo4j `name_*` properties;
- ignore blank names;
- deterministic duplicate handling;
- never collapse Simplified and Traditional Chinese;
- unit tests for all language mappings.

Do not scatter literal language-property construction throughout importers and query readers.

Replace code such as:

```kotlin
"name_$language"
languageCode.substringBefore('-')
```

with the central mapping.

---

## Step 2: Add a forward-only Neo4j schema migration

Do not edit `V001__initial_catalog_schema.cypher`.

Add a new migration, likely:

```text
V002__canonical_catalog_revision_and_multilingual_names.cypher
```

Register it in `CatalogSchemaMigrator.DEFAULT_MIGRATIONS`.

Introduce the minimum metadata needed to identify a committed canonical catalog revision.

A reasonable model is:

```text
(:CatalogState {
    name: "canonical",
    currentRevisionId: "...",
    currentRevisionSequence: 123,
    currentContentHash: "...",
    updatedAt: datetime()
})

(:CatalogRevision {
    id: "...",
    sequence: 123,
    status: "BUILDING" | "COMMITTED" | "FAILED",
    contentHash: "...",
    startedAt: datetime(),
    committedAt: datetime(),
    failure: "..."
})
```

Use another equivalent design only if it is simpler and equally safe.

Add uniqueness constraints for stable revision identity and the singleton catalog-state key.

Migrate existing multilingual fields:

- ensure every `Church` has canonical `name_ja` and `name_en` where the old `primaryName` and `englishName` were populated;
- preserve all existing valid `name_*` properties;
- do not overwrite a nonblank direct property with an inferior compatibility field;
- convert current `Person.localizedNamesJson` into direct `name_*` properties in Kotlin migration/application code if Cypher JSON parsing is impractical;
- convert current role localized names away from `localizedRoleNamesJson`;
- for a `RoleEvent`, use a consistent direct-property prefix such as `roleName_ja`, `roleName_en`, etc., unless the existing domain model supports a proper named `Role` node. Do not introduce a broad Role redesign in this task.

Compatibility fields may temporarily remain during the migration, but final canonical readers and writers must use the direct multilingual properties. Remove obsolete duplicated canonical fields when safe, or clearly mark and test them as generated compatibility aliases rather than independent values.

Add migration tests:

- migration order;
- checksum immutability;
- idempotent second execution;
- existing names preserved;
- Japanese and English backfilled;
- `zh-Hans` and `zh-Hant` remain distinct;
- old JSON-string multilingual fields are no longer required by canonical projection code.

---

## Step 3: Introduce canonical catalog read/write interfaces

Create use-case-oriented abstractions rather than one enormous generic DAO.

At minimum, provide equivalents of:

```kotlin
interface CanonicalChurchCatalogReader {
    suspend fun readCommittedSnapshot(): CanonicalChurchCatalogSnapshot
}

interface CanonicalChurchCatalogWriter {
    suspend fun replaceChurchCatalog(
        expectedRevision: CatalogRevisionToken?,
        churches: List<ChurchRecord>,
        operation: CatalogOperationMetadata,
    ): CatalogCommitResult
}

interface CatalogRevisionReader {
    suspend fun currentCommittedRevision(): CatalogRevision
}
```

Names may differ, but responsibilities must be explicit.

`CanonicalChurchCatalogSnapshot` must include:

```kotlin
churches: List<ChurchRecord>
revisionId: String
revisionSequence: Long
contentHash: String
```

The content hash must be calculated from a deterministic logical projection, not from incidental Neo4j record order.

Requirements:

- stable sorting;
- stable serialization;
- same logical catalog produces the same hash;
- database timestamps and internal Neo4j IDs do not affect the hash;
- reads use only a committed revision;
- generation fails clearly if no committed catalog exists;
- no code should guess the current revision from “latest completed legacy import.”

Implement Neo4j-backed versions.

Keep `Neo4jStaticChurchCatalogSource` as a compatibility facade temporarily if useful, but converge static and search projections on the same canonical reader/revision model.

---

## Step 4: Implement safe authoritative replacement and deletion semantics

The existing legacy importer upserts records but does not reliably delete `Church` nodes omitted from a later complete source snapshot.

Fix this for full-catalog replacement.

Requirements:

- a full replacement treats the supplied church ID set as authoritative for church records managed by this pipeline;
- a church removed from the replacement input is absent from the next committed canonical snapshot;
- relationships and managed dependent nodes are cleaned safely;
- do not accidentally delete future non-church graph entities that are not owned by the church-catalog replacement workflow;
- mark or otherwise identify nodes managed by this workflow if ownership boundaries are needed;
- delete or detach obsolete managed `Location` nodes;
- delete unreferenced managed `Website`, `Webpage`, `SocialMediaAccount`, and current imported `RoleEvent`/`Person` records according to clear ownership rules;
- do not delete shared `Denomination` nodes simply because one church stopped referencing them;
- remove obsolete `name_*` properties when a language is intentionally removed from the new canonical entity, instead of leaving stale values from `SET +=`;
- prevent a failed replacement from becoming the current committed revision.

Prefer a revision workflow:

1. create `BUILDING` revision;
2. apply and validate changes;
3. calculate deterministic content hash;
4. mark revision `COMMITTED`;
5. atomically update `CatalogState`;
6. on failure mark the revision `FAILED` and leave the previously committed revision authoritative.

Use a single Neo4j transaction if practical for approximately the current catalog size. If chunking is required, ensure readers cannot observe a partially committed catalog as current.

Add tests proving:

- removing one church removes it from Neo4j canonical reads;
- stale names and relationships are removed;
- failed writes leave the old revision current;
- repeated identical replacement is deterministic;
- an expected-revision mismatch prevents lost updates;
- no orphaned managed nodes remain after replacement.

---

## Step 5: Retain the legacy JSON importer only as bootstrap tooling

The existing JSON importer is useful for the one-time transition and disaster/bootstrap scenarios, but it must no longer be the normal data pipeline.

Rename or clearly redefine commands so their purpose cannot be misunderstood.

Preferred command semantics:

```text
catalog-neo4j-bootstrap-from-legacy-json
catalog-neo4j-export-church-projection
catalog-neo4j-integrity
catalog-neo4j-status
```

It is acceptable to retain `catalog-neo4j-import` as a deprecated alias temporarily, but:

- its help must say it is a legacy/bootstrap command;
- it must call the new canonical writer;
- it must create a committed catalog revision;
- it must use authoritative replacement semantics;
- it must not remain a prerequisite for every crawl;
- parity against legacy JSON becomes a migration/diagnostic tool, not a permanent production gate.

Update `scripts/catalog-import.sh` accordingly or replace it with a clearly named bootstrap script.

Do not delete the original `resources/catalog/churches.json` until a verified Neo4j commit and backup/export exist.

---

## Step 6: Move crawler and cleanup canonical reads to Neo4j

Every workflow that currently starts from `resources/catalog/churches.json` must load the current committed Neo4j church projection instead.

Examples include:

- Google Saved Places promotion;
- denomination cleanup;
- English and future multilingual naming;
- social-account merge;
- website refresh workflows that update canonical church records;
- official-directory reconciliation;
- any manual override application that currently rewrites the catalog file.

Do not force every internal algorithm to issue individual Cypher queries.

A good migration shape is:

```text
read one committed ChurchRecord projection from Neo4j
-> run existing bounded in-memory/file-assisted algorithms
-> validate the complete resolved result
-> commit one authoritative replacement/delta to Neo4j
```

Temporary files under `build/`, `cache/`, or a unique run workspace are allowed as intermediate artifacts.

They must not be named or treated as the canonical repository catalog.

Refactor file-oriented APIs where practical:

```kotlin
run(catalog: List<ChurchRecord>, ...)
```

or:

```kotlin
run(input: CatalogWorkspace, ...)
```

instead of passing the canonical `resources/catalog/churches.json` path.

When a legacy stage is too risky to rewrite immediately, use a temporary generated workspace file sourced from the current Neo4j revision and import its final result back through the canonical writer in the same orchestrated command. This is acceptable only as an internal transition adapter; no durable source-of-truth authority may remain with that file.

Update `GoogleSavedPlacesCleanupWorkflow`:

- load existing church records from Neo4j;
- retain non-Google records from that snapshot;
- run its existing normalization, deduplication, website, denomination, social, and naming gates;
- when `promote=true`, commit to Neo4j through the canonical writer;
- `promote=false`/dry-run must not mutate Neo4j;
- stop atomically writing `resources/catalog/churches.json`;
- include the prior revision and resulting revision in the report.

Preserve current review decisions and deterministic/LLM resolution behavior.

Do not change translation quality rules merely to complete storage migration.

---

## Step 7: Store all current named graph entities with direct properties

Update canonical writers and readers for current labels.

### Church

Canonical names must come from direct properties.

At minimum:

```text
name_ja
name_en
name_ko
name_pt
name_id
name_zh_Hans
name_zh_Hant
```

Only write supported nonblank names.

The `ChurchRecord.name` and `ChurchRecord.englishName` compatibility fields may still be populated in generated projections from `name_ja` and `name_en`, but they must not be separate canonical authorities.

### Denomination

Replace ad hoc `substringBefore('-')` handling.

Write and read direct `name_*` properties using the central language registry.

### Person

Replace:

```text
name
localizedNamesJson
```

with direct canonical name properties.

If the current untagged `name` is Japanese, backfill `name_ja`. If its language is not guaranteed, inspect the domain assumptions and preserve compatibility without guessing incorrectly. Document the chosen rule.

Do not serialize the canonical multilingual names back into JSON.

### RoleEvent

Replace `localizedRoleNamesJson` with direct role-name properties such as:

```text
roleName_ja
roleName_en
roleName_ko
...
```

Keep `roleId` and role-history graph relationships.

Do not broaden this task into the full future pastor employment-history model unless required to preserve existing behavior.

### Future extensibility

Provide utility methods that can later apply `name_*` properties to Book, Sermon, Article, Organization, and other labels without adding a new storage system.

---

## Step 8: Make Lucene snapshot generation read Neo4j

Refactor `SnapshotBuilder`.

Remove:

```kotlin
Files.readAllBytes(resourcesRoot.resolve("catalog/churches.json"))
json.decodeFromString<List<ChurchRecord>>(...)
```

Instead:

- obtain `CanonicalChurchCatalogSnapshot` from the Neo4j reader;
- build all language indexes from that snapshot;
- keep existing website exclusion, denomination-name handling, geoname translation, address normalization, analyzer selection, zip generation, and atomic publication behavior;
- use the canonical revision’s deterministic content hash as the source hash;
- add explicit catalog revision fields to `IndexManifest`.

Preferred manifest additions:

```kotlin
catalogRevisionId: String
catalogRevisionSequence: Long
catalogContentHash: String
```

If `sourceSha256` must remain for compatibility, define it as the canonical catalog logical content hash and document that it is no longer a filesystem-byte hash.

Do not hash a generated compatibility JSON file.

Update `build-snapshot` and Gradle task wiring so Neo4j migration/health/current-revision checks run before generation.

The task must fail with a clear message when:

- Neo4j is unreachable during build;
- schema is outdated;
- no committed revision exists;
- catalog integrity fails;
- required names are missing.

It must not silently fall back to `churches.json`.

---

## Step 9: Unify static-page generation with the same revision

`StaticSiteGeneratorCli` already reads `Neo4jStaticChurchCatalogSource`.

Refactor it to use the same canonical snapshot/revision abstraction as Lucene generation.

Requirements:

- static pages and search snapshot built during one publication workflow use the same revision ID and content hash;
- static generation must not infer authority from the latest legacy `ImportRun`;
- page manifest records the canonical revision;
- static generation fails if the catalog changes between coordinated build stages, unless the build deliberately restarts from the new revision;
- keep generation read-only;
- preserve bounded queries and current parallel rendering;
- preserve all current localized pages and SEO metadata.

Add a coordinated publication Gradle task, for example:

```text
:server:publishCrossmapArtifacts
```

or an appropriately placed root task that:

1. verifies Neo4j schema;
2. reads/pins the current committed revision;
3. runs integrity checks;
4. builds the Lucene snapshot from that revision;
5. generates static pages from that revision;
6. verifies both manifests contain the same revision and content hash;
7. optionally exports a compatibility church projection;
8. fails if any revision mismatch is detected.

Do not make normal page generation run LLM cleanup.

Canonical mutation and artifact publication remain separate operations.

---

## Step 10: Remove runtime and build freshness checks based on `churches.json`

Inspect `server/src/main/kotlin/jp/co/crossmap/Application.kt` and related manifest logic.

Current code fingerprints or validates indexes/page manifests against `resources/catalog/churches.json`.

Replace this with generated-manifest consistency:

- runtime validates the search snapshot’s schema, files, archive hash, and internal catalog revision metadata;
- static page manifest and search manifest must agree when both are deployed;
- runtime must not require Neo4j;
- runtime must not require a canonical JSON catalog;
- `runCurrentIndex` continues to serve already generated artifacts;
- normal `server:run` may build from Neo4j first because that is a development build action, not a request-time dependency.

Remove `churches.json` from file fingerprints and up-to-date checks except for explicitly named legacy/bootstrap/export tasks.

Add tests for:

- valid matching manifests;
- revision mismatch rejection;
- content-hash mismatch rejection;
- stale schema rejection;
- successful runtime startup with no Neo4j and no `resources/catalog/churches.json`, provided generated artifacts exist.

---

## Step 11: Redefine JSON export

Retain an explicit logical church projection export because it is useful for:

- debugging;
- review;
- migration comparison;
- backups in a human-readable format;
- external consumers that need a flattened church list.

However, make its generated status unambiguous.

Preferred default output:

```text
build/generated/catalog/church-search-projection.json
```

or:

```text
build/reports/catalog-export/churches.json
```

Do not default to overwriting `resources/catalog/churches.json`.

The export must include revision metadata in a neighboring manifest, for example:

```json
{
  "catalogRevisionId": "...",
  "catalogRevisionSequence": 123,
  "catalogContentHash": "...",
  "generatedAt": "...",
  "churchCount": 9500,
  "fileSha256": "..."
}
```

The exported church list is a projection, not a complete future Christianity graph backup.

Update task names and descriptions to use words such as:

```text
projection
export
generated
compatibility
```

Do not call it the canonical catalog.

Decide whether the existing tracked `resources/catalog/churches.json` remains temporarily as a migration snapshot or is moved out of source control. Do not delete it automatically without:

1. a successful Neo4j bootstrap;
2. parity verification;
3. integrity verification;
4. a generated export;
5. documented Neo4j backup and restore instructions.

If it remains temporarily, add a prominent README statement that it is frozen legacy bootstrap input and must never be edited by normal workflows.

JSON itself cannot contain comments, so place the warning in documentation and task help.

---

## Step 12: Add backup, restore, and disaster-recovery guidance

Once Neo4j is canonical, the repository cannot rely on an unbacked local directory under `cache/neo4j-data`.

Update `docs/development/neo4j-local.md` with a clear operational distinction:

```text
canonical database
generated projection
legacy bootstrap file
intermediate logs
build artifacts
```

Document:

- how to back up/dump Neo4j;
- how to restore it;
- how to verify schema and catalog revision after restore;
- how to export a logical church projection;
- how to bootstrap from the frozen legacy JSON only when necessary;
- how to run integrity checks;
- how to build all publication artifacts from a pinned revision;
- which files are safe to delete;
- which files must never be treated as canonical.

Do not hardcode developer-specific filesystem paths in general documentation.

If Neo4j Community Edition limitations affect online backup commands, document the supported local/offline procedure used by this project rather than inventing an unavailable operation.

---

## Step 13: Update integrity validation

Extend `CatalogIntegrityService` so publication verifies at least:

- exactly one canonical `CatalogState`;
- referenced current revision exists and is `COMMITTED`;
- schema version matches expected version;
- every Church has a stable unique ID;
- every Church has required `name_ja`;
- every Church has required `name_en` under the current publication contract;
- each supported name property is a nonblank string when present;
- no unsupported malformed `name_*` property exists;
- `name_zh_Hans` and `name_zh_Hant` are independently supported;
- every Church has exactly one valid location under the current model;
- no managed orphan locations;
- no stale managed Church from an authoritative replacement;
- no canonical multilingual JSON blob remains on Person or RoleEvent;
- current revision count/hash agrees with a fresh deterministic canonical projection;
- current generated artifacts can identify the catalog revision.

Do not require every future language to be populated yet unless the existing publication requirement already makes it mandatory.

Create a distinct translation-completeness report for optional languages instead of making all optional languages fatal.

---

## Step 14: Update Gradle task semantics

Update task descriptions and dependencies to match reality.

Examples:

- `dataCleanup`: resolves names/fields and commits a new Neo4j revision;
- `buildSearchSnapshot`: reads the current committed Neo4j revision;
- `generateChurchPages`: reads a pinned committed Neo4j revision;
- `publishCrossmapArtifacts`: coordinated build from one revision;
- `catalogNeo4jBootstrapFromLegacyJson`: one-time/bootstrap operation;
- `catalogNeo4jExportChurchProjection`: generated diagnostic projection;
- `catalogNeo4jIntegrity`: checks canonical graph;
- `server:run`: builds current development artifacts from Neo4j, then starts Ktor;
- `server:runCurrentIndex`: starts without Neo4j and without rebuilding.

Avoid hidden mutation in read/build tasks.

A task named “generate”, “build”, “export”, “verify”, or “integrity” must not rewrite canonical entities.

Only explicit crawl/cleanup/promote/apply commands may commit catalog changes.

---

## Step 15: Update documentation and terminology

Update at least:

```text
README.md
docs/development/neo4j-local.md
core/INDEX.md
core/SEARCH.md
```

Replace statements such as:

```text
resources/catalog/churches.json: canonical church records
```

with the new architecture.

Document the direct multilingual property convention.

Include a table similar to:

| Language tag | Neo4j property |
|---|---|
| `ja` | `name_ja` |
| `en` | `name_en` |
| `ko` | `name_ko` |
| `pt` | `name_pt` |
| `id` | `name_id` |
| `zh-Hans` | `name_zh_Hans` |
| `zh-Hant` | `name_zh_Hant` |

Clearly state:

- Neo4j owns canonical current entity values and graph relationships;
- evidence, candidates, translation attempts, and review logs remain pipeline artifacts;
- generated Lucene/static/JSON artifacts are projections;
- Ktor does not query Neo4j at request time;
- no Redis is used for multilingual names;
- `LocalizedName` domain objects may still be used as in-memory/projection DTOs, but canonical Neo4j storage is direct properties, not `LocalizedName` nodes.

Update the top-level project description from a purely church-search framing only where appropriate, without claiming unimplemented Christianity-wide features already exist.

---

# Testing requirements

Add or update tests across `catalog`, `crawl`, and `server`.

At minimum, cover the following.

## Multilingual names

- language tag to Neo4j property mapping;
- exact round-trip of all existing supported languages;
- `zh-Hans` and `zh-Hant` distinct round-trip;
- blank values omitted;
- stale removed-language property deleted;
- deterministic ordering in generated projections;
- Church, Denomination, and Person direct property handling;
- no canonical person-name JSON blob;
- no canonical role-name JSON blob.

## Canonical replacement

- bootstrap an initial catalog;
- replace it with an updated catalog;
- add a church;
- update a church;
- remove a church;
- remove a website/social relationship;
- remove a localized name;
- verify no stale Church or managed orphan remains;
- failure leaves the prior revision current;
- optimistic expected-revision mismatch fails safely.

## Projection consistency

- Neo4j canonical snapshot content hash deterministic;
- Lucene manifest records revision and hash;
- static page manifest records the same revision and hash;
- coordinated publication rejects mixed revisions;
- compatibility JSON export contains the same logical churches and revision metadata.

## Runtime boundary

- Ktor runtime starts with generated artifacts and no Neo4j;
- Ktor runtime does not read `resources/catalog/churches.json`;
- `runCurrentIndex` has no Neo4j task dependency;
- stale or mismatched generated manifests are rejected.

## Legacy boundary

- legacy JSON bootstrap still works explicitly;
- no production crawler/build path silently falls back to legacy JSON;
- repository-wide test or architectural check prevents new unauthorized `churches.json` dependencies.

Consider adding a lightweight architecture test that scans production sources and allows `churches.json` references only in a small allowlist of legacy bootstrap/export classes and tests.

---

# Migration and verification runbook

Implement and document a safe one-time cutover sequence.

The exact command names may differ, but the sequence must be:

```sh
# 1. Baseline
git status --short
./gradlew <existing relevant tests>

# 2. Back up existing state
# Copy the legacy JSON to a dated migration backup outside normal workflow.
# Dump or otherwise back up the current Neo4j database.

# 3. Start Neo4j and migrate schema
./gradlew :crawl:run --args='catalog-neo4j-migrate'
./gradlew :crawl:run --args='catalog-neo4j-health'

# 4. Bootstrap once from legacy JSON
./gradlew :crawl:run --args='catalog-neo4j-bootstrap-from-legacy-json --input resources/catalog/churches.json'

# 5. Verify bootstrap
./gradlew :crawl:run --args='catalog-neo4j-integrity'
./gradlew :crawl:run --args='catalog-neo4j-parity --input resources/catalog/churches.json'

# 6. Generate a logical projection and compare it
./gradlew :crawl:run --args='catalog-neo4j-export-church-projection'

# 7. Build both product projections from the same Neo4j revision
./gradlew :server:publishCrossmapArtifacts

# 8. Run full verification
./gradlew --no-configuration-cache \
  :core:jvmTest \
  :catalog:test \
  :crawl:test \
  :cli:test \
  :server:test \
  :app:androidApp:assembleDebug \
  :app:shared:compileKotlinIosSimulatorArm64

# 9. Run browser E2E when its environment is available
./gradlew :server:lightpandaE2eTest
```

If the project does not currently expose `:catalog:test`, use the actual available catalog test task and document it.

After cutover, demonstrate one real dry-run and one controlled canonical update that:

- reads the prior Neo4j revision;
- writes a new revision;
- reports old and new revision IDs;
- builds Lucene and static pages from the new revision;
- does not alter `resources/catalog/churches.json`.

---

# Acceptance criteria

Do not consider this task complete until all of the following are true.

1. **Neo4j is the only mutable canonical church catalog.**
2. Normal crawler and cleanup commands do not read or replace `resources/catalog/churches.json`.
3. Lucene snapshot generation reads a committed Neo4j projection.
4. Static page generation reads the same committed Neo4j revision.
5. Search and static manifests include matching revision identity and logical content hash.
6. Runtime Ktor startup does not require Neo4j or `churches.json`.
7. Legacy JSON import exists only as an explicit bootstrap/migration command.
8. JSON export is clearly generated and noncanonical.
9. Full replacement correctly removes churches absent from the new authoritative result.
10. A failed mutation cannot replace the previously committed revision.
11. Direct multilingual properties are used for current named entities.
12. `zh-Hans` and `zh-Hant` are never collapsed.
13. Person and role canonical multilingual names are not stored as JSON strings.
14. No Redis or `LocalizedName` graph nodes are introduced.
15. Current search, static-site, CLI, app, and API behavior remains functional.
16. Documentation describes Neo4j as the source of truth.
17. Tests guard against reintroducing a production dependency on canonical `churches.json`.
18. Backup, restore, bootstrap, integrity, and publication procedures are documented.

---

# Constraints and cautions

- Do not modify the checksum of an already applied migration.
- Add a new forward-only migration.
- Do not delete the legacy JSON before verified bootstrap and backup.
- Do not silently infer language tags by truncating regional/script subtags.
- Do not make static generation mutate catalog data.
- Do not run LLM translation as an implicit dependency of page or index generation.
- Do not add request-time Neo4j queries to Ktor.
- Do not add Redis.
- Do not create multilingual name nodes.
- Do not store the full future graph in a generated church JSON projection.
- Do not remove existing evidence and human-review inputs needed by the cleanup pipeline.
- Do not mark unimplemented future Book/Sermon/Article graph functionality as completed.
- Avoid broad unrelated formatting or dependency upgrades.
- Preserve Kotlin coding style and existing test conventions.
- Keep generated output deterministic.
- Use bounded Neo4j reads.
- Use parameterized Cypher.
- Avoid depending on Neo4j internal node IDs.
- Use stable application IDs for all canonical entities.

---

# Required working style

As you work:

1. Start by inspecting the repository and summarizing the current data-flow inventory.
2. State the concrete implementation sequence before editing.
3. Make focused changes in logical groups.
4. After each group, run the smallest relevant tests.
5. Fix failures before continuing unless the failure is clearly pre-existing and unrelated.
6. Keep a list of changed files and architectural decisions.
7. Do not hide partial failures.
8. Do not stop after adding interfaces; complete caller migration.
9. Do not leave both old and new production paths active without an explicit temporary deprecation boundary.
10. At the end, run the broadest feasible verification suite.

---

# Final response format

When implementation is complete, report:

## Summary

A concise description of the completed migration.

## Final data flow

Show the final canonical-write and generated-read flow.

## Changed files

Group by:

- core/model;
- Neo4j schema and repository;
- crawler and cleanup;
- Lucene snapshot;
- static generation;
- server runtime validation;
- Gradle/tasks;
- tests;
- documentation.

## Source-of-truth audit

List every remaining `churches.json` reference and justify why it is allowed.

## Multilingual property model

Show exact supported language-tag/property mappings.

## Revision and consistency model

Explain how a committed revision is created and how Lucene/static artifacts pin it.

## Deletion behavior

Explain how removed churches and stale managed relationships/properties are cleaned.

## Commands run

List commands and whether they passed.

## Migration procedure

Give the exact cutover commands for my current catalog.

## Remaining risks or follow-up work

Only list genuine deferred work. Do not present required acceptance criteria as optional follow-up.
