CREATE CONSTRAINT catalog_revision_id_unique IF NOT EXISTS FOR (node:CatalogRevision) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT catalog_state_name_unique IF NOT EXISTS FOR (node:CatalogState) REQUIRE node.name IS UNIQUE;
CREATE CONSTRAINT catalog_revision_sequence_unique IF NOT EXISTS FOR (node:CatalogRevision) REQUIRE node.sequence IS UNIQUE;
CREATE INDEX catalog_revision_status IF NOT EXISTS FOR (node:CatalogRevision) ON (node.status);
