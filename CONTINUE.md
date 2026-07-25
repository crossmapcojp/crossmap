# Crossmap continuation handoff — 2026-07-25

## Continuation update

The Neo4j boundary was clarified after the original handoff:

- `crawl` imports and enriches Neo4j;
- build-time `:server:generateChurchPages` reads bounded Neo4j detail projections;
- Ktor startup and request handling never connect to Neo4j and work after it is stopped.

Implemented and verified since the original handoff:

- fixed Neo4j Driver 6.2 managed-transaction compatibility through `SimpleQueryRunner`;
- added pool/timeout settings and ignored `local.properties` loading with environment precedence;
- added ordered checksum-guarded V001 migration and proved first/second-run behavior;
- added deterministic normalization, dry-run reporting, batched idempotent import, provenance, logical export, parity diagnostics, and read-only integrity checks;
- imported all 9,465 churches with zero rejected churches and warnings for two invalid social subrecords;
- repeated the import with stable live counts: 9,465 churches, 8,535 websites, 3,659 social accounts, one import run, and 56,231 relationships at that revision;
- generated 47,325 localized detail pages from Neo4j with 16 workers;
- stopped Neo4j and proved Ktor health, a generated static detail page, and the JSON detail endpoint all returned HTTP 200;
- exported all 9,465 churches and reached full parity: 9,465 matching, zero missing, extra, or mismatched;
- passed all 10 live Neo4j integrity checks;
- added cache-backed instance configuration, scripts, and development documentation.
- parsed the configured Facebook `pages_followed_v2` export (5,108 deduplicated name-only rows with Meta mojibake repaired) and Twitter/X list export (778 accounts);
- merged all configured social exports: 14,882 accounts parsed, with 29 new canonical X profiles added across 28 churches; Facebook rows remain non-publishable audit evidence because the export contains no page IDs or URLs;
- reimported the social-enriched 9,465-church catalog, reconfirmed full parity and integrity, and regenerated all 47,325 localized Neo4j-backed detail pages; a newly added X profile appears in all five localized pages.
- implemented the dedicated `ORTHODOX_JP` three-jurisdiction crawler and validated 61 official churches across 64 live pages with zero errors; 55 rows include official addresses and clergy, and contact fields are retained when published.
- extended the denomination page boundary to preserve raw response bytes and added the dedicated `ANGLICAN_JP` official-PDF crawler; live validation produced exactly 300 unique churches across all 11 dioceses, all with addresses, 276 with phones, and zero errors.
- completed and registered the registry-driven `CATHOLIC_JP` crawler: `resources/sources/denominations.json` is the sole URL inventory, all 15 current CBCJ dioceses have dedicated parser files, and per-diocese completeness gates prevent aggregate false-greens. Live validation produced 847 unique churches across 525 list/detail pages with zero errors; the officially listed Aomori-Honcho row is retained with an explicit note because its optional Sendai detail URL currently returns 404.
- completed all 16 requested single-page/committed-fixture crawlers using registry-supplied web URLs and shared `resource:` fixture loading: 449 churches across 16 pages with zero errors. JMA and WHCJ read the committed CSV/HTML fixtures; OBC/JMBC official English names flow into canonical records; NFK/JEB preserve reviewed Korean names; JEB expands a wife's given name with the husband's surname; and the existing canonical `NSKK` ID is retained for the prompt's `MSKK` label.
- completed all 7 requested multi-page/detail-page crawlers using only the URL inventories in `resources/sources/denominations.json`: 213 churches across 84 successfully loaded list/detail pages (`JEC=43`, `JFGC=41`, `JLC=30`, `KELC=28`, `LIVE=21`, `JFEC=28`, `GMI=22`). The live run had one accurately reported optional-detail error: JFEC still lists 金山教会 but its official detail URL returns HTTP 404; the listed church remains in the generated evidence instead of being discarded.
- completed the final full pipeline: 53 dedicated denomination sources produced 6,492 official candidates from 1,700 pages and reconciled 5,102 entries; the stable-key `OfficialEntry` identity removed an accidental whole-list hash and reduced the cached full reconciliation from an unbounded CPU loop to about 20 seconds. Imported the reconciled 9,465-church catalog twice with the same checksum and stable graph counts, then reached 9,465/9,465 parity and passed all 10 integrity checks. Neo4j generated 47,325 localized detail pages, the 9,465-church snapshot was rebuilt, and real Ktor plus Lightpanda checks passed with Neo4j stopped.

