# Crossmap Church Search

Crossmap is a standalone Kotlin Multiplatform church-search system for Japan. It crawls and normalizes church data, builds a downloadable lucene-kmp index, and exposes the same search behavior through the `cm` CLI, an on-device Compose app, and a Ktor JSON API used by a vanilla-JavaScript web client.

Crossmap does not require the former gmap project at build time or runtime. Existing HTTP responses were copied once as content-addressed cache files so repeated requests can be avoided.

## Modules

- `core`: serializable records, Japanese geoname resolution, Lucene schema/indexing, and the shared search engine.
- `crawl`: website refresh, official-directory crawling, evidence/candidate resolution, denomination cleanup, Koog/Ollama fallback, social linking, and snapshot construction.
- `cli`: the `cm` developer command.
- `server`: Ktor JSON API backed by lucene-kmp on JVM.
- `webclient`: `index.html` search, `result.html` results, and `church.html` detail rendering in vanilla JavaScript.
- `app/shared`: shared Compose UI, state, snapshot management, local search, and location abstraction.
- `app/androidApp` and `app/iosApp`: platform launchers, permissions, storage paths, and geolocation.
- `resources`: durable inputs, crawl cache, evidence, review decisions, canonical catalogs, geonames, and versioned indexes.

## Search behavior

All crawled church-page text is searchable. A query is parsed longest-name-first against all 47 prefectures and the generated municipality/ward catalog. Exact and all-name-token tiers keep the complete query. The final geographic tier selects one intended address entity and filters its stable geoname code; the remaining words are scored inside that area. Duplicate municipality and ward names are resolved from browser/app coordinates when available, and otherwise remain unresolved instead of silently searching several unrelated areas. A geoname-only query such as `東京` or `Tokyo` uses the geographic filter with no text requirement.

Localized church names share one downloadable snapshot containing five indexes: `JapaneseAnalyzer` for Japanese, analysis-nori `KoreanAnalyzer` for Korean, and the analysis-common `EnglishAnalyzer`, `PortugueseAnalyzer`, and `IndonesianAnalyzer` for their respective languages. Query-language detection is independent of the UI display language and routes each query to its matching index/analyzer; an explicit CLI `--language` can still override automatic detection.

If no geoname is present, the browser or app may request device location and retry with a default 25 km radius. A geoname in the query always takes precedence over device location.

Search responses and church details use Kotlin-serializable JSON models. Church detail includes name, denomination, address, website, and typed Facebook, X, Instagram, and YouTube profiles. Domains in `resources/catalog/excludedChurchListingDomains.txt` are rejected before crawling and at publication time; when no church-owned website remains, the public link is the Google Maps page constructed from its CID. Crawled content already distinguishes ordinary pages from sermons so a future sermon index can use a separate result model without changing church search.

The maintained field-by-field build contract, data provenance, analyzers, responsible Kotlin classes, snapshot flow, and schema-change checklist are in [`core/INDEX.md`](core/INDEX.md). Query construction and geographic behavior are documented separately in [`core/SEARCH.md`](core/SEARCH.md).

## Resource layout

- `resources/raw`: immutable acquisition inputs.
- `resources/crawl/pages`: content-addressed cached HTML.
- `resources/crawl/manifest.json`: URL, status, cache path, hash, acquisition mode, and failure metadata.
- `resources/catalog/churches.json`: canonical church records.
- `resources/catalog/denominations.json`: standalone data-driven denomination catalog.
- `resources/geonames/japan.json`: generated prefecture and municipality/ward aliases and geo areas.
- `resources/evidence`: typed directory and social-account evidence.
- `resources/cleanup`: deterministic rules, candidates, human overrides, and auditable decisions.
- `cache/search-indexes/churches/<version>`: generated Lucene index, archive, and checksum manifest; only the cache README is committed.

## Crawl and cleanup

Build and refresh cached websites:

```sh
./gradlew :crawl:run --args='refresh --resources resources --max-concurrency 6'
```

Official denomination directories are configured in `resources/sources/denominations.json` using CSS selectors, not denomination-specific Kotlin classes:

