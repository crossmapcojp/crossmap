# Crossmap Church Search

This document describes the current church-search behavior implemented in the shared `core` module. It is intended to make the ranking and geolocation decisions reviewable before future search-quality changes.

The main implementation is in:

- [`ChurchIndex.kt`](src/commonMain/kotlin/jp/co/crossmap/ChurchIndex.kt): index fields, language analyzers, and document construction.
- [`GeoNameResolver.kt`](src/commonMain/kotlin/jp/co/crossmap/GeoNameResolver.kt): geoname recognition and location resolution.
- [`ChurchSearchEngine.kt`](src/commonMain/kotlin/jp/co/crossmap/ChurchSearchEngine.kt): query construction, ranking, geo filtering, pagination, and response creation.
- [`ChurchSearchEngineTest.kt`](src/jvmTest/kotlin/jp/co/crossmap/ChurchSearchEngineTest.kt): search behavior and ranking examples.

## Search overview

A search runs through three relevance tiers:

1. Exact whole-name match.
2. Church-name match containing every analyzed query token.
3. Full-query text match constrained to the resolved geographic area.

The three tiers are combined into one Lucene Boolean query. Lucene deduplicates documents, calculates the total, and applies pagination. Large score boosts preserve the tier order:

| Tier             |       Current boost | Purpose                                                                                                          |
|------------------|--------------------:|------------------------------------------------------------------------------------------------------------------|
| Exact whole name |         `1,000,000` | Put an exact church name first.                                                                                  |
| All name tokens  |             `1,000` | Find expanded names that contain every query word.                                                               |
| Geo/remainder    | normal field boosts | Find churches whose non-generic remainder matches `search_compact` inside the detected address entity.            |

Scores determine ordering within each tier.

## Language-specific indexes

Crossmap builds separate indexes for Japanese, English, Korean, Portuguese, and Indonesian. Each index uses its corresponding analyzer:

| Language   | Analyzer             |
|------------|----------------------|
| Japanese   | `JapaneseAnalyzer`   |
| English    | `EnglishAnalyzer`    |
| Korean     | `KoreanAnalyzer`     |
| Portuguese | `PortugueseAnalyzer` |
| Indonesian | `IndonesianAnalyzer` |

Query-language detection happens independently from the browser or app display language. The selected query language determines which index and analyzer execute the search.

## Indexed name fields

Each localized church name is indexed in several forms:

- `name_exact`: an unanalyzed whole-name value for tier 1.
- `name`: analyzed searchable name text.
- `name_ja`, `name_en`, `name_ko`, `name_pt`, or `name_id`: language-specific analyzed name text.

The exact-name normalization currently:

1. trims leading and trailing whitespace;
2. converts letters to lowercase;
3. collapses consecutive whitespace to one space.

It does not currently remove punctuation or perform approximate matching. Therefore an exact match means equality after only those normalization steps.

Changing exact-name, compact-search, or address-entity indexing requires incrementing `ChurchIndex.SCHEMA_VERSION` and rebuilding every language index. The current schema version is 9.

## Tier 1: exact whole-name matching

The complete user query is normalized and looked up as one `TermQuery` against `name_exact`.

Example:

```text
Query:     Tokyo Baptist Church
Candidate: Tokyo Baptist Church
Result:    exact match
```

These are not exact matches:

```text
Tokyo First Baptist Church
Tokyo Baptist Church: TBC@Misato
Shibuya Baptist Church
```

Normally the exact tier is not constrained by a named geoname embedded in a longer church query, so a complete church name remains authoritative. Device location always filters this tier. An explicit administrative name or geoname-only query also applies its exact address-entity filter to tiers 1 and 2. This prevents Kuromoji from splitting `横浜町` into `横浜` + `町` and returning churches in Kanagawa's much larger Yokohama City.

## Tier 2: all analyzed name tokens

The language analyzer decomposes the complete query into tokens. Every token must occur in the candidate's analyzed church-name fields, but additional candidate tokens are allowed.

Japanese example:

```text
Query: 東京バプテスト教会
Tokens: 東京 / バプテスト / 教会
```

Both names contain every query token:

```text
東京バプテスト教会
東京第一バプテスト教会
```

The exact name belongs to tier 1, so `東京第一バプテスト教会` is considered in tier 2.