Current local Neo4j status:

- project config and `local.properties` target default ports `7474/7687`;
- Codex Neo4j MCP read/write access on `7687` was verified with a create-delete probe;
- package-managed `neo4j.service` is inactive and disabled;
- the cache-backed Crossmap instance stores its database under `cache/neo4j-data` and is currently stopped after the server-independence verification;
- when started, project commands and Neo4j MCP both reach that same database.

Latest non-database verification passed:

```text
./gradlew :core:jvmTest :catalog:test :crawl:test :server:test --console=plain
./gradlew :server:lightpandaE2eTest -x :server:generateChurchPages -x :crawl:buildSearchSnapshot --console=plain
sh -n scripts/neo4j-local.sh scripts/check-neo4j.sh scripts/catalog-import.sh scripts/catalog-export.sh scripts/catalog-integrity.sh
git diff --check
```

## How to resume

Start the next session in `/home/joel/code/crossmap`, enable and use the Neo4j skill, then read these files completely before editing:

1. `AGENTS.md`
2. `CONTINUE.md`
3. `crossmap-neo4j-server-object-graph-mapper-codex-prompt.md`
4. `PLAN.md`
5. `crawl/src/main/kotlin/jp/co/crossmap/crawl/denomination/README.md`

Also search context-mode session history for the current implementation decisions. The context-mode knowledge base survives the session restart.

## User request being implemented

There are three required workstreams:

1. Follow the applicable data-migration parts of `crossmap-neo4j-server-object-graph-mapper-codex-prompt.md`: the crawler imports/enriches Neo4j and the build-time static generator reads bounded detail projections from it to render `church.html`. Ktor startup and request handling must work with Neo4j stopped.
2. Parse the newly downloaded Facebook following JSON and Twitter/X list-member JSON configured in `local.properties`, adapting their parsers and integrating both into the existing social merge pipeline.
3. Implement every requested denomination crawler as a first-class crawler, one concrete crawler per file, with denomination-specific parsing in that file, parser regression tests, live crawl validation, registration, generated output, and README checkboxes.

Do not treat the workstreams as isolated: establish the catalog persistence boundary first, then integrate the refreshed social and denomination data through the intended pipeline.

## Mandatory repository conventions

- Follow root `AGENTS.md` and the context-mode routing instructions.
- Use context-mode tools for data analysis and potentially large command output.
- Use `apply_patch` for file changes.
- Do not introduce shared denomination-specific parsing through `OfficialDirectoryPageParser.enrich`, `DirectoryCrawlerSupport.churchFromBlock`, or an `Additional*` crawler category.
- Every crawler directly implements `SinglePageDenominationChurchListCrawler` or `MultiPageDenominationChurchListCrawler` and constructs/enriches `OfficialDenominationChurch` visibly in its own file.
- Mark crawler README checkboxes only after parser tests and live crawl validation.
- Preserve user changes and unrelated dirty files.
- Do not commit credentials, Neo4j data/logs/dumps, downloaded personal exports, or generated large exports.
- Do not delete `resources/catalog/churches.json` during the initial migration or silently change HTTP contracts.
- Neo4j mode must never silently fall back to JSON.
- `cmj` is read-only reference material.

## Work completed in this session

### Planning and assessment

- Added section **“9. Neo4j catalog, refreshed social exports, and denomination expansion”** to `PLAN.md`.
- Created `docs/development/catalog-neo4j-assessment.md` with:
  - current Crossmap catalog/runtime architecture;
  - local Neo4j installation facts;
  - the exact read-only `cmj` revision reviewed;
  - ontology concepts adopted and dangerous persistence patterns rejected;
  - the initial bounded graph and migration boundary.
