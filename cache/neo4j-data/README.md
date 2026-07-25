# Neo4j local data

This ignored directory stores the repository-local Neo4j Community instance used by Crossmap catalog import and static-site generation.

- Data, transaction logs, logs, run state, imports, and plugins remain under this directory.
- The tracked configuration is `config/neo4j/neo4j.conf`.
- Control the instance with `scripts/neo4j-local.sh`.
- Bolt listens on `bolt://localhost:7687`; Browser/HTTP listens on `http://localhost:7474`.
- Authentication remains enabled. Never store the password here or commit it elsewhere.
