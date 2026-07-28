# Crossmap Church Search

Crossmap is a standalone Kotlin Multiplatform church-search system for Japan. It crawls and normalizes church data, builds a downloadable lucene-kmp index, and exposes the same search behavior through the `cm` CLI, an on-device Compose app, and a Ktor JSON API used by a vanilla-JavaScript web client.

Crossmap does not require the former gmap project at build time or runtime.

## Modules

- `core`: serializable records, Japanese geoname resolution, Lucene schema/indexing, and the shared search engine.
- `catalog`: Neo4j-backed catalog storage, schema migration, import/export, parity validation, and integrity checks.
- `crawl`: website refresh, official-directory crawling, evidence/candidate resolution, denomination cleanup, Koog/Ollama fallback, social linking, and snapshot construction.
- `cli`: the `cm` developer command.
- `server`: Ktor JSON API backed by lucene-kmp on JVM.
- `webclient`: `index.html` search, `result.html` results, and `church.html` detail rendering in vanilla JavaScript.
- `app/shared`: shared Compose UI, state, snapshot management, local search, and location abstraction.
- `app/androidApp` and `app/iosApp`: platform launchers, permissions, storage paths, and geolocation.
- `resources`: durable inputs, crawl cache, evidence, review decisions, canonical catalogs, geonames, and versioned indexes.

## Neo4j boundary

Neo4j is a crawler/build-time database, not a server dependency. The `crawl` module imports and enriches the catalog in Neo4j, and `:server:generateChurchPages` reads bounded Neo4j projections to materialize the localized pages built from `church.html`. The Ktor server itself reads the generated Lucene snapshot, JSON detail data, and static WebClient files; it neither opens a Neo4j driver nor requires Neo4j to be running at startup or while serving requests.

The repository-local instance uses `local.properties` for its Bolt credentials and keeps mutable database files under `cache/neo4j-data`. See [`docs/development/neo4j-local.md`](docs/development/neo4j-local.md) for start, import, parity, integrity, generation, and stop commands.

## Search behavior

All crawled church-page text is searchable. A query is parsed longest-name-first against all 47 prefectures and the generated municipality/ward catalog. Exact and all-name-token tiers keep the complete query. The final geographic tier selects one intended address entity and filters its stable geoname code; the remaining words are scored inside that area. Duplicate municipality and ward names are resolved from browser/app coordinates when available, and otherwise remain unresolved instead of silently searching several unrelated areas. A geoname-only query such as `東京` or `Tokyo` uses the geographic filter with no text requirement.

Localized church names share one downloadable snapshot containing eight indexes: `JapaneseAnalyzer` for Japanese, analysis-nori `KoreanAnalyzer` for Korean, analysis-common analyzers for English, Portuguese, and Indonesian, analysis-extra `VietnameseAnalyzer` for Vietnamese, and `SmartChineseAnalyzer` indexes for `zh-Hans` and `zh-Hant`. Chinese records retain exact script-specific fields while both query scripts also search a canonical Simplified field. Query-language detection is independent of the UI display language and recognizes Vietnamese orthography before the overlapping Portuguese Latin-script heuristics; an explicit CLI `--language` can still override automatic detection.

If no geoname is present, the browser or app may request device location and retry with a default 25 km radius. A geoname in the query always takes precedence over device location.

Search responses and church details use Kotlin-serializable JSON models. Church detail includes name, denomination, address, website, and typed Facebook, X, Instagram, and YouTube profiles. Domains in `resources/catalog/excludedChurchListingDomains.txt` are rejected before crawling and at publication time; when no church-owned website remains, the public link is the Google Maps page constructed from its CID. Crawled content already distinguishes ordinary pages from sermons so a future sermon index can use a separate result model without changing church search.

The maintained field-by-field build contract, data provenance, analyzers, responsible Kotlin classes, snapshot flow, and schema-change checklist are in [`core/INDEX.md`](core/INDEX.md). Query construction and geographic behavior are documented separately in [`core/SEARCH.md`](core/SEARCH.md).

## Resource layout

- `resources/raw`: immutable acquisition inputs.
- `cache/web-pages`: content-addressed cached HTML, manifest, and URL cache map.
- `resources/catalog/churches.json`: canonical church records.
- `resources/catalog/denominations.json`: standalone data-driven denomination catalog.
- `resources/geonames/japan.json`: generated prefecture and municipality/ward aliases and geo areas.
- `resources/evidence`: typed directory and social-account evidence.
- `resources/cleanup`: deterministic rules, candidates, human overrides, and auditable decisions.
- `cache/search-indexes/churches/<version>`: generated Lucene index, archive, and checksum manifest; only the cache README is committed.

