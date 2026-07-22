# Crossmap Church Search 1.0 Implementation Plan

## 1. Project foundation

- [x] Align Gradle plugins and dependency versions with the working BBL/lucene-kmp stack.
- [x] Add Kotlin serialization, Clikt, Okio, Ktor client/server, and lucene-kmp dependencies.
- [x] Add a local sibling `lucene-kmp` composite-build switch.
- [x] Replace generated greeting code with shared Crossmap domain and search interfaces.
- [x] Prove a JVM-built fixture index can be opened and queried by the shared KMP engine.

## 2. Canonical resources and crawler

- [x] Create the canonical `resources/raw`, `resources/crawl`, `resources/catalog`, `resources/geonames`, and versioned index layout.
- [x] Reimplement the gmap Saved Places workflow—CSV seeds, exclusions, CID cache/fetch, Google Maps parsing, normalization, website extraction, and reporting—inside standalone Crossmap stages.
- [x] Seed the standalone Crossmap catalog from the clean historical corpus; no runtime/build dependency on the gmap repository remains.
- [x] Add a standalone RFC 4180 Google Takeout Saved Places reader for the real Japanese `タイトル,メモ,URL,コメント` format, stable CID extraction, cross-list deduplication, error reporting, and raw seed JSON.
- [x] Keep Google Takeout CSV rows as the raw source, then enrich `seeds.json` during Google Maps resolution with language-tagged components, deterministic Japanese and JA/EN/KO/PT/ID localized names, and transliteration only for unresolved proper-name parts.
- [x] Load language-pair dictionaries by the generic `<source>-<target>-<category>-dictionary.csv` convention, support reverse lookup, keep JA-EN/KO/PT/ES/ID concept keys complete and duplicate-free, and model multilingual congregation terms independently of language pairs.
- [x] Replace Optimaize short-name detection with the vendored Cybozu/Shuyo detector and short-text profiles; classify Japanese/Hangul scripts deterministically and retain canonical `ja`/`en` localized-name entries.
- [x] Treat `カトリック教会.csv` membership as authoritative programmatic `CATHOLIC_JP` denomination evidence throughout parsing and promotion, including reuse of older candidate caches, while preserving human overrides.
- [x] Resolve raw Saved Places seeds through copied CID HTML cache first, plain HTTP second, and lightweight Lightpanda rendering last; parse name, coordinates, address, website, and category into raw church candidates with an audit report.
- [x] Apply exclusion lists and Catholic-list non-church filtering from gmap, normalize candidate names during resolution, and perform entity-level deduplication before promotion.
- [x] Treat `resources/catalog/excludedChurchListingDomains.txt` as the shared website boundary: reject listing/search/map aggregators before page acquisition or cleanup, remove their cached page evidence, and replace missing/excluded public website URLs with the church's Google Maps CID URL.
- [x] Feed resolved candidates into the existing Crossmap deterministic → official-directory/page evidence → LLM → human-override cleanup workflow; do not create a parallel cleanup implementation.
- [x] Produce timestamped source/crawl/cleanup completeness reports and promote only complete records into the canonical catalog.
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

### Official UCCJ and JBC church lists

- [x] Define the shared `DenominationChurchListCrawler` API and typed official-list JSON model.
- [x] Implement fresh, cache-aware `UCCJDenominationChurchListCrawler` parsing `https://uccj.org/diocese` into `resources/crawl/uccj-churches.json`.
- [x] Implement fresh, cache-aware `JBCDenominationChurchListCrawler` parsing `https://bapren.jp/church/` into `resources/crawl/jbc-churches.json`.
- [x] Remove JBBF member churches from denomination aliases and crawl the official JBBF address book into `resources/crawl/jbbf-churches.json` as membership evidence.
- [x] Reconcile official UCCJ/JBC entries with the pending catalog: add official denomination evidence to matching churches and remove unsupported stale labels without overriding human decisions.
- [x] Add real-name parser/reconciliation tests, including that イエス愛の教会 and 沼津キリストの教会 are not published as JBC churches.
- [x] Invalidate any old UCCJ/JBC source-page cache, fetch both official lists fresh, rebuild `resources/catalog/churches.json`, and verify the generated list/catalog statistics.