Generic church terms alone and geoname-only queries do not activate this unfiltered tier. For example, `教会`, `Church`, `교회`, `Igreja`, `Gereja`, and an ambiguous `府中市` proceed to the general/geo tier. This preserves browser/app filtering for broad searches and ensures coordinates select only one same-named municipality.

Like tier 1, tier 2 is normally not filtered by a named geoname embedded in a longer church query. Its purpose is to recognize a complete church name even if that name happens to include geoname text. An explicit administrative name, a geoname-only query, and device-location fallback filter this tier too.

## Geoname resolution

`GeoNameResolver` examines the query using names and translations for the active query language. It prefers longer matches and narrows geographic intent to at most one address entity.

- A bare name shared by a prefecture and its capital city, such as `福岡` or `Fukuoka`, means the city.
- An explicit suffix such as `福岡県`, `Fukuoka Prefecture`, or `Fukuoka-ken` means the prefecture.
- Japanese administrative suffixes remain explicit when romanized, attached, hyphenated, or space-separated. Supported forms include `ken`, `to`, `do/dō`, `fu`, `shi`, `ku`, `cho/chō/chou`, `machi`, `mura`, `son`, and `gun`. For example, both `Yokohama-cho` and `Yokohamacho` select Aomori's `横浜町` and remove the complete suffixed form from the text remainder.
- A unique prefecture, municipality, or ward selects that entity directly.
- When multiple municipalities share a name, `detectIntendedGeonameFromUserLocation` selects the center nearest the browser/app coordinates. Without coordinates the resolver leaves the location ambiguous and the search remains text-only until the client can retry with location.
- Country-wide names (`日本`, `Japan`, `일본`, `Japão`, and `Jepang`) are never resolved as geo filters. Crossmap already searches a Japan-only catalog, and this prevents denomination names such as `日本基督教団` and `日本バプテスト連盟` from being misread as locations.

For this query:

```text
Tokyo Baptist Church
```

the English resolver recognizes `Tokyo` as the English translation of `東京都`. The resolved location includes:

- the matched query text, such as `Tokyo`;
- the canonical Japanese name, such as `東京都`;
- its type, such as `PREFECTURE`;
- representative center coordinates, normally the prefectural or municipal government office;
- the canonical code and prefecture code.

The resolver also calculates a remainder such as `Baptist Church`. Tier 3 analyzes that remainder while the exact entity code preserves the removed geographic intent. The public search response's `textQuery` still contains the complete original query.

## Tier 3: address-entity and remainder search

The exact and all-name-token tiers use the entire original query. In the geo tier, the recognized geoname is represented losslessly by the exact address-entity code filter, while the remaining distinctive words form the scored text query. Generic church words (`教会`, `church`, `교회`, `igreja`, `gereja`, and equivalents) are removed from this tier because they occur in nearly every document and made Lucene traverse a large posting list without adding useful discrimination. Thus `Tokyo Baptist Church` becomes exact Tokyo code + `baptist`; it never becomes an unrestricted `Baptist Church` search or the former union of municipality clauses.

The exception is a geoname-only query such as `Tokyo` or `東京`. Its text branch is `MatchAllDocsQuery`; the single exact address-entity filter supplies the complete intent, so every church in that city/prefecture can be returned even when its name does not contain the geoname.

For example:

```text
Tokyo Baptist Church
```

is analyzed as approximately:

```text
tokyo / baptist / church
```

Every remaining distinctive token is required in the schema-9 `search_compact` field. That field contains current-language names, translated title/address geonames, and translated denomination names; Japanese additionally contains category and full address. Website and social text are a separate conditional fallback.

| Field            | Boost | Notes                                                                                                     |
|------------------|------:|-----------------------------------------------------------------------------------------------------------|
| `search_compact` |     1 | Fast combined name, translated geoname, denomination, and Japanese category/address field.               |
| `content`        |     1 | Japanese crawled-page fallback, executed only if tiers 1–3 cannot fill the requested page.               |
| `social`         |     1 | Japanese social-metadata fallback under the same condition.                                               |

This allows a church such as `Shibuya Baptist Church` to match as follows:

```text
Tokyo code -> exact address_geoname_code filter
baptist    -> search_compact
church     -> removed as a non-discriminating generic token
```

Text matching is not sufficient. The church's normalized address must also contain the selected canonical address-entity code.

An Osaka church fails the Tokyo geo stage even if its name contains `Baptist Church`, because its normalized address does not contain Tokyo's canonical address code. Coordinates and circles are not involved.