- Indexed and analyzed the 46 KB Neo4j implementation prompt. It contains 20 phases and acceptance criteria; the next session must still read it fully and follow it as the authority.

### Environment facts verified

- Repository: `/home/joel/code/crossmap`.
- Neo4j Community Server is running, PID was `1561254` during assessment.
- Installed Neo4j and Cypher Shell version: `2026.06.0`.
- No `NEO4J_*` environment variables were present.
- `/etc/neo4j/neo4j.conf` exists; authentication remains enabled.
- Authenticated Bolt connectivity was **not** verified because no credentials were available in the session environment. The next session must use the Neo4j skill and obtain credentials through external environment variables without writing or printing the password.
- Official Java driver Maven metadata was checked; `org.neo4j.driver:neo4j-java-driver:6.2.0` was the current release and was selected.

### `cmj` reference audit

- Clone: `/home/joel/code/cmj`.
- Revision: `150696e954f068444e8afd08a9ac669dd26e4117`.
- Worktree was clean.
- Reviewed the requested `Church.kt`, `Location.kt`, `MultilingualEntity.kt`, and `OnlineThing.kt` plus directly referenced annotation/entity concepts.
- Adopted terminology for Church, Denomination, Alliance, Parish, Location, Website, SocialMediaAccount, Person, and RoleEvent.
- Rejected numeric internal IDs, abstract/enumeration nodes, recursively hydrated graphs, large bidirectional collections, ambiguous empty relationship lists, cascade saving, lazy loading, dirty tracking, and automatic graph deletion.

### KMP domain boundary added

Created:

- `core/src/commonMain/kotlin/jp/co/crossmap/catalog/CatalogDomain.kt`
- `core/src/commonTest/kotlin/jp/co/crossmap/catalog/CatalogDomainTest.kt`

This currently defines typed stable IDs, `EntityRef`, `MultilingualText`, a bounded `Church` model, Website/Social types, endpoint-oriented projections, pagination/bounds types, the domain-facing `ChurchRepository`, and `ChurchRecord.toCatalogChurch()`.

This is intentionally free of Neo4j driver types so it can remain in KMP common code.

### JVM catalog module started

Added `:catalog` to `settings.gradle.kts`, added Neo4j driver `6.2.0` to `gradle/libs.versions.toml`, and added `implementation(projects.catalog)` to `server/build.gradle.kts`.

Created:

- `catalog/build.gradle.kts`
- `catalog/src/main/kotlin/jp/co/crossmap/catalog/graph/GraphMetadata.kt`
- `catalog/src/test/kotlin/jp/co/crossmap/catalog/graph/GraphMetadataTest.kt`
- `catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/Neo4jConfig.kt`
- `catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/Neo4jDriverManager.kt`
- `catalog/src/main/kotlin/jp/co/crossmap/catalog/neo4j/GraphTransactionRunner.kt`
- `catalog/src/test/kotlin/jp/co/crossmap/catalog/neo4j/Neo4jConfigTest.kt`

The initial implementation includes:

- explicit `NodeMetadata` and registry;
- a bounded scalar-only mapper that rejects nested values;
- flattened multilingual properties such as `name_ja` and `name_en`;
- explicit Church metadata;
- `CATALOG_BACKEND=json|neo4j` configuration with no fallback in Neo4j mode;
- one managed driver holder with connectivity/health/close operations;
- an application-owned read/write transaction runner with named-duration logging.

These files are an unverified first slice, not a completed architecture.

### Social export formats analyzed

Current `local.properties` entries:

```properties
crossmap.facebookFollowingJson=/home/joel/Downloads/facebook-hokutoide-2026_07_25-6HZxkeIF/your_facebook_activity/pages/pages_and_profiles_you_follow.json
crossmap.twitterListMembersJson=/home/joel/Downloads/twitter-ListMembers-1784935198973.json
```

Observed shapes without copying personal data into the repository:

- Facebook: 2,148,625 bytes, root object key `pages_followed_v2`, 5,131 entries; each entry has `timestamp`, `data`, and `title`. `FacebookChurchPageJsonParser` is currently a placeholder returning an empty list and must be implemented against nested real fields with sanitized regression fixtures.
- Twitter/X: 3,304,939 bytes, root array, 778 members. Entries expose `id`, `screen_name`, `name`, `description`, website/profile fields, and `metadata`. The current `TwitterListMembersJsonParser` already understands the top-level fields and a `metadata.legacy` fallback, but it must be validated against the complete real file and adjusted for visible edge cases.
- The downloaded files must not be committed. Add minimal synthetic fixtures reproducing their schemas.

### Offline denomination fixtures found

- `resources/crawl/jma-churches.csv` exists (3,837 bytes) and is intended to be committed.
- `resources/crawl/whcj-churches.html` exists (71,028 bytes) and is intended to be committed.

## Verification status

The command below was started after adding the initial domain/catalog slice:

```bash
./gradlew :core:jvmTest :catalog:test
```

It was deliberately terminated when the user paused the session to enable the Neo4j skill. No successful or failed result was obtained. **Rerun this first.** Expect possible compilation/API issues in the unverified driver slice and fix them test-first.

No Neo4j schema, repository, import, export, parity, integrity, runtime cutover, social parser change, or requested new crawler has been completed yet.

## Current worktree ownership

The following pre-existing/user-owned dirty files were present before this session’s Neo4j edits and must be preserved:

- `crawl/src/main/kotlin/jp/co/crossmap/crawl/denomination/README.md`
- `resources/catalog/denominations.json`
- `crossmap-neo4j-server-object-graph-mapper-codex-prompt.md` (untracked user-provided prompt)

This session changed or created:

- `PLAN.md`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `server/build.gradle.kts`
- `catalog/**`
- `core/src/commonMain/kotlin/jp/co/crossmap/catalog/**`
- `core/src/commonTest/kotlin/jp/co/crossmap/catalog/**`
- `docs/development/catalog-neo4j-assessment.md`
- `CONTINUE.md`

Review overlapping diffs carefully; do not reset or discard unrelated changes.

## Next steps in order

### 1. Activate the Neo4j skill and validate the initial slice