## Crawl and cleanup

Build and refresh cached websites. The default follows same-domain links through depth 1; `--max-depth 0..3` and `--max-pages-per-church` make deeper crawls explicit and bounded. Each page stores outgoing links, and catalog import materializes stable `Webpage` nodes with `LINKS_TO` relationships for future PageRank work. Locale home-page headings become official localized church names without overwriting reviewed/manual values.

```sh
./gradlew :crawl:run --args='refresh --resources resources --max-concurrency 6 --max-depth 1 --max-pages-per-church 12'
```

Chinese localization is an explicit, reviewable migration. Validate and dry-run first; the dry run writes JSON and text reports under `resources/review/` without changing `churches.json`. Detailed rule matches, unmatched segments, and explanations stay in that review report, while the canonical catalog retains compact provenance and is written without formatting/default-valued fields:

```sh
./gradlew :crawl:validateChineseDictionaries
./gradlew :crawl:dryRunChineseLocalizedNames
./gradlew :crawl:chineseGoldenTest
./gradlew :crawl:generateChineseLocalizedNames
./gradlew :crawl:reindexChineseFields
```

The migration is idempotent and preserves official, manual, and reviewed values. See [`docs/development/chinese-localization.md`](docs/development/chinese-localization.md) for dictionary precedence, reports, corrections, reindexing, and rollback.

Vietnamese localization uses the same reviewable boundary with direct JA→VI dictionaries, denomination and GeoName catalogs, and compact provenance in `churches.json`:

```sh
./gradlew :crawl:dryRunVietnameseLocalizedNames
./gradlew :crawl:generateVietnameseLocalizedNames
./gradlew :crawl:reindexVietnameseFields
```

The dry run writes `resources/review/vietnamese-localization-report.json` and must report `indexingChanges=0` after an applied migration. The static Vietnamese home page is `/vi/index.html`; rebuild Neo4j projections and run `:server:generateChurchPages` after catalog changes.

Most official denomination directories are configured in `resources/sources/denominations.json`. UCCJ and JBC use validated table-specific crawlers because their complete official lists are also authoritative negative evidence. Use `--force-refresh --dedicated-only` to invalidate and refetch just those two pages before reconciling the catalog:

```sh
./gradlew :crawl:run --args='crawl-denomination-directories --resources resources'
./gradlew :crawl:run --args='crawl-denomination-directories --resources resources --force-refresh --dedicated-only'
```

Denomination resolution order is:

1. canonical denomination name or alias in the Google place church name;
2. canonical name or alias in cached home, main, or about-page content;
3. exact or high-confidence official-directory church name and address;
4. official website-domain evidence;
5. Koog/Ollama guess over bounded chunks of visible crawled-page text;
6. `NOT_DETERMINED` plus an auditable review decision.

Accepted fields record `[programmatically-determined]`, `[llm-determined]`, or `[human-determined]` provenance. LLM results below the configurable threshold are never published. Manual overrides take precedence:

```sh
./gradlew :crawl:run --args='override-denomination --resources resources --church-id google:123 --denomination-id JBC --note reviewed'
```

Run cleanup as a review-only dry run before applying it:

```sh
df -h /media/joel/llms
./gradlew :crawl:run --args='cleanup-llm --resources resources --model qwen3:4b --confidence-threshold 0.80 --limit 25 --dry-run'
```

Always run `df` immediately before an Ollama inference, pull, or model removal. Unit tests use deterministic fake matchers and never require Ollama.

Social candidates use `resources/evidence/social-accounts.json`. Resolution order is deliberately strict:

1. a cached church website page links directly to the exact account URL: 100% programmatic match;
2. normalized Google place and social names are equal, or either contains the other exactly: 100% programmatic match;
3. sufficiently overlapping names are shortlisted for `churchNameMatchesByLlm`;
4. low scores and close competing candidates remain unmatched for review.

```sh
df -h /media/joel/llms
./gradlew :crawl:run --args='link-social --resources resources --model qwen3:4b --confidence-threshold 0.80 --limit 100 --dry-run'
```

Decisions are written to `resources/cleanup/social-decisions.json`. Remove `--dry-run` to publish accepted profiles and their provenance into the canonical church catalog.

English church names first use official English text found in cached webpages, the church domain, or linked social profiles. Only unresolved names are translated locally with CyberAgent CAT-Translate-7b. Install the exact Q4_K_M model and the checked-in 4096-token Ollama configuration:

