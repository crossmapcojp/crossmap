# Catalog JSON to Neo4j migration

`resources/catalog/churches.json` remains the immutable migration and rollback contract while the graph pipeline is validated. Neo4j is not a live Ktor dependency.

## Flow

1. `LegacyJsonChurchCatalogSource` decodes the existing `ChurchRecord` serializer.
2. Normalization trims names, canonicalizes language keys and URLs, validates coordinates, assigns stable IDs, sorts nested records, and reports invalid subrecords.
3. `CatalogSchemaMigrator` applies ordered checksum-guarded Cypher resources.
4. `Neo4jCatalogImporter` writes batches with parameterized `UNWIND` and explicit relationships.
5. `ImportRun` and `SourceRecord` preserve checksum and record provenance.
6. `Neo4jStaticChurchCatalogSource` reads bounded pages and reconstructs deterministic church-detail projections.
7. Logical export and canonical parity compare all 9,465 normalized churches.
8. Integrity checks validate IDs, names, coordinates, denomination cardinality, locations, websites, and provenance.
9. Static generation renders the Neo4j projections into localized `church.html` files.
10. Neo4j can then stop; Ktor serves generated pages and search snapshots without connecting to it.

The importer deliberately replaces only named relationship types for the church IDs in the active batch. It never recursively persists an arbitrary object graph and never performs a database-wide delete.

## Stable IDs

- Church: existing Crossmap ID.
- Denomination: existing catalog denomination ID.
- Location: `church-location:<church-id>` for the current single-address model.
- Website: deterministic hash of canonical URL while retaining meaningful fragments for church-directory anchors.
- Webpage: deterministic hash of canonical URL plus content hash, preserving multiple crawl observations.
- Social account: deterministic platform and normalized-URL hash.
- Person and role event: deterministic church/name/role hash until cross-church person identity is justified.
- Source record: source checksum and record index.
- Import run: source checksum.

## Rollback

Stop using Neo4j-backed generation, retain the last known-good generated `webclient` output, and rebuild from `resources/catalog/churches.json` with the prior revision. Do not delete the JSON source during the migration period.

