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

All crawled church-page text is searchable. A query is parsed longest-name-first against all 47 prefectures and the generated municipality/ward catalog. Recognized place names are removed from the text query and converted to Lucene geo filters. Duplicate municipality names are handled as a union, and prefectures expand to their municipality areas.

If no geoname is present, the browser or app may request device location and retry with a default 25 km radius. A geoname in the query always takes precedence over device location.

Search responses and church details use Kotlin-serializable JSON models. Church detail includes name, denomination, address, website, and typed Facebook, X, Instagram, and YouTube profiles. Crawled content already distinguishes ordinary pages from sermons so a future sermon index can use a separate result model without changing church search.

## Resource layout

- `resources/raw`: immutable acquisition inputs.
- `resources/crawl/pages`: content-addressed cached HTML.
- `resources/crawl/manifest.json`: URL, status, cache path, hash, acquisition mode, and failure metadata.
- `resources/catalog/churches.json`: canonical church records.
- `resources/catalog/denominations.json`: standalone data-driven denomination catalog.
- `resources/geonames/japan.json`: generated prefecture and municipality/ward aliases and geo areas.
- `resources/evidence`: typed directory and social-account evidence.
- `resources/cleanup`: deterministic rules, candidates, human overrides, and auditable decisions.
- `resources/indexes/<version>`: Lucene index, archive, and checksum manifest.

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

## Build an index snapshot

```sh
./gradlew :crawl:run --args='build-snapshot --resources resources --version local-dev'
```

Each snapshot records schema and index versions, lucene-kmp version, document count, archive size, and SHA-256. Mobile clients download to a `.part` file, verify it, stage extraction, validate the manifest, and atomically switch the active version while retaining the previous working snapshot.

## CLI

```sh
./gradlew :cli:installDist
./cli/build/install/cm/bin/cm search '教会 東京'
./cli/build/install/cm/bin/cm search 'バプテスト' --latitude 35.6812 --longitude 139.7671 --radius-km 25
./cli/build/install/cm/bin/cm church google:123
```

The CLI automatically locates the latest local Crossmap snapshot unless an explicit index path is supplied.

## Server and web client

```sh
CROSSMAP_INDEX_DIR=resources/indexes/churches/local-dev/index ./gradlew :server:run
```

Open the server root in a browser. The user flow is `index.html` -> `/api/v1/search` JSON -> `result.html` -> `/api/v1/churches/{id}` JSON -> `church.html`. All browser logic is vanilla JavaScript. Browser geolocation is requested only after the first search confirms that the query contains no geoname.

## Mobile

```sh
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
```

Android and iOS open the downloaded snapshot locally and search with lucene-kmp on device. Network access is only needed to download a new snapshot; an already activated index supports offline launch and search.

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