```sh
df -h /media/joel/llms
ollama pull hf.co/mradermacher/CAT-Translate-7b-GGUF:Q4_K_M
ollama create cat-translate:7b-q4_k_m -f crawl/src/main/resources/ollama/CAT-Translate-7b.Modelfile
```

Then populate and validate the complete catalog. Publication fails instead of leaving any `englishName` blank:

```sh
df -h /media/joel/llms
./gradlew :crawl:run --args='english-names --resources resources --model cat-translate:7b-q4_k_m --dry-run'
```

Remove `--dry-run` to atomically update `resources/catalog/churches.json`. `--programmatic-only` is available for an Ollama-free completeness check.

The production cleanup entry point is `./gradlew :crawl:dataCleanup`. Every invocation writes a unique `logs/YYYY-MM-DD-HH-mm-data-cleanup-stat.log` report with deterministic/LLM counts, unresolved count, errors, LLM timeouts, duration, and throughput. Static page generation is intentionally read-only and consumes the current canonical catalog without invoking this cleanup gate.

## Build an index snapshot

```sh
./gradlew :crawl:prepareChurchGeoNames
./gradlew :crawl:buildSearchSnapshot -PcrossmapIndexVersion=local-dev
```

The geoname task joins official `JP.txt` and language-tagged `alternatenames/JP.txt` data, then maintains separate `*-title-missing.csv` and `*-address-missing.csv` human-review queues under `resources/geonames`; title names take priority when a name has both uses. Each snapshot contains `index/{ja,en,ko,pt,id}`. Japanese indexes names, address, crawled pages, and geonames; the other indexes contain localized names plus only the title/address geonames that have translations in that index language. The manifest records the five languages, schema/index versions, lucene-kmp version, document count, archive size, archive SHA-256, and canonical-catalog SHA-256. Mobile clients download to a `.part` file, verify it, stage extraction, validate the manifest, and atomically switch the active version while retaining the previous working snapshot.

## CLI

```sh
./gradlew :cli:installDist
./cli/build/install/cm/bin/cm church search '教会 東京'
./cli/build/install/cm/bin/cm church search --language ko '오사카'
./cli/build/install/cm/bin/cm church search --language pt 'Osaka'
./cli/build/install/cm/bin/cm church search 'バプテスト' --latitude 35.6812 --longitude 139.7671 --radius-km 25
./cli/build/install/cm/bin/cm church index info
```

The CLI automatically locates the latest local Crossmap snapshot unless an explicit index path is supplied.

## Server and web client

```sh
./gradlew :server:run
```

`:server:run` first rebuilds and publishes the `development` search snapshot from the current canonical catalog. At startup the server accepts `latest.json` only when its schema is current, its index directory exists, and its source-catalog SHA-256 matches `resources/catalog/churches.json`; it never silently serves a stale index.

Open `/` to select a supported browser language initially. `vi-VN` maps to `vi`; `zh-CN`, `zh-SG`, and ambiguous `zh` map to `zh-Hans`; `zh-TW`, `zh-HK`, and `zh-MO` map to `zh-Hant`. Every page exposes an explicit locale switch, persists that choice in local storage, and lets it override later browser detection. Without JavaScript the root provides an English search fallback. `/ja/`, `/en/`, `/ko/`, `/pt/`, `/id/`, `/vi/`, `/zh-Hans/`, and `/zh-Hant/` can also be opened directly. Each directory contains generated `index.html`, `result.html`, and stable-English-slug church pages with canonical and `hreflang` links. Query language is detected independently by the server, so an English query on `/ja/result.html` still uses the English analyzer while results remain Japanese; Vietnamese queries use `VietnameseAnalyzer`, and Chinese results display the selected script while retaining the original Japanese name as secondary text.

Chrome asks the user to allow location access the first time Crossmap requests it. Use `http://localhost:8080`, not the wildcard bind address `http://0.0.0.0:8080`; the client redirects the latter to `localhost` because browser geolocation requires HTTPS or a trusted local-development origin. If permission is denied or location times out, search remains nationwide and no nearby-area heading is shown.

Run the real browser regression with:

```shell
./gradlew :server:lightpandaE2eTest
```

It rebuilds the latest snapshot and generated pages, starts Netty, exercises all five language directories in Lightpanda, verifies cross-language query detection, and follows each language's same-slug static church-detail link.

Generate the complete portable site with:

```sh
./gradlew :server:generateChurchPages
```

