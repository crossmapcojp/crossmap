# Codex Implementation Prompt: Add Simplified and Traditional Chinese Support to Crossmap

## Objective

Implement first-class Simplified Chinese and Traditional Chinese support in the Crossmap project.

The implementation must cover:

- Rule-based localization of Japanese church names into Simplified Chinese and Traditional Chinese
- Storage of both Chinese display-name variants in Neo4j
- Static HTML generation for both Chinese locales
- Lucene KMP search indexing
- Search queries entered in either Simplified or Traditional Chinese
- Locale-aware result ranking and display
- Preservation of the original Japanese church name
- Provenance, confidence, and review metadata for generated names

The supported locale identifiers must be:

- `zh-Hans` for Simplified Chinese
- `zh-Hant` for Traditional Chinese

Do not treat Traditional Chinese as merely a UI-time conversion of Simplified Chinese. Both must be modeled as first-class display locales.

For search normalization, use a canonical Chinese search representation, preferably Simplified Chinese, while retaining script-specific fields for exact matching, display, debugging, and ranking.

---

## Important Existing Context

Crossmap already has a rule-based church-name localization pipeline.

During ingestion and conversion of church data into Neo4j, Japanese church names are localized into supported languages using dictionaries and programmatic rules.

Existing dictionary categories include concepts such as:

- Geographic names
- Denomination names
- Christian terminology
- Church-name terms
- Common concepts such as `恵み`, which can become `Grace` in English or an appropriate equivalent in Korean
- More than 7,000 geographic place-name entries

The existing pipeline currently supports several languages, including Japanese, English, Korean, Portuguese, and Indonesian or other configured locales.

The Chinese implementation must extend this existing architecture rather than introducing a separate, disconnected localization system.

---

# Working Rules

1. Inspect the repository before modifying code.
2. Reuse existing abstractions, schemas, naming conventions, and pipeline stages wherever reasonable.
3. Do not perform a broad rewrite unless the current design makes the requested implementation impossible.
4. Keep the implementation incremental and reviewable.
5. Do not silently overwrite official or manually reviewed names.
6. Preserve backward compatibility for existing locales and indexed data.
7. Clearly distinguish:
   - official names
   - generated localized names
   - aliases
   - canonical search forms
8. Avoid generating URLs or persistent identifiers from translated Chinese display names.
9. Add tests before or alongside implementation.
10. Run the relevant test suite after each major implementation stage.

---

# Phase 1: Repository Inspection

Inspect the repository and identify the following:

- Church domain models
- Neo4j node and relationship models
- Existing localized-name structures
- Locale enums, constants, or language-code definitions
- Dictionary schemas
- Dictionary loading code
- Japanese church-name parsing and localization code
- Geographic-name dictionaries
- Denomination dictionaries
- Christian terminology dictionaries
- Data-ingestion pipeline
- Google saved-place conversion pipeline
- Static HTML generation
- Locale-specific routing
- Lucene KMP document construction
- Lucene analyzers currently used
- Query parsing and query normalization
- Search-result ranking and boosting
- Existing localization tests
- Existing golden-data or fixture mechanisms

Before editing code, produce a concise implementation map containing:

- relevant modules
- relevant classes and functions
- current localization flow
- current indexing flow
- proposed modification points
- migration or compatibility risks

Do not begin major implementation until this inspection is complete.

---

# Phase 2: Define the Chinese Data Model

Add `zh-Hans` and `zh-Hant` as supported locales using the project's existing locale representation.

Use standards-compliant locale identifiers. Do not invent project-specific identifiers such as `cn`, `tw`, or `traditionalChinese`.

The church-name model should be able to represent at least:

```text
officialName
officialNameLocale
nameJa
nameZhHans
nameZhHant
aliasesZhHans
aliasesZhHant
searchCanonicalZh
```

Adapt these names to the repository's existing conventions.

Do not duplicate fields unnecessarily if the project already uses a localized map, value object, child node, or localization entity.

Each generated localized name should support provenance metadata such as:

```text
source
generationMethod
dictionaryVersion
confidence
reviewStatus
generatedAt
```

Recommended semantic values:

