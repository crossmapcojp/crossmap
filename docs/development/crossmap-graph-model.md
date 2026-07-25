# Crossmap catalog graph model

The graph model is deliberately bounded. KMP domain IDs and projections live in `core`; JVM driver, mapper, migrations, import/export, parity, and integrity code live in `catalog`.

## Labels

- `Church`, `Denomination`, `Location`
- `Website`, `Webpage`, `SocialMediaAccount`
- `Person`, `RoleEvent`
- `SourceRecord`, `ImportRun`
- `CrossmapSchema`, `SchemaMigration`

## Relationships

```text
(Church)-[:BELONGS_TO_DENOMINATION]->(Denomination)
(Church)-[:LOCATED_AT]->(Location)
(Church)-[:HAS_WEBSITE {pageIds}]->(Website)
(Website)-[:HAS_PAGE]->(Webpage)
(Church)-[:HAS_SOCIAL_ACCOUNT {display metadata}]->(SocialMediaAccount)
(Person)-[:HELD_ROLE]->(RoleEvent)-[:ROLE_AT]->(Church)
(Church)-[:IMPORTED_FROM]->(SourceRecord)<-[:IMPORTED]-(ImportRun)
```

Relationship properties are used only where the fact is church-specific. A shared social account keeps platform/URL identity on the node, while a church-specific discovered display name remains on its relationship. A website can be shared, while `pageIds` records which crawl observations belong to that church.

The mapper supports stable IDs, scalar properties, multilingual flattened properties, converters, and explicit one-hop projections. It does not support recursive cascade save, automatic graph deletion, lazy loading, dirty tracking, identity maps, arbitrary reflection traversal, or unrestricted hydration.

