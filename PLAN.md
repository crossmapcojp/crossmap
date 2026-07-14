# Crossmap Church Search 1.0 Implementation Plan

## 1. Project foundation

- [x] Align Gradle plugins and dependency versions with the working BBL/lucene-kmp stack.
- [x] Add Kotlin serialization, Clikt, Okio, Ktor client/server, and lucene-kmp dependencies.
- [x] Add a local sibling `lucene-kmp` composite-build switch.
- [x] Replace generated greeting code with shared Crossmap domain and search interfaces.
- [x] Prove a JVM-built fixture index can be opened and queried by the shared KMP engine.

## 2. Canonical resources and crawler

- [x] Create the canonical `resources/raw`, `resources/crawl`, `resources/catalog`, `resources/geonames`, and versioned index layout.
- [ ] Reimplement the gmap Saved Places workflow—CSV seeds, exclusions, CID cache/fetch, Google Maps parsing, normalization, website extraction, and reporting—inside standalone Crossmap stages.
- [x] Seed the standalone Crossmap catalog from the clean historical corpus; no runtime/build dependency on the gmap repository remains.
- [x] Add a standalone RFC 4180 Google Takeout Saved Places reader for the real Japanese `タイトル,メモ,URL,コメント` format, stable CID extraction, cross-list deduplication, error reporting, and raw seed JSON.
- [x] Resolve raw Saved Places seeds through copied CID HTML cache first, plain HTTP second, and lightweight Lightpanda rendering last; parse name, coordinates, address, website, and category into raw church candidates with an audit report.
- [ ] Apply exclusion lists and Catholic-list non-church filtering from gmap (done), then complete candidate-name normalization and entity-level deduplication before promotion.
- [ ] Feed resolved candidates into the existing Crossmap deterministic → official-directory/page evidence → LLM → human-override cleanup workflow; do not create a parallel cleanup implementation.
- [ ] Produce timestamped source/crawl/cleanup completeness reports and promote only complete records into the canonical catalog.
- [x] Replace denomination-specific control flow with a generic evidence -> candidate -> resolution -> review -> publication pipeline.
- [x] Model church, denomination directory, website, social profile, and sermon inputs as typed evidence records with durable provenance.
- [x] Add a data-driven denomination catalog and directory-source configurations so coverage is not limited to the old hand-coded subset.
- [x] Crawl configured official denomination directories and emit normalized church candidates without denomination-specific Kotlin code.
- [x] Allow new denomination candidates discovered from websites/directories to enter human review instead of requiring a pre-existing hardcoded ID.
- [x] Implement a durable URL-to-cache manifest so every indexed page is traceable to a church and source URL.
- [x] Implement resumable `refresh` with bounded concurrency, per-host throttling, retries, conditional HTTP requests, content hashes, robots handling, and recorded failures.
- [x] Implement supported same-site page discovery and complete visible-text extraction.
- [x] Reserve typed Facebook, X/Twitter, Instagram, and YouTube profile/provenance fields for later social ingestion without enabling social crawling in 1.0.
- [x] Reserve typed sermon metadata and corpus-aware manifests so future sermon crawling/search can use a separate index without breaking church search.
- [x] Add offline crawler fixtures and deterministic import tests.
- [x] Add deterministic refresh tests.

## 3. Japanese geonames

- [x] Add a generated catalog covering all 47 prefectures and current Japanese municipalities/wards with official codes.
- [x] Generate canonical aliases, center coordinates, and a per-place covering radius.
- [x] Implement common query normalization, longest-name-first extraction, prefecture disambiguation, duplicate-city unions, and location-token removal.
- [x] Use optional browser/app geolocation with a 25 km default radius only when the query contains no recognized geoname.
- [x] Add coverage tests for prefectures, municipalities, ambiguity, multiple locations, location-only queries, and radius overrides.

## 3a. Post-crawl entity cleanup

- [x] Add provenance-aware field determinations tagged `[programmatically-determined]`, `[llm-determined]`, or `[human-determined]`.
- [x] Add durable denomination/social candidate inputs and auditable denomination and social decision logs.
- [x] Integrate Koog with local Ollama for best-effort Japanese entity matching using a configurable small model.
- [x] Apply LLM decisions only at or above a configurable 0.80 confidence threshold and preserve rejected/uncertain decisions for review.
- [x] Add a manual override file and command so reviewed values take precedence and become `[human-determined]`.
- [x] Add deterministic fake-matcher tests; never require Ollama for the unit-test suite.
- [x] Resolve social accounts in precedence order: direct cached-page hyperlink, exact/either-name-contains match, then bounded LLM fallback; leave low or ambiguous scores unmatched.
- [x] Publish accepted social profiles and field provenance into the standalone church catalog while retaining an auditable `social-decisions.json`.
- [x] Resolve each church `englishName` first from official webpage, URL/domain, or linked social evidence, then fall back to Koog + Ollama translation with auditable name-part roles.
- [x] Add an `english-names` crawl command that atomically updates the catalog and refuses a partial result so every publishable church has an English-name URL component.
- [x] Install the `cyberagent/CAT-Translate-7b` Q4_K_M GGUF as `cat-translate:7b-q4_k_m`, configure a 4096-token context, and verify it runs fully on GPU.
- [ ] Check disk capacity with `df` before any Ollama inference or model pull, then evaluate installed Japanese-capable models on labeled fixtures.