```text
source:
- OFFICIAL
- MANUAL
- GENERATED
- IMPORTED

generationMethod:
- EXACT_OVERRIDE
- DENOMINATION_DICTIONARY
- GEO_DICTIONARY
- CONCEPT_DICTIONARY
- TOKEN_RULE
- SCRIPT_CONVERSION
- ORIGINAL_FALLBACK

reviewStatus:
- REVIEWED
- NEEDS_REVIEW
- UNREVIEWED
- REJECTED
```

Use enums or sealed types where the current architecture supports them.

The system must preserve a manually reviewed Chinese name when the localization pipeline runs again.

---

# Phase 3: Extend the Dictionary Format

Extend the existing dictionary system to support:

```text
zh-Hans
zh-Hant
```

Do not create isolated Chinese-only dictionaries unless the current system requires language-specific files.

Prefer extending existing multilingual dictionary entries.

A conceptual entry may resemble:

```yaml
source:
  ja: 恵み
targets:
  en: Grace
  ko: 은혜
  pt: Graça
  zh-Hans: 恩典
  zh-Hant: 恩典
category: christian_concept
```

Another example:

```yaml
source:
  ja: バプテスト
targets:
  en: Baptist
  ko: 침례교
  pt: Batista
  zh-Hans: 浸信会
  zh-Hant: 浸信會
category: denomination
```

Use terminology appropriate to Chinese-speaking Christian communities.

Do not assume that Simplified and Traditional Chinese always differ only by character conversion. Some Christian terminology, denomination names, proper names, and regional conventions may differ lexically.

Support dictionary priority.

Recommended precedence:

1. Exact church-name override
2. Official denomination translation
3. Official organization translation
4. Exact geographic-name translation
5. Christian concept dictionary
6. Generic church-name token dictionary
7. Script conversion fallback
8. Original Japanese fallback

Document and encode this precedence explicitly.

---

# Phase 4: Chinese Name Generation Pipeline

Extend the existing Japanese-to-localized-name pipeline to generate both:

```text
zh-Hans
zh-Hant
```

The pipeline should:

1. Normalize the Japanese source name without destroying meaningful orthography.
2. Detect exact church-name overrides.
3. Segment or tokenize the church name using the existing parsing logic.
4. Resolve denomination terms.
5. Resolve geographic names.
6. Resolve Christian concepts.
7. Resolve generic church-name terms.
8. Preserve unrecognized proper nouns.
9. Generate `zh-Hans`.
10. Generate `zh-Hant`.
11. Record provenance for each generated result.
12. Calculate or assign confidence.
13. Flag uncertain output for review.

Do not blindly convert the entire Japanese Kanji string into Chinese.

Japanese church names may contain:

- Japanese-specific Kanji usage
- Kana
- abbreviated denomination names
- English words
- Latin-script organization names
- mixed Japanese and English
- historical place names
- personal names
- uncommon Christian terminology
- branding with unusual punctuation

The implementation must preserve unknown segments rather than inventing translations.

---

# Phase 5: Simplified and Traditional Chinese Strategy

Use both Chinese variants as first-class stored display values.

Recommended generation strategy:

```text
Japanese source
    |
    +--> rule-based zh-Hans generation
    |
    +--> rule-based zh-Hant generation
```

Where dictionary coverage exists, generate each variant directly from locale-specific dictionary terms.

Only use script conversion as a fallback.

Do not make the primary architecture:

```text
Japanese -> Simplified Chinese -> blind conversion -> Traditional Chinese
```

However, script conversion may be used when:

- the term is known to be lexically identical apart from script
- no locale-specific override exists
- the output is marked as fallback-generated
- the result can be reviewed later

Create an abstraction for Chinese script conversion so the conversion library can be changed without affecting the rest of the localization pipeline.

If the repository already has a transliteration or normalization abstraction, integrate with it.

---

# Phase 6: Neo4j Persistence

Update Neo4j persistence so that both Chinese localized names are stored.

The implementation should support retrieval by locale without requiring runtime translation.

Store:

- Simplified Chinese display name
- Traditional Chinese display name
- Simplified Chinese aliases
- Traditional Chinese aliases
- generation provenance
- confidence
- review status

