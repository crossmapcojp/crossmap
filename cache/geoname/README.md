# GeoNames cache

This directory contains the official Japan dump from
[GeoNames](https://download.geonames.org/export/dump/).

- `japan/JP.txt` is the official headerless, tab-delimited source. `GeoName.ensureOfficialJapanDump` downloads
  and extracts `JP.zip` only when this file is absent. `JP.readme.txt` documents its fields.
- `japan/alternatenames/JP.txt` is the official language-tagged alternate-name source.
  `GeoName.ensureOfficialJapanAlternateNamesDump` downloads and extracts `alternatenames/JP.zip` only when absent.
- `japan/geonames.csv` and `japan/church-name-lexicon.json` are derived by `GeoName.buildJapanCache`.
  The lexicon maps Japanese names and alternate names only when the same GeoNames row provides a usable
  ASCII name. `ChurchNameComponentAnalyzer` uses it for deterministic detection and translation.
- `japan/church-name-multilingual-lexicon.json` joins both official files by `geonameid` and retains preferred
  English, Korean, Portuguese, and Indonesian names for title/address translation and index construction.

The committed `resources/geonames/japan.json` catalog is different: its Japanese names and aliases are used
for detection only because it has no English-name field.

`PrepareGeoNameCache` performs download-if-missing, extraction, and derived-cache construction. All data payloads
are ignored; this README is committed.