## 3. Japanese geonames

- [x] Use the official headerless GeoNames `JP.txt` dump plus language-tagged `alternatenames/JP.zip`; download and extract either cache when absent and join records by `geonameid`.
- [x] Download and validate JMA's multilingual `city.json`, merge full and suffix-free municipality aliases with GeoNames, and log before/after missing-translation coverage for EN/KO/PT/ID.
- [x] Build cached Japanese-to-English/Korean/Portuguese/Indonesian lexicons, while treating committed `resources/geonames/japan.json` names as detection-only.
- [x] Use longest-match tries so the 100,000+ GeoNames aliases can participate efficiently in church-title and Japanese-address analysis.
- [x] Generate `church-ja-all.json`, per-church title/address usage with the original Google Place title for detection review, and duplicate-free JA-to-EN/KO/PT/ID title-first/address-only missing-review CSV queues while preserving reviewed translations across reruns.
- [x] Clean Japanese geonames before decomposition, address matching, translation review, and indexing: exclude reviewed church-name collisions, katakana-only aliases, and numeric/kanji `丁目` address blocks while retaining mixed-script places such as `ユーカリが丘`.
- [x] Record source Google Maps title languages separately from Crossmap-generated localized names so available/spoken-language filtering has explicit provenance.
- [x] Add a generated catalog covering all 47 prefectures and current Japanese municipalities/wards with official codes.
- [x] Generate canonical aliases and administrative representative points; prefer prefectural/municipal government-office coordinates without treating them as search boundaries.
- [x] Implement common query normalization, longest-name-first extraction, prefecture disambiguation, duplicate-city unions, and location-token removal.
- [x] Use optional browser/app geolocation with a 50 km configurable default radius only when the query contains no recognized geoname.
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
- [x] Translate mixed church-name structures deterministically in source order, including composite geonames, multiple adjacent concepts, `geoname + concepts`, and `concepts + geoname` patterns before any LLM fallback.
- [x] Remove `宗教法人`, parenthesized `(宗教法人)`/`(宗)`, full-width variants, and adjacent slash/pipe/middle-dot/colon/dash decorations from Google church titles and localized aliases before decomposition or translation.
- [x] Recompose Portuguese, Spanish, and Indonesian names for natural Japanese display: move a terminal geoname to the front when Romance church/concept structure is present, and move leading `Igreja`/`Iglesia`/`Gereja` to trailing `教会`.
- [x] Preserve parenthesized Japanese kana readings as localized aliases, support reviewed per-church readings, and index Kuromoji hiragana readings plus untokenized exact-reading fields for names, denominations/traditions, categories, and geonames without dropping short proper-name particles.
- [x] Translate Portuguese `Igreja Pentecostal Deus É Amor` and related `Igreja Deus É Amor` variants (including decomposed Unicode accents) as reviewed whole phrases, use Portuguese geoname aliases, omit location connectors before a terminal geoname, and discard lower-quality synthesized Japanese aliases for Latin-source titles.
- [x] Fill missing Korean geonames from authoritative English romaji pronunciation, reject Hanja-style reviewed fallbacks, and enforce that Korean church names contain no accidental Latin except evidenced 3–4 letter church identifiers.
- [x] Write a timestamped LLM-composed-name detail log containing each Japanese church name and ordered child parts with type, translation, evidence, and translation method.
- [x] Route data-cleanup statistics, church-name translation statistics, and LLM-composed-name details through crawl-module Logback configuration to timestamped files and the console.
- [x] Give every crawl command a structured Logback quality-control report with inputs, settings, domain metrics, outputs, duration, and failure details, and enumerate every filename in the crawl README command table.
- [x] Add an `english-names` crawl command that atomically updates the catalog and refuses a partial result so every publishable church has an English-name URL component.
- [x] Install the `cyberagent/CAT-Translate-7b` Q4_K_M GGUF as `cat-translate:7b-q4_k_m`, configure a 4096-token context, and verify it runs fully on GPU.
- [x] Check disk capacity with `df` before any Ollama inference or model pull, then evaluate installed Japanese-capable models on labeled fixtures.