Preserve the original Japanese name.

Do not replace the official church name with a generated Chinese name.

If localized names are represented as related nodes, preserve the existing graph design.

If localized names are stored as properties, use the project's established property conventions.

Add any required schema constraints or indexes.

Create a migration or reprocessing path for existing church records.

The migration must be idempotent.

Running it repeatedly must not:

- create duplicate localized-name nodes
- duplicate aliases
- overwrite manual corrections
- lower a reviewed value to an unreviewed generated value

---

# Phase 7: Static Site and HTML Generation

Add static-site support for:

```text
zh-Hans
zh-Hant
```

Use the browser language only as an initial locale suggestion.

Users must be able to switch languages explicitly.

Recommended browser-language mapping:

```text
zh-CN -> zh-Hans
zh-SG -> zh-Hans
zh-Hans -> zh-Hans

zh-TW -> zh-Hant
zh-HK -> zh-Hant
zh-MO -> zh-Hant
zh-Hant -> zh-Hant
```

For ambiguous `zh`, define and document a default. Prefer either:

- project-configurable behavior, or
- `zh-Hans` as the technical default while still exposing an immediate language switch

Do not permanently bind the user to the browser-detected language.

Persist an explicit user selection using the project's existing preference mechanism, cookie, URL locale, or local storage strategy.

Each church result should display:

1. the localized name for the selected locale
2. the original Japanese name as secondary text

Example:

```text
东京恩典浸信会
東京恵みバプテスト教会
```

or:

```text
東京恩典浸信會
東京恵みバプテスト教会
```

Label generated names appropriately in internal metadata. A visible "automatic translation" label is optional and should follow the product's UX policy.

Do not use localized names as canonical resource identifiers.

Keep stable URLs based on existing IDs, slugs, Place IDs, database IDs, or another locale-independent identifier.

Update:

- locale switcher
- HTML `lang` attributes
- alternate-language links
- canonical links
- metadata
- structured data
- Open Graph fields where appropriate
- sitemap generation
- static build output paths

Use correct HTML language tags:

```html
<html lang="zh-Hans">
<html lang="zh-Hant">
```

---

# Phase 8: Lucene KMP Index Design

Implement a Chinese indexing strategy that supports queries written in either Chinese script.

Do not use only one stored Chinese field if doing so prevents exact script matching, correct display, or ranking.

Recommended fields:

```text
name_zh_hans
name_zh_hant
aliases_zh_hans
aliases_zh_hant
name_zh_canonical
aliases_zh_canonical
name_ja
name_original
```

Adapt field names to existing index conventions.

## Canonical Search Field

Create a canonical Chinese field normalized to Simplified Chinese:

```text
name_zh_canonical
```

Both Simplified and Traditional Chinese names should contribute normalized tokens to this field.

At query time:

- Simplified Chinese input may be used directly after standard normalization.
- Traditional Chinese input should also be converted to the canonical Simplified representation.
- The original query script must still be retained for exact-field matching.

## Script-Specific Fields

Retain:

```text
name_zh_hans
name_zh_hant
```

These fields are required for:

- exact phrase matching
- locale-specific boosting
- debugging
- highlighting
- future analyzer improvements
- avoiding loss of original localized text

## Stored Fields

Store the display values needed to render search results, unless display data is always loaded from Neo4j or another result store.

Follow the existing architecture for stored fields versus database hydration.

---

# Phase 9: Analyzer Strategy

Inspect Lucene KMP's currently available analyzers before selecting one.

The existing discussion considered SmartChineseAnalyzer for the canonical Simplified Chinese field.

Evaluate whether SmartChineseAnalyzer is available and reliable in the project's Lucene KMP implementation.

If SmartChineseAnalyzer is used:

- use it for canonical Simplified Chinese search fields
- normalize Traditional Chinese input before analysis
- do not assume it fully solves proper-name matching
- preserve keyword or exact-match subfields
- test mixed Chinese, Japanese, Latin, and numeric input

Recommended multi-field approach:

