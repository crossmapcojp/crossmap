# Neo4j source-of-truth migration

Neo4j is the sole canonical church-catalog authority. `resources/catalog/churches.json` remains frozen only as verified one-time bootstrap, parity, and disaster-recovery input; normal crawling, cleanup, localization, indexing, static generation, and serving must not depend on it.

## Final data flow

```text
raw sources and evidence
  -> read current committed Neo4j CatalogRevision
  -> bounded in-memory normalization/enrichment
  -> validate complete ChurchRecord projection
  -> optimistic authoritative Neo4j replacement
  -> one new committed CatalogRevision
  -> pinned Lucene and static-page projections
  -> manifest revision/hash reconciliation
  -> Ktor serves materialized artifacts without Neo4j
```

Canonical multilingual names use direct Neo4j properties: `name_ja`, `name_en`, `name_ko`, `name_pt`, `name_id`, `name_vi`, `name_zh_Hans`, and `name_zh_Hant`. Simplified and Traditional Chinese remain distinct. Church, Denomination, Person, and RoleEvent use the same reversible mapping; JSON-string name blobs are invalid canonical state.

Each successful mutation writes one `CatalogRevision` and atomically moves `CatalogState(id='canonical')-[:CURRENT_REVISION]->...` in the same transaction. The caller supplies the revision it read; a concurrent change fails the expected-revision check. Authoritative replacement removes absent managed churches and denomination-independent managed orphans. A failed transaction leaves the previous current revision readable and records a separate failed operation when possible.

## Migration procedure

1. Record baseline counts and SHA-256 for the frozen legacy JSON.
2. Stop Neo4j and take a timestamped backup of the complete store; preserve the JSON separately.
3. Start Neo4j and run `catalog-neo4j-migrate` twice to prove migration idempotence.
4. Run `catalog-neo4j-bootstrap-from-legacy-json --dry-run`, then run it once without `--dry-run`.
5. Run `catalog-neo4j-status`, legacy parity, and `catalog-neo4j-integrity`.
6. Run `catalog-neo4j-export-church-projection`; preserve its neighboring manifest with the JSON.
7. Run `:server:publishCrossmapArtifacts`. This builds search and static projections and rejects differing catalog revision IDs or hashes.
8. Run the full unit/integration suite and browser E2E when its environment is available.

Bootstrap and parity are diagnostic migration commands, not production gates. Generated JSON is a flattened church projection and is not a complete graph backup. Restore and rollback procedures are documented in [neo4j-local.md](neo4j-local.md).

## Runtime boundary

Ktor requires compatible Lucene/static manifests and materialized files only. It does not connect to Neo4j, does not require the frozen JSON, and does not compare artifact freshness to repository files. `runCurrentIndex` serves already-published artifacts; `publishCrossmapArtifacts` is the coordinated build-time operation.

## Verified cutover — 2026-07-31

- The package-managed and repository-local stores were backed up before migration under `cache/migration-backups/2026-07-31-neo4j-source-of-truth/`; the dump and frozen JSON SHA-256 values were recorded at creation time.
- Schema migration applied version 2 once and reported no new migrations on the second run.
- Bootstrap accepted 9,464 churches with zero rejected records.
- Integrity passed all 19 checks; normalized legacy parity matched 9,464/9,464 churches with no missing, extra, or mismatched records.
- An authoritative replacement that omitted `google:1002244063282821313` produced a 9,463-church projection with that church absent and all 19 integrity checks passing, including cleanup of its now-orphaned managed location. Restoring the frozen projection returned the graph to 9,464 churches at revision `catalog:294f382e-4895-4ff8-8e0e-868337fceb03` with the original logical content hash.
- The explicit Vietnamese dry run did not change the current revision. The controlled applied run advanced the revision from `catalog:7383a297-17cb-4012-8396-30ac81e66d3a` to `catalog:0672ff3f-edc7-4231-8896-4fa57fe2aa89` while retaining logical content hash `08dea788fe668ad03cdba899adfe1ee24a62dab65441b7cd2e43cb5e9aa2522a`.
- The frozen `resources/catalog/churches.json` SHA-256 remained `a2b88fc1568235df8a8fa48dc5e3895e09b1e48de18b77d958bf669b66c17e58` before and after the controlled update.
- `publishCrossmapArtifacts` produced 9,464 Lucene documents and 9,464 church-page entries from the restored revision/hash. The Lightpanda browser E2E suite then passed against those materialized artifacts with repository-local Neo4j stopped.