```sh
./gradlew :crawl:run --args='crawl-denomination-directories --resources resources'
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

The production cleanup entry point is `./gradlew :crawl:dataCleanup`. Every invocation writes a unique `logs/YYYY-MM-DD-HH-mm-data-cleanup-stat.log` report with deterministic/LLM counts, unresolved count, errors, LLM timeouts, duration, and throughput. `:server:generateChurchPages` depends on this cleanup gate.

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

Open the server root in a browser. The user flow is `index.html` -> `/api/v1/churches/search` JSON -> `result.html` -> the generated `/church/{english-name}.html` detail page. Each search hit carries its canonical `detailUrl`; result links do not expose Google Place IDs. The server accepts the generated page manifest only when its catalog SHA-256 matches the current catalog and it covers every indexed church. All browser logic is vanilla JavaScript. Browser geolocation is requested only after the first search confirms that the query contains no geoname.

Run the real browser regression with:

```shell
./gradlew :server:lightpandaE2eTest
```

It rebuilds the latest snapshot and generated pages, starts Netty, renders the `布佐キリスト教会` result page in Lightpanda, verifies that the old `/church.html?id=...` link is absent, and follows the English-name static church-detail link.

Static detail pages use `/church/{denomination-English}-{church-English}.html`. Supply a JSON object mapping denomination IDs to English names, for example `{"JBC":"Japan Baptist Convention"}`, then generate development or production pages:

```sh
./gradlew :server:generateChurchPages \
  -PdenominationEnglishNames=resources/catalog/denomination-en-names.json
```

The default output is `webclient/church` (generated and gitignored). Generated page and canonical links are root-relative `/church/...` paths, so the same files work on localhost and production. Generation fails if a church English name, a known denomination English name, or a collision-disambiguating English location is absent.

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
| `crawl`, `resources` | `./gradlew :crawl:readGoogleSavedPlaces` | Reads the Google Saved Places CSV configured in `local.properties` and creates standalone seed records. | Reads local input; does not require the former gmap project. | Seed/cache data and a timestamped quality-control log. |
| `crawl`, `core`, `resources` | `./gradlew :crawl:googleSavedPlacesSource` | Reads saved places and resolves them into crawl source records. | Depends on `readGoogleSavedPlaces`; rejects configured listing domains before source processing and substitutes Google Maps CID links. | Sanitized source/evidence cache and stage logs. |
| `crawl`, `resources` | `./gradlew :crawl:googleSavedPlacesDataCleanup` | Runs the complete saved-place source and promotion workflow. | May update the canonical church catalog; inspect logs before committing data. | `resources/catalog/churches.json` and timestamped logs. |
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
| `server`, `core`, `crawl`, `resources`, `webclient` | `./gradlew :server:run` | Rebuilds data-dependent artifacts and then starts the complete web application. | Depends on `:crawl:buildSearchSnapshot` and `generateChurchPages`; the latter depends on cleanup and can invoke Ollama or update catalog data. | Current snapshot, generated church pages, logs, and running HTTP server. |
| `server`, `crawl`, `resources`, `webclient` | `./gradlew :server:generateChurchPages` | Generates every localized static church-detail page and its manifest from the reviewed denomination catalogs. | Depends on church cleanup and geoname preparation; it validates the committed 185-name catalogs and does not overwrite them with the opt-in denomination LLM task. | Generated files below `webclient/church`. |
| `server`, `core` | `./gradlew :server:test` | Runs API, startup-safety, snapshot, static-site, and server integration tests. | Does not opt into the external Lightpanda flow. | `server/build/reports/tests`. |
| `server`, `core`, `crawl`, `resources`, `webclient` | `./gradlew :server:lightpandaE2eTest` | Runs the real index-to-JSON-to-results-to-static-detail browser flow. | Rebuilds the snapshot/pages and inherits their cleanup side effects; requires Lightpanda. | E2E test report under `server/build/reports/tests`. |

### Mobile app

| Module(s) | Command | What it does | Dependencies and side effects | Output |
| --- | --- | --- | --- | --- |
| `app/androidApp`, `app/shared`, `core` | `./gradlew :app:androidApp:assembleDebug` | Builds the Android app using shared on-device search, snapshot, UI, and geolocation code. | Requires the Android SDK. | Debug APK under `app/androidApp/build/outputs/apk`. |
| `app/iosApp`, `app/shared`, `core` | `./gradlew :app:shared:compileKotlinIosSimulatorArm64` | Compiles the shared iOS simulator target used by the iOS launcher. | Requires the Kotlin/Native Apple toolchain. | Kotlin/Native compilation output under `app/shared/build`. |