The output is the `webclient/` directory. Ktor serves this directory unchanged in development. For production, publish the same directory as the Cloudflare Pages asset directory; no server-side template runtime is required. Navigation, API requests, and assets are origin-relative, so generation and local serving do not require a domain argument. Canonical, hreflang, Open Graph, JSON-LD, and sitemap metadata default to `https://www.crossmap.co.jp`; `-PcrossmapSiteBaseUrl=...` is an optional SEO override, not a runtime dependency. On localhost, Ktor serves both the static files and `/api/v1/`. On Cloudflare Pages, route or proxy the same-origin `/api/v1/` path to Ktor; Ktor's CORS response also permits direct API requests from the production domain, `*.pages.dev` previews, and localhost development origins. Generation fails if a required church name, denomination name, or collision-disambiguating location is missing.

Static rendering uses all processors visible to the JVM by default (16 workers on an 8-core/16-thread machine). Use `-PcrossmapStaticSiteParallelism=N` to cap or benchmark it; `N=1` restores sequential generation.

## Mobile

```sh
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
```

Android and iOS open the downloaded snapshot locally and search with lucene-kmp on device. If a query has no Japanese geoname, the platform location provider supplies the default geo center when permission/location is available. Result taps open a local church detail with Japanese/English name, denomination, address, website, and social links; website/social buttons use the platform URL opener. Network access is only needed to download a new snapshot; an already activated index supports offline launch and search.

## Verification

```sh
./gradlew --no-configuration-cache \
  :core:jvmTest \
  :crawl:test \
  :cli:test \
  :server:test \
  :app:androidApp:assembleDebug \
  :app:shared:compileKotlinIosSimulatorArm64
```

The crawler suite covers deterministic denomination precedence, Japanese fuzzy name/address normalization, LLM thresholds with fake matchers, resumable evidence stages, copied-cache reuse, official-directory extraction, direct social hyperlinks, exact/containing social names, and low-score rejection.

## Common Gradle tasks

The `Module(s)` column names every module that a task directly operates or orchestrates. Cross-module rows deliberately list more than one module so their real scope is visible before running them.

### Core and whole-project verification

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `core` | `./gradlew :core:jvmTest` | Runs shared model, address parsing, geoname resolution, index, query planning, ranking, and search tests on JVM. | Read-only apart from Gradle build output. | Test reports under `core/build/reports/tests`. |
| `core`, `crawl`, `cli`, `server`, `app` | `./gradlew test` | Runs every available JVM/unit-test task in the project graph. | Can be slower than focused module tests; does not run the opt-in real Lightpanda test. | Per-module reports under `*/build/reports/tests`. |
| `core`, `crawl`, `cli`, `server`, `app` | `./gradlew --no-configuration-cache :core:jvmTest :crawl:test :cli:test :server:test :app:androidApp:assembleDebug :app:shared:compileKotlinIosSimulatorArm64` | Performs the release-oriented verification command used by this project. | Requires Android and iOS/Kotlin Native toolchains for the respective app steps. | Tests plus Android and iOS compilation artifacts. |

### Crawl, cleanup, and indexing

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `crawl`, `resources` | `./gradlew :crawl:fetchUrl -Purl="<URL>"` | Fetches and tests a single web page or Google Maps CID URL through the full fetch pipeline (HttpClient → LightPanda → Playwright) with verbose logging. | Tests network connection, caching, rendering, and Cloudflare status. | Console fetch metrics and `cache/web-pages/`. |
| `crawl`, `core`, `resources` | `./gradlew :crawl:googleSavedPlaces` | Runs the entire Google Saved Places workflow based on the downloaded Takeout CSV files (CSV reading, CID resolution, canonical fetched-title selection, official-directory reconciliation including scored romanized-English/Japanese address comparison, and catalog promotion). | Reads Takeout CSV path from `local.properties`; updates canonical catalog after all quality gates pass. | `resources/catalog/churches.json` and stage logs. |
| `crawl`, `resources` | `./gradlew :crawl:dataCleanup` | Runs deterministic cleanup followed by configured Koog/Ollama fallbacks and completeness checks. | May invoke Ollama and update catalog data. Run `df -h /media/joel/llms` first. | Cleanup caches and `logs/*-data-cleanup-stat.log` plus translation-detail logs. |
| `crawl`, `resources` | `./gradlew :crawl:prepareGeoNameCache` | Downloads/prepares Japanese GeoNames inputs when absent and builds the searchable local geoname cache. | May download GeoNames archives on the first run. | Files below `cache/geoname`. |
| `crawl`, `core`, `resources` | `./gradlew :crawl:buildGeoCatalog` | Rebuilds the runtime administrative resolver catalog from JMA municipalities and designated-city wards, restoring official JIS check digits and parent-qualified ward aliases. | Depends on `prepareGeoNameCache`; normally runs transitively before church geoname/address preparation. | `resources/geonames/japan.json` and a timestamped build-geonames log. |
| `crawl`, `resources` | `./gradlew :crawl:prepareChurchGeoNames` | Detects title/address geonames, joins translations, and refreshes missing-translation queues. | Depends on `buildGeoCatalog`, which prepares the raw cache and rebuilds the canonical JMA/GeoNames administrative catalog first. | `resources/geonames/church-ja-all.json`, usage data, split title/address missing CSVs, and a timestamped log. |
| `crawl`, `core`, `resources` | `./gradlew :crawl:normalizeChurchAddresses` | Normalizes all Japanese church addresses through the local Geolonia checkout and enriches them with typed components/codes. | Depends on `prepareChurchGeoNames`; reads `crossmap.geoloniaNormalizerDir` from `local.properties`; may run npm install/build for that checkout once. | `cache/address-normalization/normalized-addresses.json` and `logs/*-address-normalization.log`. |
| `crawl`, `core`, `resources` | `./gradlew :crawl:buildSearchSnapshot -PcrossmapIndexVersion=development` | Builds the five-language schema-current Lucene snapshot from the current canonical catalog. | Depends on geoname preparation and address normalization; applies the website exclusion/fallback policy and does not itself run LLM cleanup. | `cache/search-indexes/churches/development`, archive, checksum manifest, and `latest.json`. |
| `crawl` | `./gradlew :crawl:test` | Runs crawler, cleanup, dictionary, geoname, localization, address-pipeline, and logging tests. | Uses fake LLM collaborators; no Ollama required. | `crawl/build/reports/tests`. |

