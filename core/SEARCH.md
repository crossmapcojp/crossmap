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
| Geo/full-query   | normal field boosts | Find churches whose complete query matches across name, denomination, and geoname data inside the detected area. |

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

Changing exact-name indexing requires incrementing `ChurchIndex.SCHEMA_VERSION` and rebuilding every language index. The current schema version is 7.

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

The exact tier is intentionally not constrained by detected or device location. A user who provides the complete church name can therefore find that church even when it is outside the default device radius.

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

Generic church terms alone do not activate this unfiltered tier. For example, `教会`, `Church`, `교회`, `Igreja`, and `Gereja` proceed to the general/geo tier. This preserves browser and app location filtering for broad searches.

Like tier 1, tier 2 is not geo-filtered. Its purpose is to recognize a church name even if the name happens to include text that is also a geoname.

## Geoname resolution

`GeoNameResolver` examines the query using names and translations for the active query language. It prefers longer and more specific matches and can resolve more than one location.

For this query:

```text
Tokyo Baptist Church
```

the English resolver recognizes `Tokyo` as the English translation of `東京都`. The resolved location includes:

- the matched query text, such as `Tokyo`;
- the canonical Japanese name, such as `東京都`;
- its type, such as `PREFECTURE`;
- center coordinates;
- a covering radius;
- the canonical code and prefecture code.

The resolver also calculates a remainder such as `Baptist Church`. That remainder is useful for diagnostics, but it is no longer used as the Lucene text query. The search response's `textQuery` contains the complete original query.

## Tier 3: full-query geo search

The geo tier uses the entire original query, including the recognized geoname.

For example:

```text
Tokyo Baptist Church
```

is analyzed as approximately:

```text
tokyo / baptist / church
```

Every token is required, but different tokens may match different fields. The principal fields and boosts are:

| Field                   | Boost | Notes                                                                                 |
|-------------------------|------:|---------------------------------------------------------------------------------------|
| Church name             |     8 | Localized name for the active index.                                                  |
| Language-specific name  |     8 | For example, `name_en`.                                                               |
| Translated geoname      |     6 | Geonames found in the Google title or Japanese address and translated for this index. |
| Translated denomination |     5 | Denomination name in the active language.                                             |
| Japanese category       |     5 | Japanese index only.                                                                  |
| Japanese address        |     3 | Japanese index only.                                                                  |
| Crawled website content |     1 | Japanese index only.                                                                  |
| Social metadata         |     1 | Japanese index only.                                                                  |

This allows a church such as `Shibuya Baptist Church` to match as follows:

```text
tokyo   -> translated address geoname
baptist -> church name
church  -> church name
```

Text matching is not sufficient. The church's latitude and longitude must also pass the Lucene geo filter for Tokyo.

An Osaka church fails the Tokyo geo stage even if its name contains `Baptist Church`, because it does not have the Tokyo geoname and its coordinates are outside the resolved Tokyo area.

## Coordinate filtering

Every `ChurchRecord` has a Lucene `LatLonPoint` and `LatLonDocValuesField`.

For a municipality or ward, the engine creates one `LatLonPoint.newDistanceQuery` using that location's center and covering radius.

For a prefecture, the engine expands the prefecture into its known municipalities and wards and creates a union of their distance queries. A church passes if it is inside at least one area.

The catalog can mark a municipality as excluded from its parent prefecture's implicit search area while leaving the municipality independently searchable. Tokyo uses this for its nine remote island municipalities. Consequently, an ordinary `Tokyo` or `東京都` query uses the mainland Tokyo geometry (currently about a 53.5 km covering radius and 53 municipality/ward areas), while an explicit `小笠原村` query still searches Ogasawara normally. This represents typical user intent and avoids a roughly 995 km Tokyo circle covering most of Japan.

If a query resolves multiple locations, their geo areas are also combined as a union.

### Japanese address guard

The Japanese index adds an address filter for non-device locations. The address must contain the resolved canonical Japanese place name. This reduces false positives near prefecture or municipality borders.

The matched geoname text is also added as an optional boosted name clause, so a church whose name explicitly contains the requested place may rank above another church in the same area.

## Device-location fallback

When the query contains no recognized geoname, the browser or app may supply the user's coordinates.

The engine then creates a synthetic `DEVICE` location with a default radius of 25 km unless the request specifies another radius. The general search tier is constrained by this circle.

Broad generic name queries skip tier 2 so that a query such as `Church` or `教会` does not bypass the device-location filter.

If neither a query geoname nor device coordinates are available, tier 3 becomes a normal text search without a coordinate filter.

## Result merging and pagination

The three tier queries are `SHOULD` branches of one Boolean query with at least one required match. This has several useful properties:

- one church appears only once even when it matches all three tiers;
- exact totals come from Lucene;
- offset and limit apply to the final merged order;
- title-language filters apply inside every tier;
- exact and all-token tier boosts dominate geo-tier scores;
- field relevance scores still order churches inside the same tier.