```text
name_zh_hans_exact      -> KeywordAnalyzer or equivalent
name_zh_hant_exact      -> KeywordAnalyzer or equivalent
name_zh_canonical       -> SmartChineseAnalyzer
aliases_zh_canonical    -> SmartChineseAnalyzer
```

Consider an additional character-level fallback field if SmartChineseAnalyzer tokenization performs poorly for short church names.

Possible fallback strategies include:

- CJK bigrams
- character n-grams
- edge n-grams for prefix behavior
- keyword normalization fields
- exact alias fields

Do not add all fallback strategies automatically. Benchmark them against realistic data and select the smallest useful set.

Document analyzer tradeoffs.

---

# Phase 10: Query Processing

Update query processing to:

1. Detect the selected UI locale.
2. Preserve the original query text.
3. Detect whether the query contains Traditional Chinese characters when feasible.
4. Generate a canonical Simplified Chinese query variant.
5. Search script-specific fields.
6. Search canonical Chinese fields.
7. Search Japanese names and aliases where appropriate.
8. Apply locale-aware boosting.
9. Avoid duplicate result entries.

A query should be able to match when:

- a Simplified Chinese query searches a Traditional Chinese localized name
- a Traditional Chinese query searches a Simplified Chinese localized name
- a Chinese query searches a Japanese official church name through generated aliases
- the church name contains mixed Japanese, Chinese, and Latin characters
- the denomination term differs by locale
- the place name differs between Japanese and Chinese usage

Preserve query syntax supported by the current system.

Do not apply script conversion to:

- operators
- field names
- quoted syntax markers
- wildcards
- escaped characters

Normalize only the text portions that should be analyzed.

---

# Phase 11: Ranking and Boosting

Implement explicit ranking rules.

Recommended ranking priority:

1. Exact name match in the user's selected script
2. Exact alias match in the user's selected script
3. Exact match in the other Chinese script
4. Canonical analyzed Chinese name match
5. Canonical alias match
6. Japanese official-name match
7. Partial or fuzzy fallback match

Example conceptual boosts:

```text
exact selected-script name: 10.0
exact selected-script alias: 8.0
exact other-script name: 6.0
canonical analyzed name: 4.0
canonical alias: 3.0
Japanese official name: 2.0
partial fallback: 1.0
```

Do not use these numbers blindly. Adapt them to the current scoring model and add tests that prove the intended ordering.

Consider secondary ranking signals already used by Crossmap, such as:

- geographic distance
- exact location match
- denomination match
- official or reviewed name status
- confidence of generated localization
- church data completeness

A low-confidence generated alias should not outrank an exact official-name match.

---

# Phase 12: Display Behavior

Search matching and result display must be separate concerns.

The selected UI locale determines the preferred display field:

```text
zh-Hans UI -> nameZhHans
zh-Hant UI -> nameZhHant
```

Fallback order for display:

For `zh-Hans`:

1. reviewed Simplified Chinese name
2. official Simplified Chinese name
3. generated Simplified Chinese name
4. reviewed Traditional Chinese name
5. generated Traditional Chinese name
6. Japanese official name
7. another existing official name

For `zh-Hant`:

1. reviewed Traditional Chinese name
2. official Traditional Chinese name
3. generated Traditional Chinese name
4. reviewed Simplified Chinese name
5. generated Simplified Chinese name
6. Japanese official name
7. another existing official name

Always make the original Japanese name accessible.

Do not dynamically convert the displayed official name at request time when a stored reviewed localized name exists.

---

# Phase 13: Confidence and Review Workflow

Add a review-report mechanism.

Names should be flagged for review when:

- only script-conversion fallback was used
- one or more source tokens were not recognized
- multiple dictionary translations conflict
- a personal name may have been translated as a common noun
- a geographic name has multiple possible Chinese forms
- a denomination lacks an official Chinese name
- Simplified and Traditional generation paths produce inconsistent terminology
- the output contains an unexpected mixture of scripts
- the localized name is identical to the Japanese source despite translatable terms
- punctuation or spacing becomes malformed

Generate a machine-readable report, preferably JSON, CSV, or the project's existing report format.

Recommended fields:

