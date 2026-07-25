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
./scripts/catalog-import.sh resources/catalog/churches.json --dry-run
./scripts/catalog-import.sh resources/catalog/churches.json
./scripts/catalog-export.sh
./gradlew :crawl:run --args='catalog-neo4j-parity --input resources/catalog/churches.json'
./scripts/catalog-integrity.sh
./gradlew :server:generateChurchPages
```

Reports are under `build/reports/catalog-import`, `catalog-export`, `catalog-parity`, and `catalog-integrity`.

## MCP access

Codex's local Neo4j MCP is configured for `bolt://localhost:7687` with read and write tools, as explicitly requested for this development database. Keep it localhost-only and authenticated. Never point write-capable MCP configuration at a production database. The integrity service and logical export are the preferred diagnostics before any mutation.

## Backup and upgrade

Before schema replacement, cleanup, or upgrade:

1. Run `scripts/catalog-export.sh` and parity/integrity checks.
2. Stop the Crossmap instance.
3. Copy `cache/neo4j-data` to protected backup storage, or use the backup tooling supported by the installed Community release.
4. Upgrade Neo4j separately from the Java driver, validate configuration, and check the compatibility matrix.
5. Start, migrate twice, import twice, and rerun parity/integrity before static generation.