## 3b. Multilingual denomination and tradition names

- [x] Model concrete denominations separately from reusable church traditions, and record each denomination's tradition plus per-language name provenance.
- [x] Treat name decomposition and target-language word order as a translation/review technique; store the reviewed natural final name rather than a runtime composition program.
- [ ] Search each Japanese denomination's official site for its published English name before translating it.
- [ ] For globally organized denominations, require official names from their global or national bodies in every supported language; do not coin replacements.
- [ ] For names without official translations, check established internet usage, then translate semantic parts into a naturally ordered final name.
- [x] Complete and generate `denomination-ja/en/ko/pt/id-names.json`, with a 185 x 5 test proving exact ID coverage and nonblank names in every supported language.
- [x] Replace spreadsheet-hash `XLSX_*` denomination IDs with stable readable abbreviations and enforce the invariant in catalog tests.
- [x] Keep `NOT_DETERMINED` and `INDEPENDENT_CHURCH` as internal classification sentinels only; remove them from localized church names, web details, and generated pages.
- [x] Mark outsider movement labels such as `キリストの教会（無楽器派）` as non-prefix classifications so they cannot be composed into a church's public full name.
- [x] Record official-site, established-usage, or translated provenance for every denomination-language name.
- [x] Feed denomination name parts and tradition name parts into multilingual church-name localization without conflating the organization with its tradition.

## 4. Shared Lucene index and search

### Single-address-entity geoname filtering

- [x] Meet the interactive search latency target: at most 1 second per request, preferably at most 0.5 second, measured on the real server/index path.

- [x] Add a best-effort `JapaneseAddressNormalizer` and typed decomposition for prefecture, municipality, city ward, Kyoto street expression, locality/geoname, address number, and optional building.
- [x] Cover normal Tokyo, designated-city ward, county/town, building, and Kyoto `上る`/`下る` address examples with core unit tests.
- [x] Add exact Lucene address-entity/code fields, index normalized address parts, and increment the church index schema.
- [x] Add a crawl `normalize-addresses` stage using the local Geolonia checkout, cache its structured output, and emit a timestamped Logback report with one church per entry, detected normalization level, normalized components, summary counts by level, and detailed errors/timeouts.
- [x] Resolve a query to exactly one intended administrative geoname: explicit prefecture suffix wins; otherwise prefer the unique municipality/city interpretation; use a unique prefecture only when no city shares the term.
- [x] Implement and test `detectIntendedGeonameFromUserLocation` by selecting the nearest ambiguous candidate to browser/app coordinates with deterministic fallback when coordinates are unavailable.
- [x] Replace named-geoname distance-query unions with one exact address-entity filter; retain Lucene `LatLonPoint` radius filtering only for device-location fallback when no geoname is present.
- [x] Apply the device-radius filter to every query tier, collect matching documents nearest-first with `LatLonDocValuesField`, and prove the real Izu/UCCJ ordering with unit and Lightpanda tests.
- [x] Label device-assisted results with the nearest municipality (for example `伊豆市付近の「日本基督教団」の検索結果`) while keeping named administrative searches code-based.
- [x] Never interpret Japan-wide names inside denomination names as geo filters; cover `日本基督教団` and `日本バプテスト連盟` with resolver tests.
- [x] Pass browser/app user coordinates early enough to disambiguate named geonames, while preserving search when permission is denied.
- [x] Update query-plan and timing logs to show the chosen candidate, ambiguity decision, and the single exact address filter.
- [x] Rebuild the multilingual snapshot and verify `東京バプテスト教会` no longer creates 53 geo clauses and returns the exact church first with substantially lower `lucene.collect` time.
- [x] Add core, CLI, Ktor, shared-app, and Lightpanda webclient regression coverage for unique, explicit-prefecture, ambiguous-nearest, direct-island, and no-geoname device-radius searches.
- [x] Treat explicit Japanese and romanized administrative names as authoritative across every ranking tier, so `横浜町`, `Yokohama-cho`, and `Yokohamacho` cannot fall through to Yokohama City name matches.
- [x] Document the address normalization boundary, Kyoto best-effort behavior, query-intent rules, and remaining limitations in `core/SEARCH.md` and the field-by-field build contract in `core/INDEX.md`.