## Exact normalized-address filtering

Every document stores exact tags produced by `JapaneseAddressNormalizer`: prefecture/code, county, municipality/code, designated-city ward/code, Kyoto street expression, locality, address number, building, and a repeated `address_geoname_code` field. A named geoname creates exactly one `TermQuery` filter against that code. There is no prefecture expansion, distance-circle union, or language-specific address guard.

The crawl `normalize-addresses` stage first uses the locally cloned Geolonia normalizer and records its level (0/1/2/3/8), then enriches the result with Crossmap's typed decomposition and geoname codes. The canonical catalog combines GeoNames and JMA municipality data, including designated-city wards such as Tokyo `中央区` (`131024`) and Fukuoka `中央区` (`401331`). The normalization cache records a geoname-catalog SHA-256; when only that catalog changes, cached Geolonia output is re-enriched locally rather than invoking Node again. The deterministic Kotlin normalizer remains the index-build fallback when a cached Geolonia result is absent or failed.

The exact and all-name tiers already reward a church whose name contains the place. Tier 3 therefore does not add a redundant optional geoname-name scorer; every document in that tier already has the selected exact entity code.

## Device-location fallback

When the query contains no recognized geoname, the browser or app may supply the user's coordinates.

The engine then creates a synthetic `DEVICE` location with a default radius of 50 km unless the request specifies another radius. This is the only case that uses `LatLonPoint.newDistanceQuery`; named geonames never use radius filtering. The distance query is applied to exact-name, all-name-token, general, and content-fallback tiers, so a high-boost nationwide match cannot bypass the device radius.

Device-location results are collected with `LatLonDocValuesField.newDistanceSort`, making the nearest matching church first. Relevance still decides whether a document matches; distance orders the matching documents. The engine finds the closest municipality/ward government-office point only to label the response (for example `伊豆市`), not to define the radius or an administrative boundary.

Broad generic name queries skip tier 2 so that a query such as `Church` or `教会` does not bypass the device-location filter.

If neither a query geoname nor device coordinates are available, tier 3 becomes a normal text search without a coordinate filter.

## Result merging and pagination

The three tier queries are `SHOULD` branches of one Boolean query with at least one required match. This has several useful properties:

- one church appears only once even when it matches all three tiers;
- exact totals come from Lucene;
- offset and limit apply to the final merged order;
- title-language filters apply inside every tier;
- named-geoname searches retain tier boosts and relevance ordering;
- device-location fallback orders the filtered matches by distance rather than score.

For device fallback, the reported distance is calculated from the user's coordinates to the church. Named administrative searches may report distance from the representative government-office point, but this display value is never used as an inside/outside boundary.

## Complete Tokyo Baptist example

Given:

```text
Tokyo Baptist Church
```

the pipeline is:

```text
1. Detect English query language.
2. Open the English index.
3. Resolve Tokyo to one intended address entity and code.
4. Run exact whole-name query.
5. Run all-name-token query using Tokyo + Baptist + Church.
6. Run the compact remainder search using Baptist; omit the generic word Church.
7. Apply one exact `address_geoname_code` filter to tier 3.
8. Merge the tiers with exact and all-token boosts.
9. Deduplicate, paginate, decode ChurchRecord JSON, and calculate distance.
```

Expected ranking shape:

```text
First tier:  Tokyo Baptist Church
Second tier: Tokyo First Baptist Church and other names containing all tokens
Third tier:  churches with the Tokyo address code whose compact metadata contains Baptist
```

## Query-plan logging

Every search writes an INFO-level `search-query-plan` entry before it executes. The plan is intended for search-quality monitoring and reports:

- the original term, detected language, selected analyzer, pagination, and optional title-language filter;
- analyzed tokens and the AND operator used to combine them;
- candidate geonames, the selection reason, and the one selected feature type/code;
- the exact-name term, all-token tier status, searchable fields, and boosts;
- the tier-3 exact-address or device-radius filter and final merge/deduplication rule.

For example, `Tokyo Baptist Church` produces a plan shaped like:

