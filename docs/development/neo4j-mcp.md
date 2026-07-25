# Neo4j MCP for Crossmap

The local Codex Neo4j MCP targets `bolt://localhost:7687`, database `neo4j`, with authentication. The repository owner explicitly requested full read/write MCP access for this localhost development database.

Safety rules:

- Confirm `scripts/neo4j-local.sh status` and the listening ports before writes.
- Prefer MCP reads and `catalog-neo4j-integrity` for diagnosis.
- Use repository migrations/import commands for normal writes so behavior remains tested and reported.
- Before destructive maintenance, produce a logical export and filesystem backup.
- Never expose MCP or Bolt beyond localhost and never reuse these credentials for production.
- Do not commit MCP credentials, `local.properties`, database files, dumps, or logs.

Useful read queries:

```cypher
MATCH (schema:CrossmapSchema {name: 'catalog'}) RETURN schema.version;
MATCH (church:Church) RETURN count(church);
MATCH ()-[relationship]->() RETURN type(relationship), count(*) ORDER BY type(relationship);
MATCH (run:ImportRun) RETURN run.id, run.status, run.sourceChecksum, run.completedAt ORDER BY run.completedAt DESC;
```