- [x] Dogfood the built `lc` command against Crossmap fixtures to compare field extraction, boosts, and result ranking; improve lucene-cli generically if the experiment exposes a missing capability.
- [x] Define serializable church, crawl, geoname, request, response, hit, page, error, and index-manifest models.
- [x] Build one Lucene document per church with boosted name/category/address/content fields and geo point/doc-values fields.
- [x] Build separate Japanese, English, Korean, Portuguese, and Indonesian indexes in one downloadable snapshot, using JapaneseAnalyzer, KoreanAnalyzer, EnglishAnalyzer, PortugueseAnalyzer, and IndonesianAnalyzer respectively for indexing and query parsing.
- [x] Detect query language independently of display language and route server, browser, app, and default CLI searches to the matching language index/analyzer.
- [x] Keep Japanese address/page content in the Japanese index; add NFKC-, case-, and whitespace-deduplicated title/address geoname terms to all five language indexes.
- [x] Enrich every indexed church with all five reviewed denomination names and index the language-matching denomination name in each analyzer-specific index.
- [x] Prove translated church names, denomination names, and title/address geonames through core and Ktor searches in all five supported languages without requiring a client-supplied query language.
- [x] Keep social profile metadata independently indexable so future social content can be added without changing church/result JSON contracts.
- [x] Keep crawled content type and optional sermon metadata independently indexable for a future sermon-result document model.
- [x] Implement shared text-plus-geo search, exact totals, stable ordering, pagination, distance, matching page detection, and snippets.
- [x] Rank search in three merged tiers: exact whole-name matches, name matches containing every analyzed query token, then full-query text matches within the resolved geoname area; keep the full query instead of removing the geoname text.
- [x] Log a structured query plan for every search, including language/analyzer selection, tokens, geoname resolution, all three query tiers, fields/boosts, geo filters, and merge rules; keep JSON output clean by routing logs to standard error.
- [x] Log monotonic duration and percentage breakdowns for every shared search-engine stage and the complete Ktor receive-to-send request path so search bottlenecks are directly visible.
- [x] Use mainland-focused geometry for ordinary Tokyo prefecture searches, exclude its nine remote island municipalities from implicit Tokyo expansion, keep explicit island searches available, and normalize leading-zero municipality codes.
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

### Five-language UI and portable static site

- [x] Define one canonical Android-compatible XML message catalog for Japanese, English, Korean, Portuguese, and Indonesian and validate exact key/placeholder parity.
- [x] Reuse that catalog from Compose and the JVM static-site generator; keep query-language detection independent from UI language.
- [x] Use only the canonical template names `index.html`, `result.html`, and `church.html`.
- [x] Keep `app.js` language agnostic and place only one UI language in each generated HTML document.
- [x] Redirect the root index by supported browser language first; otherwise use geolocation for Japan, Korea, Indonesia, Brazil, or Portugal, then fall back to English without rendering a manual language chooser.
- [x] Generate `/ja`, `/en`, `/ko`, `/pt`, and `/id` index, result, and church pages with stable language-independent English slugs.
- [x] Server-render church name, denomination, address, website, social links, canonical URL, reciprocal hreflang links, JSON-LD, and sitemap content.
- [x] Make `webclient/` a portable static artifact: Ktor serves it unchanged in development and Cloudflare Pages can publish it unchanged in production.
- [x] Update Lightpanda coverage for every localized route, cross-language query, and same-slug language switch.
- [x] Verify full static generation, message validation, server tests, and the Lightpanda browser flow.
- [ ] Verify Android/iOS app builds and tests later in the platform IDE/tooling workflow.
- [x] Update root, webclient, and app documentation for localization and deployment.