## 4. Shared Lucene index and search

- [x] Dogfood the built `lc` command against Crossmap fixtures to compare field extraction, boosts, and result ranking; improve lucene-cli generically if the experiment exposes a missing capability.
- [x] Define serializable church, crawl, geoname, request, response, hit, page, error, and index-manifest models.
- [x] Build one Lucene document per church with boosted name/category/address/content fields and geo point/doc-values fields.
- [x] Keep social profile metadata independently indexable so future social content can be added without changing church/result JSON contracts.
- [x] Keep crawled content type and optional sermon metadata independently indexable for a future sermon-result document model.
- [x] Implement shared text-plus-geo search, exact totals, stable ordering, pagination, distance, matching page detection, and snippets.
- [x] Implement deterministic versioned snapshot creation with manifest, document count, lucene-kmp version, archive size, and SHA-256.
- [x] Complete shared search coverage for every indexed field, geo behavior, ordering, pagination, and JSON round trips. (Real-index and 19-scenario Clikt tests pass.)

## 5. `cm` CLI

- [x] Implement `cm church search <query>` with index, pagination, radius, JSON, and pretty-print options.
- [x] Implement compact human output and canonical JSON-only stdout behavior.
- [x] Implement `cm church index info`.
- [x] Add CLI golden and failure-path tests.
- [x] Implement `--latitude`, `--longitude`, and `--radius-km` geo-filter options, including paired/range validation and query-geoname precedence.
- [x] At the end of 1.0 work, imagine broad real-world church-search user scenarios and exercise them through Clikt `test()`; assert result quality and iteratively improve indexing, crawler extraction, query parsing, ranking, and related logic.
  - [x] Exercise 19 Clikt scenarios built from real crawled churches: exact/partial names, denomination and website text, address, prefecture/city/ambiguous-city geonames, device location, pagination, JSON, punctuation, and no-result/error paths.
  - [x] Prevent generic `教会`/`チャペル` terms from overwhelming more distinctive terms, escape Lucene query syntax, and boost addresses matching resolved place names.

## 6. Ktor server and web client

- [x] Implement `/api/v1/churches/search`, `/api/v1/indexes/churches/latest`, immutable archive download, and `/api/v1/health`.
- [x] Implement `/api/v1/churches/{id}` and a church detail page showing name, denomination, address, website, and typed social links.
- [x] Validate and open the configured index at server startup and return structured JSON errors.
- [x] Serve the vanilla HTML/JavaScript client from Ktor.
- [x] Implement query, loading, error, empty, result, distance, snippet, link, and pagination UI states.
- [x] Implement the vanilla-JavaScript `index.html` -> JSON-backed `result.html` -> JSON-backed `church.html` navigation flow.
- [x] Generate static FreeMarker church detail pages at English denomination/name slugs using root-relative page and canonical links.
- [x] Fail static publication when an English church name, known denomination English name, or collision-disambiguating English location is missing.
- [x] Serve generated `/church/{english-slug}.html` pages and provide the `generateChurchPages` Gradle task.
- [x] Add Ktor API tests and a Lightpanda browser smoke test against `./gradlew :server:run`, covering index -> search JSON -> rendered result page -> church detail JSON/page.

## 7. Android and iOS app

- [x] Implement the shared Compose search screen and state/view-model layer.
- [x] Implement full-snapshot download to `.part`, SHA-256 verification, staging extraction, manifest validation, and atomic active-version switching.
- [x] Retain the previous working snapshot and support retry/redownload/offline launch.
- [x] Run all mobile searches locally through the shared lucene-kmp engine.
- [x] Implement platform link opening and all download/search/result/detail/error UI states.
- [x] Implement optional Android/iOS location permission and pass device coordinates as the no-geoname search fallback.
- [ ] Verify Android build/tests and an Android emulator search flow with the Android CLI.
- [ ] Verify iOS framework/tests and the shared golden-query fixture.

## 8. End-to-end acceptance

- [x] Materialize the available clean church data as a standalone canonical Crossmap catalog with no gmap build/runtime dependency.
- [x] Build a full church index snapshot from the standalone canonical corpus.
- [ ] Run the same golden query set through core, CLI, Ktor, Android, and iOS paths.
- [x] Cover Japanese name, denomination, address, website body, prefecture, city, ambiguous city, location-only, pagination, and no-result scenarios.
- [x] Document build, crawl, snapshot, CLI, server, web, Android, and iOS usage in the README.