```text
search-query-plan:
  input.original=Tokyo Baptist Church
  input.language=en analyzer=EnglishAnalyzer
  analysis.tokens=[tokyo, baptist, church] operator=AND
  analysis.geonameRemainder=baptist church
  analysis.locations=[tokyo -> 東京都(PREFECTURE, code=13, representativeCenter=35.6897,139.6930)]
  tier.1.type=EXACT_NAME boost=1000000.0 field=name_exact term=tokyo baptist church geoFilter=false
  tier.2.type=ALL_NAME_TOKENS boost=1000.0 enabled=true tokens=[tokyo, baptist, church] fields=[name^8.0] geoFilter=false
  analysis.explicitAdministrativeName=false
  tier.3.type=ADDRESS_ENTITY_REMAINDER boost=normal tokens=[baptist] fields=[search_compact^1.0]
  tier.3.geoFilter=true filter=EXACT_ADDRESS_ENTITY field=address_geoname_code code=13
  merge=SHOULD(tier.1,tier.2,tier.3) minimumShouldMatch=1 deduplicate=true
```

The CLI and server route logs to standard error, so CLI `--json` output on standard output remains valid JSON. Android and iOS use their platform logging sink.

INFO deliberately shows the compact semantic plan rather than the potentially large Lucene query tree. Set `jp.co.crossmap.ChurchSearchEngine` to TRACE in the applicable Logback configuration when the exact generated Lucene queries are needed; the `search-query-lucene` entry then contains all three tier queries and the merged Boolean query.

### Search timing logs

Every successful search emits two monotonic timing reports. Durations use `TimeSource.Monotonic`, and every line includes its percentage of the relevant total.

`search-timing` covers the shared engine:

- request validation and logging;
- geoname resolution and single-entity selection;
- query-plan rendering, tier construction, and tier merging;
- acquisition of the warmed, long-lived index searcher;
- `lucene.collect.fastTiers`, which executes the merged query once and collects/counts the requested page;
- stored-record decoding, matched-page generation, and response construction.

`search-http-timing` covers the Ktor request from route entry through `call.respond`: reading the query, language detection, engine selection, option parsing, shared-engine execution, and response serialization/sending. The normal `search-response` line also includes the end-to-end duration.

`other` is the small amount of uninstrumented control-flow overhead. The interactive acceptance target is at most 1 second end to end, preferably at most 0.5 second. The server warms one long-lived reader/searcher per language at startup. After replacing the former 53-area Tokyo filter, consolidating search fields, collecting tiers once, and dropping generic tier-3 terms, the real Ktor + schema-9 snapshot + JSON path measured 449 ms, 420 ms, and 381 ms for three warm `東京バプテスト教会` requests on 2026-07-18. `LightPandaSearchE2ETest` fails if any warm sample reaches one second.

## Current limitations and improvement candidates

These are useful areas to evaluate when improving search quality:

1. **Exact normalization**: consider NFKC, punctuation rules, apostrophe variants, and reviewed aliases without weakening "no more, no less" semantics.
2. **Fixed tier boosts**: the current large numeric boosts are simple and effective, but explicit staged collection could express the tier contract without relying on score ranges.
3. **Within-tier ordering**: all-token candidates are ordered by Lucene relevance, not edit distance or the number and position of extra tokens. A closer-name reranker could improve this.
4. **Exact address coverage**: monitor normalization level/error reports and geoname-code coverage; missing codes make the named-location tier intentionally conservative.
5. **Geo ambiguity**: continue testing same-name municipalities and clients that deny coordinates. Explicit administrative names constrain every tier; bare place-like text preserves exact/all-token church-name lookup.
6. **Translated address coverage**: non-Japanese indexes depend on translated geonames extracted from Japanese titles and addresses. Missing translations reduce recall.
7. **Address translations**: exact entity codes are language-neutral, while translated address terms still affect text recall.
8. **Device-location policy**: every tier is constrained by the device radius and matching results are distance-sorted. Named administrative entities remain exact address-code filters, never radius filters.
9. **Distance meaning**: prefecture distance is measured from its configured center, not from the nearest municipality center or boundary.
10. **Snapshot refresh**: each immutable engine keeps a long-lived reader. A server snapshot swap must construct/warm the replacement before closing the old engine.
11. **Evaluation corpus**: continue adding real queries, expected ranking tiers, no-result cases, multilingual names, ambiguous geonames, and pagination assertions.

Any ranking change should be tested at minimum in `ChurchSearchEngineTest`, the `cm` Clikt scenarios, Ktor tests, and the Lightpanda browser flow. Index-field changes require a schema bump and a complete snapshot rebuild.