### CLI

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `cli`, `core` | `./gradlew :cli:run --args=\"church search '東京バプテスト教会' --json\"` | Runs a developer search against the latest compatible local snapshot. | Read-only; requires an existing current snapshot. | Search JSON and query-plan/timing logs on the console. |
| `cli` | `./gradlew :cli:installDist` | Creates an installed `cm` command tree. | Compiles the CLI distribution. | `cli/build/install/cm/bin/cm`. |
| `cli`, `core` | `./gradlew :cli:test` | Runs CLI parsing, index discovery, language routing, and real search behavior tests. | Creates only test/build output. | `cli/build/reports/tests`. |

### Server and web client

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `server`, `core`, `webclient` | `./gradlew :server:runCurrentIndex` | Starts Ktor with the latest already-built compatible snapshot and serves the vanilla-JavaScript client. | Does not crawl, invoke Ollama, rebuild the index, or regenerate static pages. Best choice for local search iteration and benchmarks. | HTTP server on the configured host/port and request/query timing logs. |
| `server`, `core`, `crawl`, `resources`, `webclient` | `./gradlew :server:run` | Rebuilds data-dependent artifacts and then starts the complete web application. | Depends on `:crawl:buildSearchSnapshot` and the read-only `generateChurchPages` task. | Current snapshot, generated church pages, logs, and running HTTP server. |
| `server`, `crawl`, `resources`, `webclient` | `./gradlew :server:generateChurchPages` | Generates the auto-localizing root plus every localized index, result, church-detail page, manifest, and sitemap in a bounded CPU-sized worker pool. | Reads the canonical catalog without running cleanup or invalidating the current snapshot; defaults to all JVM processors and accepts `-PcrossmapStaticSiteParallelism=N`; the site origin is only an optional SEO override. | Deployable `webclient/` tree with generated `ja/en/ko/pt/id/vi/zh-Hans/zh-Hant` directories. |
| `server`, `core` | `./gradlew :server:test` | Runs API, startup-safety, snapshot, static-site, and server integration tests. | Does not opt into the external Lightpanda flow. | `server/build/reports/tests`. |
| `server`, `core`, `crawl`, `resources`, `webclient` | `./gradlew :server:lightpandaE2eTest` | Runs the real index-to-JSON-to-results-to-static-detail browser flow. | Rebuilds the snapshot/pages and inherits their cleanup side effects; requires Lightpanda. | E2E test report under `server/build/reports/tests`. |

### Mobile app

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `app/androidApp`, `app/shared`, `core` | `./gradlew :app:androidApp:assembleDebug` | Builds the Android app using shared on-device search, snapshot, UI, and geolocation code. | Requires the Android SDK. | Debug APK under `app/androidApp/build/outputs/apk`. |
| `app/iosApp`, `app/shared`, `core` | `./gradlew :app:shared:compileKotlinIosSimulatorArm64` | Compiles the shared iOS simulator target used by the iOS launcher. | Requires the Kotlin/Native Apple toolchain. | Kotlin/Native compilation output under `app/shared/build`. |