```text
churchId
officialNameJa
generatedZhHans
generatedZhHant
confidence
reviewReasons
matchedDictionaryEntries
unmatchedSegments
generationMethods
```

Also generate a human-readable summary.

Do not fail the entire ingestion pipeline because one name requires review.

---

# Phase 14: Dictionary and Terminology Validation

Add validation checks for Chinese dictionary data.

Validate:

- duplicate source entries
- conflicting locale targets
- empty `zh-Hans` values
- empty `zh-Hant` values
- accidental use of Japanese Shinjitai where Chinese terminology is expected
- inconsistent denomination terminology
- malformed locale identifiers
- cyclic aliases
- leading or trailing whitespace
- duplicate aliases after normalization
- Simplified and Traditional values that are unexpectedly identical
- Simplified and Traditional values that differ unexpectedly

Identical Simplified and Traditional values are not always wrong, so treat this as a review signal, not necessarily an error.

---

# Phase 15: Testing

Add unit, integration, and golden tests.

## Unit Tests

Test:

- locale parsing
- locale fallback
- dictionary lookup
- dictionary priority
- Simplified generation
- Traditional generation
- script conversion fallback
- confidence assignment
- review flag generation
- preservation of manual values
- idempotent reprocessing
- canonical search normalization

## Analyzer Tests

Test queries and fields containing:

- Simplified Chinese
- Traditional Chinese
- Japanese Kanji
- Hiragana
- Katakana
- Latin text
- numbers
- punctuation
- mixed-script church names
- short names
- denomination abbreviations

## Search Tests

At minimum, prove:

- Simplified query matches Simplified result
- Traditional query matches Traditional result
- Simplified query matches Traditional result through canonical normalization
- Traditional query matches Simplified result through canonical normalization
- exact selected-script match ranks first
- exact official Japanese match is not hidden
- low-confidence generated alias does not outrank a reviewed exact match
- locale changes display text without changing result identity
- duplicate aliases do not create duplicate results

## Static Site Tests

Test:

- `zh-Hans` page generation
- `zh-Hant` page generation
- correct `<html lang>`
- locale switch links
- stable canonical URL
- localized metadata
- fallback to Japanese
- browser-language mapping
- explicit user preference overriding browser language

---

# Phase 16: Golden Church-Name Fixtures

Create a golden fixture set using representative real or anonymized church-name patterns.

Include examples containing:

- geographic name plus denomination
- geographic name plus Christian concept
- Japanese Kanji-only names
- Kana-only names
- mixed Kanji and Kana
- English brand name plus `教会`
- Catholic terminology
- Baptist terminology
- Lutheran terminology
- Pentecostal terminology
- independent evangelical terminology
- Korean-origin church names in Japan
- Chinese-origin church names in Japan
- personal names
- historical Japanese place names
- names containing `恵み`
- names containing `福音`
- names containing `聖書`
- names containing `キリスト`
- names containing `教会`
- names containing `チャペル`
- names containing `センター`

Each fixture should contain:

```text
source Japanese name
expected zh-Hans
expected zh-Hant
expected canonical form
expected confidence
expected review status
expected matched dictionary rules
```

Where a single authoritative translation cannot be determined, mark the fixture as requiring review instead of inventing certainty.

---

# Phase 17: Migration and Reindexing

Implement a safe migration plan.

The migration should:

1. Add locale support.
2. Load new dictionaries.
3. Generate missing Chinese localized names.
4. Preserve manual and reviewed names.
5. Write provenance.
6. Produce the review report.
7. Rebuild or incrementally update the Lucene KMP index.
8. Validate document counts.
9. Validate that no churches disappear.
10. Validate that result identities remain stable.

Provide commands or Gradle tasks for:

```text
generate Chinese localized names
validate Chinese dictionaries
produce review report
reindex Chinese fields
run Chinese golden tests
```

Use existing task conventions where available.

Support dry-run mode before mutating production data.

Dry-run output should include:

- number of churches processed
- number of `zh-Hans` names generated
- number of `zh-Hant` names generated
- number preserved because of manual review
- number requiring review
- number of indexing changes
- dictionary coverage rate
- unmatched-token frequency