- [x] Implement `/api/v1/churches/search`, `/api/v1/indexes/churches/latest`, immutable archive download, and `/api/v1/health`.
- [x] Implement `/api/v1/churches/{id}` and a church detail page showing name, denomination, address, website, and typed social links.
- [x] Sanitize website URLs again at snapshot, API, and static-page boundaries so stale indexes/catalogs cannot expose excluded listing domains; cover search, detail, generated pages, and the real Lightpanda flow with regression tests.
- [x] Validate and open the configured index at server startup and return structured JSON errors.
- [x] Make `:server:run` rebuild the development snapshot, validate latest-index schema and canonical-catalog SHA-256, and cover `布佐キリスト教会` results/detail with a real Lightpanda E2E test.
- [x] Keep static page generation read-only, reload a newly published compatible snapshot without restarting Ktor, and verify localhost plus Cloudflare Pages API origins.
- [x] Serve the vanilla HTML/JavaScript client from Ktor.
- [x] Implement query, loading, error, empty, result, distance, snippet, link, and pagination UI states.
- [x] Push pagination offsets into browser history and restore the matching result page on browser Back/Forward navigation.
- [x] Replace visible language lists on index, result, and church pages with automatic locale selection; keep the active query in a search-first result header and apply a distinct Google/DuckDuckGo-inspired visual design.
- [x] Implement the vanilla-JavaScript `index.html` -> JSON-backed `result.html` -> server-rendered `church.html` navigation flow.
- [x] Add a persistent Japanese/English/Korean/Portuguese/Indonesian church-name selector to browser search results, API detail, and generated static detail pages.
- [x] Render the denomination in the selected display language on JSON-backed and generated static church detail pages, with denomination ID only as a fallback.
- [x] Return each generated English-name static detail URL in search JSON and use it for result links; omit stale page mappings without disabling the JSON search API, then regenerate pages through the Gradle run/E2E workflow.
- [x] Generate static FreeMarker church detail pages at English denomination/name slugs using root-relative page and canonical links.
- [x] Fail static publication when an English church name, known denomination English name, or collision-disambiguating English location is missing.
- [x] Serve generated `/{language}/{english-slug}.html` pages and provide the `generateChurchPages` Gradle task.
- [x] Render static language shells and church pages in a bounded processor-sized worker pool, with a Gradle parallelism override and full-output concurrency test.
- [x] Add Ktor API tests and a Lightpanda browser smoke test against `./gradlew :server:run`, covering index -> search JSON -> rendered result page -> church detail JSON/page.

## 7. Android and iOS app

- [x] Implement the shared Compose search screen and state/view-model layer.
- [x] Implement full-snapshot download to `.part`, SHA-256 verification, staging extraction, manifest validation, and atomic active-version switching.
- [x] Retain the previous working snapshot and support retry/redownload/offline launch.
- [x] Run all mobile searches locally through the shared lucene-kmp engine.
- [x] Implement platform link opening and all download/search/result/detail/error UI states.
- [x] Implement optional Android/iOS location permission and pass device coordinates as the no-geoname search fallback.
- [x] Add a preferred-language selector to the shared Compose app and render the selected `localizedNames` entry with safe English/Japanese fallback.
- [x] Render the selected language's denomination name with the translated church name in Compose church details, with denomination ID only as a fallback.
- [ ] Verify Android build/tests and an Android emulator search flow with the Android CLI.
- [ ] Verify iOS framework/tests and the shared golden-query fixture.

## 8. End-to-end acceptance

- [x] Materialize the available clean church data as a standalone canonical Crossmap catalog with no gmap build/runtime dependency.
- [x] Build a full church index snapshot from the standalone canonical corpus.
- [ ] Run the same golden query set through core, CLI, Ktor, Android, and iOS paths.
- [x] Cover Japanese name, denomination, address, website body, prefecture, city, ambiguous city, location-only, pagination, and no-result scenarios.
- [x] Run a real Lightpanda browser flow for all five localized church names and denomination searches, and verify all five denomination labels on the generated church detail page.
- [x] Document build, crawl, snapshot, CLI, server, web, Android, and iOS usage in the README.
