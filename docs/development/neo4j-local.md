# Local Neo4j for Crossmap

Crossmap uses one repository-local Neo4j Community instance for crawl import and build-time static church-detail generation. Ktor does not query Neo4j and must continue to run when Neo4j is stopped.

## Installation and storage

- Verified Neo4j Community Server: `2026.06.0`.
- Verified Cypher Shell: `2026.06.0`.
- Java comes from the installed Neo4j package and the project uses JVM toolchain 24.
- Tracked configuration: `config/neo4j/neo4j.conf`.
- Ignored mutable storage: `cache/neo4j-data/`.
- Data: `cache/neo4j-data/data`.
- Transaction logs: `cache/neo4j-data/transactions`.
- Logs: `cache/neo4j-data/logs`.
- Run state: `cache/neo4j-data/run`.
- Import staging: `cache/neo4j-data/import`.

The instance binds only to localhost. Bolt is `bolt://localhost:7687` and Browser/HTTP is `http://localhost:7474`.

Disable the package-managed instance before starting Crossmap's instance so the ports and MCP endpoint are unambiguous:

```sh
sudo systemctl disable --now neo4j
./scripts/neo4j-local.sh validate
./scripts/neo4j-local.sh start
./scripts/neo4j-local.sh status
ss -ltn | rg ':(7474|7475|7687|7688)\b'
```

Only `127.0.0.1:7474` and `127.0.0.1:7687` should be present.

Control commands:

```sh
./scripts/neo4j-local.sh start
./scripts/neo4j-local.sh stop
./scripts/neo4j-local.sh restart
./scripts/neo4j-local.sh status
./scripts/neo4j-local.sh console
./scripts/neo4j-local.sh validate
```

## Credentials and configuration

Credentials are ignored in `local.properties`:

```properties
neo4j.uri=bolt://localhost:7687
neo4j.username=neo4j
neo4j.password=replace-with-local-password
neo4j.database=neo4j
```

CI or shell overrides use `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, and `NEO4J_DATABASE`. Environment variables take precedence. Optional pool/timeout settings are documented by `Neo4jConfig` and use safe defaults. Passwords are never logged.

For a fresh empty data directory, initialize the password before first startup with `neo4j-admin dbms set-initial-password --require-password-change=false`. Follow the installed Neo4j administration guide for a lost password; do not copy authentication files between unrelated stores.

## Catalog commands

```sh
./scripts/check-neo4j.sh
./gradlew :crawl:run --args='catalog-neo4j-migrate'
./gradlew :crawl:run --args='catalog-neo4j-status'
./scripts/catalog-bootstrap-from-legacy-json.sh resources/catalog/churches.json --dry-run
./scripts/catalog-bootstrap-from-legacy-json.sh resources/catalog/churches.json
./scripts/catalog-export.sh
./gradlew :crawl:run --args='catalog-neo4j-parity --input resources/catalog/churches.json'
./scripts/catalog-integrity.sh
./gradlew :server:publishCrossmapArtifacts
```

Reports are under `build/reports/catalog-import`, `catalog-export`, `catalog-parity`, and `catalog-integrity`.

The bootstrap and parity commands are migration/disaster-recovery tools only. `resources/catalog/churches.json` is frozen legacy input and is never a normal pipeline dependency. Every normal mutation reads the current committed revision and publishes a new revision with an expected-revision check. `catalog-neo4j-export-church-projection` is read-only and writes a generated JSON projection plus a neighboring manifest; neither file becomes canonical.

## MCP access

Codex's local Neo4j MCP is configured for `bolt://localhost:7687` with read and write tools, as explicitly requested for this development database. Keep it localhost-only and authenticated. Never point write-capable MCP configuration at a production database. The integrity service and logical export are the preferred diagnostics before any mutation.

## Backup, restore, rollback, and upgrade

Before bootstrap, schema replacement, cleanup, or upgrade:

1. Run `catalog-neo4j-status`, `scripts/catalog-integrity.sh`, and `scripts/catalog-export.sh`; preserve the export and manifest together.
2. Stop the Crossmap instance cleanly.
3. Copy the complete `cache/neo4j-data` directory to protected, timestamped backup storage, or use the backup tooling supported by the installed Neo4j edition. Never copy a running store.
4. Separately preserve the frozen legacy JSON before the one-time bootstrap. Record its SHA-256 alongside the Neo4j backup.
5. Upgrade Neo4j separately from the Java driver, validate configuration, and check the compatibility matrix.
6. Start, migrate twice, then run status and integrity. Do not routinely rerun the legacy bootstrap.

To restore after database loss, stop Neo4j, move the failed store aside, restore the complete backed-up store to `cache/neo4j-data`, start Neo4j, then run migration, status, integrity, projection export, and `:server:publishCrossmapArtifacts`. If no graph backup exists, initialize an empty store and use `catalog-neo4j-bootstrap-from-legacy-json` once from the verified frozen JSON, then run parity and integrity before publishing.

To roll back a bad catalog mutation, restore a known-good Neo4j backup. A generated church projection is intentionally incomplete as a future Christianity-graph backup and must not be imported as though it were authoritative. Never roll back by copying a JSON projection over `resources/catalog/churches.json` or by pointing Ktor at Neo4j.