The reported distance is calculated from the resolved location center to the church. For multiple resolved locations, the smallest calculated distance is returned. The distance display does not currently represent distance to the edge of a prefecture or municipality area.

## Complete Tokyo Baptist example

Given:

```text
Tokyo Baptist Church
```

the pipeline is:

```text
1. Detect English query language.
2. Open the English index.
3. Resolve Tokyo -> 東京都 and its geo areas.
4. Run exact whole-name query.
5. Run all-name-token query using Tokyo + Baptist + Church.
6. Run the full-query field search using Tokyo + Baptist + Church.
7. Apply the Tokyo coordinate filter to tier 3.
8. Merge the tiers with exact and all-token boosts.
9. Deduplicate, paginate, decode ChurchRecord JSON, and calculate distance.
```

Expected ranking shape:

```text
First tier:  Tokyo Baptist Church
Second tier: Tokyo First Baptist Church and other names containing all tokens
Third tier:  churches in the Tokyo area whose name/geoname fields collectively match all tokens
```

## Query-plan logging

Every search writes an INFO-level `search-query-plan` entry before it executes. The plan is intended for search-quality monitoring and reports:

- the original term, detected language, selected analyzer, pagination, and optional title-language filter;
- analyzed tokens and the AND operator used to combine them;
- every resolved geoname, its feature type, code, center, and radius;
- the exact-name term, all-token tier status, searchable fields, and boosts;
- the tier-3 geo areas, Japanese address guard, and final merge/deduplication rule.

For example, `Tokyo Baptist Church` produces a plan shaped like:

```text
search-query-plan:
  input.original=Tokyo Baptist Church
  input.language=en analyzer=EnglishAnalyzer
  analysis.tokens=[tokyo, baptist, church] operator=AND
  analysis.geonameRemainder=baptist church
  analysis.locations=[tokyo -> 東京都(PREFECTURE, code=13, center=35.6895,139.6917, radiusKm=...)]
  tier.1.type=EXACT_NAME boost=1000000.0 field=name_exact term=tokyo baptist church geoFilter=false
  tier.2.type=ALL_NAME_TOKENS boost=1000.0 enabled=true tokens=[tokyo, baptist, church] fields=[name^8.0, name_en^8.0] geoFilter=false
  tier.3.type=FULL_QUERY_GEO boost=normal tokens=[tokyo, baptist, church] fields=[name^8.0, name_en^8.0, geoname^6.0, denomination^5.0]
  tier.3.geoFilter=true areas=東京都 (... area(s))
  merge=SHOULD(tier.1,tier.2,tier.3) minimumShouldMatch=1 deduplicate=true
```

The CLI and server route logs to standard error, so CLI `--json` output on standard output remains valid JSON. Android and iOS use their platform logging sink.

INFO deliberately shows the compact semantic plan rather than the potentially large Lucene query tree. Set `jp.co.crossmap.ChurchSearchEngine` to TRACE in the applicable Logback configuration when the exact generated Lucene queries are needed; the `search-query-lucene` entry then contains all three tier queries and the merged Boolean query.

## Current limitations and improvement candidates

These are useful areas to evaluate when improving search quality:

1. **Exact normalization**: consider NFKC, punctuation rules, apostrophe variants, and reviewed aliases without weakening "no more, no less" semantics.
2. **Fixed tier boosts**: the current large numeric boosts are simple and effective, but explicit staged collection could express the tier contract without relying on score ranges.
3. **Within-tier ordering**: all-token candidates are ordered by Lucene relevance, not edit distance or the number and position of extra tokens. A closer-name reranker could improve this.
4. **Prefecture-query performance**: a prefecture can expand into many distance queries. Benchmark clustering, bounding shapes, cached filters, and other lucene-kmp geo representations.
5. **Geo ambiguity**: a word such as `Tokyo` can be part of an official organization name rather than a user's intended location. Exact and all-token tiers protect name lookup, but the geo intent itself remains implicit.
6. **Translated address coverage**: non-Japanese indexes depend on translated geonames extracted from Japanese titles and addresses. Missing translations reduce recall.
7. **Address guard consistency**: the additional canonical-address filter currently exists only for Japanese.
8. **Device-location policy**: exact and distinctive all-token names intentionally bypass device radius, while generic queries do not. This policy should remain covered by UX tests.
9. **Distance meaning**: prefecture distance is measured from its configured center, not from the nearest municipality center or boundary.
10. **Reader and filter reuse**: the engine currently opens the index for each request. Long-lived readers and cached geo filters may reduce server and device latency.
11. **Evaluation corpus**: continue adding real queries, expected ranking tiers, no-result cases, multilingual names, ambiguous geonames, and pagination assertions.

Any ranking change should be tested at minimum in `ChurchSearchEngineTest`, the `cm` Clikt scenarios, Ktor tests, and the Lightpanda browser flow. Index-field changes require a schema bump and a complete snapshot rebuild.
