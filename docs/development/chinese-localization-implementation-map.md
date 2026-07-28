# Chinese localization implementation map

## Modules and extension points

| Concern | Current implementation | Planned modification |
|---|---|---|
| Locale model and fallback | `core/Language.kt`, `LocalizedName`, `LocalizedText`, `localizedDomainText` | Add exact BCP 47 `zh-Hans` and `zh-Hant` enum entries, script-aware parsing/fallback, and seven-language `LocalizedText` coverage. |
| Church, pastor, denomination, and geoname names | `core/Models.kt`, `DenominationNames.kt`, `GeoName`, language-tagged `LocalizedName` lists | Keep the existing language-tagged representation; add provenance-bearing localized-name metadata without replacing Japanese primary values. |
| Name localization | `crawl/MultilingualChurchNameLocalizer.kt`, `ChurchNameEnglishDictionary.kt`, `CongregationTermDictionary.kt`, `ChurchGeoNameTranslationCatalog.kt` | Extend dictionary filename parsing beyond two-letter targets, add direct JA→zh-Hans/zh-Hant terms, explicit precedence, script-conversion fallback, confidence/review metadata, and reports. |
| Google Saved Places conversion | `GoogleSavedPlacesCrawler.kt` and cleanup/promotion | Generate both Chinese variants during the existing multilingual localization stage and preserve reviewed/official values during reprocessing. |
| Website evidence | `ChurchWebsiteCrawler.kt`, `CrawledPage`, `CrawlManifest` | Replace the implicit home-page-only discovery rule with configurable breadth-first same-site crawling by depth; extract official language alternate pages and official names. |
| Website graph | crawl-time Neo4j catalog commands and migrations | Add idempotent `WebPage` nodes and `LINKS_TO` relationships owned by church website evidence. Neo4j remains a crawl/build-time dependency; Ktor remains database-independent. |
| Neo4j church localization | catalog importer/exporter and schema migrations | Persist both script-specific localized values and provenance metadata using the established church localization shape; add constraints/indexes and idempotent upserts. |
| Static web/UI | `LocalizedStaticSiteGenerator`, `UiMessageCatalog`, shared `UiLanguage` | Generate both Chinese locale trees, correct `lang`/hreflang/metadata, browser mapping, persisted explicit selection, Japanese secondary name, and stable locale-independent church IDs. |
| Index construction | `core/ChurchIndex` | Bump schema; add script-specific exact/analyzed fields plus Simplified canonical Chinese fields for names, aliases, geonames, denominations, and ministers. |
| Query/ranking | `core/ChurchSearchEngine` | Preserve the original query, canonicalize only query text, search exact selected-script/other-script/canonical/Japanese fields with explicit boost ordering, and retain one result per church ID. |
| Operations | Gradle tasks and crawl CLI commands | Add dry-run generation, dictionary validation, review report, migration/reprocessing, and reproducible reindex commands. |

## Current flows

Name ingestion currently decomposes a Google title, resolves reviewed dictionaries/geonames, emits `LocalizedName` values for EN/KO/PT/ID, promotes them into `ChurchRecord`, and imports/exports those records through the catalog boundary. `ChurchIndex` builds one index per language with a per-language analyzer and generic plus language-specific name fields. Static generation iterates `Language.entries` and consumes locale-specific XML messages.

Website refresh currently queues the home URL and previously stored pages, caps traversal at six URLs, and discovers links only from the home page. Pages are embedded in each church record and represented in the crawl manifest; hyperlink relationships are not persisted as graph entities.

## Compatibility and migration risks

- `Language.fromCode` currently strips the script subtag, so both Chinese locales would collapse to `zh`; this must change before adding enum entries.
- Many exhaustive `when` expressions, message directories, generated Compose resources, denomination-name catalogs, tests, and index paths derive from `Language.entries` and will fail until seven-language coverage is complete.
- Dictionary discovery accepts only two-letter locale codes and cannot load `zh-Hans`/`zh-Hant` today.
- Adding fields requires a Lucene schema bump and full deterministic reindex; existing index directories must fail clearly rather than open with the wrong schema.
- Generated values must never replace official/manual/reviewed Chinese values. Reprocessing and Neo4j upserts therefore need provenance-aware precedence and idempotency tests.
- Chinese canonicalization must not alter Lucene operators, field names, wildcards, escapes, or quoted syntax markers.
- Crawl depth must remain bounded, same-site, canonicalized, cache-aware, and cycle-safe. The link graph must not make Ktor depend on Neo4j.
- Existing five-locale output, search scores, stable URLs, catalog parity, and immutable source corpora must remain unchanged except for additive Chinese data.

## Ordered implementation

1. Add the locale/data model and migration-safe provenance model.
2. Extend dictionaries, direct Chinese generation, fallback conversion, validation, and review reporting.
3. Persist Chinese values through JSON and Neo4j, then add static output/UI resources.
4. Add Chinese Lucene fields, normalization, query expansion, and ranking tests.
5. Add depth-configurable website crawling and Neo4j page/link persistence; use official alternate-language evidence to correct TMC.
6. Add and live-validate the dedicated JCCC crawler.
7. Run migration/reindex fixtures, full tests, browser E2E, Neo4j parity/integrity, and documentation audit.