---

# Phase 18: Observability

Add useful structured logging.

Log:

- localization locale
- dictionary version
- generation method
- confidence
- review status
- fallback usage
- unmatched segments
- indexing field counts
- reindex duration
- document-count differences

Do not log sensitive user search data unless the current privacy policy permits it.

Avoid noisy per-token logs in normal production mode.

Use debug-level logging for detailed localization traces.

---

# Phase 19: Documentation

Update project documentation.

Document:

- why both `zh-Hans` and `zh-Hant` are stored
- why canonical search normalization uses Simplified Chinese
- why display names are not generated only at request time
- dictionary precedence
- fallback behavior
- review workflow
- locale detection
- locale switching
- analyzer selection
- ranking rules
- migration commands
- adding new dictionary entries
- correcting an incorrect generated name
- preserving official Chinese church names
- reindexing after dictionary changes

Include a concise architecture diagram in Markdown.

Example:

```text
Japanese official name
        |
        v
Rule-based localization pipeline
        |
        +--------------------+
        |                    |
        v                    v
     zh-Hans              zh-Hant
        |                    |
        +---------+----------+
                  |
                  v
       canonical Simplified form
                  |
                  v
           Lucene KMP index
```

---

# Phase 20: Implementation Sequence

Follow this sequence unless repository constraints require a justified adjustment:

1. Inspect repository.
2. Write implementation map.
3. Add locale constants.
4. Add data-model support.
5. Extend dictionary schema.
6. Add dictionary validation.
7. Add Chinese localization pipeline.
8. Add provenance and confidence.
9. Add Neo4j persistence.
10. Add migration and dry-run support.
11. Add static-site locale support.
12. Add Lucene fields.
13. Add canonical normalization.
14. Add query expansion.
15. Add ranking boosts.
16. Add display fallback.
17. Add review reporting.
18. Add unit tests.
19. Add integration tests.
20. Add golden fixtures.
21. Run migration on test data.
22. Rebuild test index.
23. Run all relevant tests.
24. Document changes.

Commit changes in small logical groups if the environment permits commits.

---

# Required Deliverables

At the end, report:

## Repository Analysis

- modules inspected
- current localization architecture
- current indexing architecture
- relevant extension points

## Implementation Summary

- data-model changes
- dictionary changes
- pipeline changes
- Neo4j changes
- static-site changes
- Lucene KMP changes
- query changes
- ranking changes
- review workflow

## Files Changed

List every changed or created file with a short explanation.

## Tests

Report:

- tests added
- tests run
- passed tests
- failed tests
- skipped tests
- reasons for any failures

## Data Quality

Report:

- dictionary coverage
- number of fallback conversions
- number of names requiring review
- common unmatched terms
- known limitations

## Migration

Provide exact commands for:

- dry run
- generation
- validation
- reindexing
- rollback or restoration where applicable

## Follow-up Work

List only concrete unresolved tasks.

Do not claim completion for anything that was not implemented or verified.

---

# Acceptance Criteria

The feature is complete only when all of the following are true:

- `zh-Hans` and `zh-Hant` are first-class supported locales.
- Both Chinese display names can be stored in Neo4j.
- Existing manually reviewed values are preserved.
- Static pages can be generated for both locales.
- Browser-language detection chooses a reasonable default.
- Users can explicitly switch locale.
- Simplified Chinese queries match Traditional Chinese names.
- Traditional Chinese queries match Simplified Chinese names.
- Exact selected-script matches rank above canonical fallback matches.
- Search results display the selected Chinese variant.
- The original Japanese church name remains available.
- Lucene fields preserve script-specific and canonical forms.
- Dictionary fallback behavior is explicit.
- Low-confidence names are reported for review.
- Migration is idempotent.
- Reindexing is reproducible.
- Existing locale behavior remains functional.
- Tests cover representative mixed-script and church-specific cases.
- Documentation explains the architecture and operational workflow.

---

# Begin Work

Start by inspecting the repository.

Do not make assumptions about file names, modules, database schemas, or analyzer availability.

First provide the repository implementation map, then proceed with the smallest coherent implementation steps.
