# Chinese localization architecture and operations

Crossmap stores `zh-Hans` and `zh-Hant` independently. They are display records, not request-time conversions: official and reviewed terminology can differ lexically, must retain provenance, and must not change when conversion tables change. The original Japanese name remains the canonical source and stable IDs never contain a localized name.

```text
Japanese official church / GeoName / minister name
                     |
                     v
          deterministic localization
              /             \
             v               v
         zh-Hans          zh-Hant
             \               /
              v             v
        canonical Simplified search form
                     |
                     v
       script-specific + canonical Lucene fields
```

## Data and precedence

`LocalizedName` carries source, generation method, dictionary version, confidence, review status, reasons, matched rules, and unmatched segments. Church and minister records retain both Chinese variants. GeoNames use exact `zh-Hans` and `zh-Hant` translation keys; reviewed legacy tags such as `zh-TW` are canonicalized while generating the GeoName catalog.

Display precedence is reviewed, official, generated, then the other Chinese script, Japanese, and another official language. Manual, official, and reviewed values survive every migration. Website crawling may add `OFFICIAL/EXACT_OVERRIDE` values from church-owned locale pages, but cannot replace a manual or reviewed value.

Dictionary precedence is:

1. exact church-name override;
2. official denomination or organization name;
3. exact geographic name;
4. Christian concept;
5. generic church-name or congregation token;
6. script-conversion fallback;
7. unchanged Japanese fallback.

Direct paired dictionaries live in `resources/dictionary/ja-zh-Hans-*-dictionary.csv` and `ja-zh-Hant-*-dictionary.csv`. Congregation terminology uses lowercase canonical keys `zh-hans` and `zh-hant`. Add or correct both variants together. An authoritative church-owned value belongs in the church record with `source=OFFICIAL`; a reviewed correction uses `source=MANUAL`, `reviewStatus=REVIEWED`, and `generationMethod=EXACT_OVERRIDE`.

Script conversion is intentionally small and replaceable (`ChineseScriptNormalizer`). It supplies canonical Simplified search text and reviewable fallbacks; it never rewrites a stored official display name.

## Search and display

Lucene schema 13 stores exact and analyzed `name_zh_hans`, `name_zh_hant`, and canonical Simplified fields. `SmartChineseAnalyzer` is used only for analyzed canonical Chinese. Keyword-normalized exact fields protect short proper names and provide ranking in this order: exact selected script, exact other script, canonical exact/analyzed, then Japanese and partial fallbacks. This is smaller and more predictable than adding n-gram fields before evidence shows they are needed.

Static output contains `/zh-Hans/` and `/zh-Hant/`, exact HTML `lang`, canonical URLs, alternates, localized metadata, and an explicit persisted switch. Browser mapping uses Simplified for ambiguous `zh`; an explicit choice always wins. Search matching and display are separate, so cross-script matches render the chosen stored variant and show Japanese secondarily.

## Validation, migration, review, and reindexing

Run the workflow in order:

```sh
./gradlew :crawl:validateChineseDictionaries
./gradlew :crawl:dryRunChineseLocalizedNames
./gradlew :crawl:chineseGoldenTest
./gradlew :crawl:generateChineseLocalizedNames
./gradlew :crawl:reindexChineseFields
```

The validator reports malformed locales, duplicate/unpaired sources, conflicts, whitespace, cycles, normalized duplicate targets, Shinjitai signals, and unexpectedly identical or lexically different script pairs. Identical pairs are review signals, not errors.

The dry run writes:

- `resources/review/chinese-localization-report.json`: per-church generated variants, confidence, reasons, rules, unmatched segments, coverage, and unmatched-token frequency. These verbose diagnostics live only in the review report; `resources/catalog/churches.json` retains compact source/method/confidence/review-status provenance and is serialized without formatting or default-valued fields so the canonical catalog stays below GitHub's large-file warning threshold;
- `resources/review/chinese-localization-summary.txt`: human-readable totals;
- `resources/review/chinese-dictionary-validation.json`: validation errors and review signals.

Review names that use conversion/original fallback, retain Kana, contain unmatched segments, or otherwise have `NEEDS_REVIEW`. Correct dictionaries for reusable terminology and use a manual localized value for a one-church proper name. Re-run the dry run until the change is represented in the report, then apply and reindex.

Generation is deterministic and idempotent: rerunning does not duplicate locales or aliases, overwrite protected names, or lower review status. Reindexing rebuilds the snapshot from the canonical catalog and validates the schema/source checksum. For rollback, restore the prior `resources/catalog/churches.json` (or its normal catalog backup), rerun `reindexChineseFields`, and regenerate static pages. Neo4j import remains a build-time operation; Ktor never translates or queries Neo4j at request time.