1. Use the Neo4j skill and read its complete instructions.
2. Rerun `./gradlew :core:jvmTest :catalog:test`.
3. Fix compilation and tests without weakening the bounded-mapper constraints.
4. Verify authenticated connectivity using externally supplied `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, and `NEO4J_DATABASE`.
5. Check driver/server compatibility and record the exact result.

### 2. Complete the Neo4j prompt phases

Implement, test, and document:

- explicit Neo4j Church repositories and bounded projections;
- ordered repeatable schema migrations, constraints, and only evidence-based indexes;
- deterministic JSON normalization and batched idempotent importer with `ImportRun` provenance/checksum/report;
- denomination, location, website, social-account, person/minister, and explicitly justified source relationships;
- logical deterministic JSON exporter and round-trip tests;
- canonical parity comparison and report;
- predefined read-only integrity checks and report;
- CLI/Gradle commands and safe scripts;
- crawler/static-generator command lifecycle integration and health distinction; never connect from Ktor startup or routes;
- `LegacyJsonChurchCatalogSource` and `Neo4jChurchCatalogSource` during migration;
- crawler/catalog-generation and build-time static-page cutover only after import/parity/tests; Ktor must have no live Neo4j requirement;
- static-site/snapshot implications without changing public HTTP contracts;
- all required documents and final implementation report named in the prompt.

Use a separately configured test database/namespace. Never run unbounded destructive Cypher against the development catalog. Before any destructive replacement, make a logical export or backup.

### 3. Social export integration

1. Programmatically inspect nested Facebook `data` fields and URL representations without dumping the export.
2. Add sanitized real-shape fixtures and failing tests.
3. Implement `FacebookChurchPageJsonParser` and validate all 5,131 entries.
4. Analyze Twitter/X null/type/URL/duplicate distributions, add edge-case tests, and adjust its parser only as needed.
5. Run the social-link pipeline in dry-run mode, audit match statistics/false positives, then integrate through the normal full pipeline.

### 4. Requested denomination crawlers

Single-page/offline crawlers:

- JMA — `resources/crawl/jma-churches.csv`; reconstruct and hard-code Hangul for Korean names represented with Chinese characters.
- WJELC — `https://www.wjelc.or.jp/about/churchlist/`
- JAC — `https://jac-hij.sakura.ne.jp/profile.html`
- WHCJ — committed `resources/crawl/whcj-churches.html`
- OBC — `http://okinawabaptist.com/?page_id=2`; preserve official English church names as canonical localized names.
- SEIKYODAN — `https://www.seikyodan.com/shyozoku2.html`
- WMC — `https://worldmission.or.jp/church/`
- JMBC — `https://jmbc.japan-mb.com/church/`; preserve official English names.
- JLBC — `https://clbj.org/church/`
- FMC_JP — `https://fmcjp.org/?page_id=61`
- NFK — `https://nihonfukuin.imagodei.jp/%e6%89%80%e5%b1%9e%e6%95%99%e4%bc%9a/`; normalize half-width kana and decompose names such as `車幸任ﾁｬﾍﾝﾘﾑ` using the supplied kana reading.
- MSKK — `https://nskk.gr.jp/church/`
- 日本アドベント・キリスト教団 — `https://nihonadobento.wordpress.com/home/%e6%89%80%e5%b1%9e%e6%95%99%e4%bc%9a%e3%83%bb%e9%96%a2%e9%80%a3%e5%9b%a3%e4%bd%93%e4%b8%80%e8%a6%a7/`
- FUKUIN_DENDO — `https://church.ne.jp/niigatabible_ch/main/denpuku.html`
- 日本伝道隊 — `https://nihon-dendoutai.kyoukai.jp/church/`; reconstruct/hard-code Korean Hangul names and parse `husband-full-name、wife-given-name` by applying the husband’s surname to the wife.
- 日本聖約キリスト教団 — `https://www.seiyaku.jp/?page_id=1316`

Multi-page/detail-page crawlers:

- JEC — prefecture pages under `https://www.jec-net.org/pref/`: `gunma`, `tokyo`, `kanagawa`, `nagano`, `shizuoka`, `aichi`, `mie`, `kyoto`, `osaka`, `hyogo`, `nara`, `wakayama`, `shimane`, `okayama`, `tokushima`, `fukuoka`.
- JFGC — `https://www.japan-foursquare.jp/cont2/6.html`, `/22.html`, `/21.html`, `/23.html`, `/13.html`.
- JLC — `http://www.jlc.or.jp/area/hokkaido/`, `/nigata/`, `/kanto/`, `/okinawa/`.
- JFEC — `https://www.doumeifukuin.net/shyozokukyoukai/`; crawl denomination church detail pages too.
- KELC — `http://www.kelc.net/hyogo.html`, `osaka.html`, `wakayama.html`, `nara.html`, `mie.html`.
- LIVE — `https://livechurch.jp/location/ja/`, `/en/`, `/pt/`; merge official language-specific names into the same official records.
- GMI — `https://gmi.or.jp/chapels/`; crawl church detail pages too.

For each crawler: inspect/fetch safely, add a dedicated file, handle visible address/phone/minister/detail-page/English-name edge cases, register uniformly, add unit tests, run a live crawl, inspect generated records, then check its README box.

### 5. Final verification

- Run all focused and full Gradle tests.
- Run schema migration twice.
- Import the catalog twice and prove stable counts/no duplicates.
- Generate importer, parity, integrity, and logical export reports.
- Run full data pipeline with refreshed social and denomination data.
- Generate static detail pages from Neo4j, stop Neo4j, then verify Ktor endpoints and LightPanda/WebClient flows remain green.
- Evaluate logs and resulting church data for regressions before declaring completion.
- Complete `docs/development/catalog-neo4j-implementation-report.md` and all prompt acceptance criteria.
