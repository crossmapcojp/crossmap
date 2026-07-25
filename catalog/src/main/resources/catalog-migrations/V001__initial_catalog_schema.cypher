// Stable application identifiers and migration metadata.
CREATE CONSTRAINT church_id_unique IF NOT EXISTS FOR (node:Church) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT denomination_id_unique IF NOT EXISTS FOR (node:Denomination) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT location_id_unique IF NOT EXISTS FOR (node:Location) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT website_id_unique IF NOT EXISTS FOR (node:Website) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT social_account_id_unique IF NOT EXISTS FOR (node:SocialMediaAccount) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT person_id_unique IF NOT EXISTS FOR (node:Person) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT role_event_id_unique IF NOT EXISTS FOR (node:RoleEvent) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT webpage_id_unique IF NOT EXISTS FOR (node:Webpage) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT source_record_id_unique IF NOT EXISTS FOR (node:SourceRecord) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT import_run_id_unique IF NOT EXISTS FOR (node:ImportRun) REQUIRE node.id IS UNIQUE;
CREATE CONSTRAINT schema_migration_version_unique IF NOT EXISTS FOR (node:SchemaMigration) REQUIRE node.version IS UNIQUE;
CREATE CONSTRAINT crossmap_schema_name_unique IF NOT EXISTS FOR (node:CrossmapSchema) REQUIRE node.name IS UNIQUE;

// Index only properties used by planned repository lookup and integrity queries.
CREATE INDEX church_google_place_id IF NOT EXISTS FOR (node:Church) ON (node.googlePlaceId);
CREATE INDEX church_normalized_name IF NOT EXISTS FOR (node:Church) ON (node.normalizedName);
CREATE INDEX denomination_normalized_name IF NOT EXISTS FOR (node:Denomination) ON (node.normalizedName);
CREATE INDEX location_city_code IF NOT EXISTS FOR (node:Location) ON (node.cityCode);
CREATE INDEX website_normalized_url IF NOT EXISTS FOR (node:Website) ON (node.normalizedUrl);
