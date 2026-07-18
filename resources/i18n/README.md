# Crossmap UI message catalogs

This directory is the single source of truth for user-interface text used by the Compose Multiplatform app and the generated vanilla-JavaScript website. Church names, denomination names, addresses, geonames, website URLs, and social URLs are domain data and do not belong here.

`values/strings.xml` is English and the fallback catalog. The other directories map directly to the core `Language` enum:

| Language | Directory |
|---|---|
| English (`en`) | `values/` |
| Japanese (`ja`) | `values-ja/` |
| Korean (`ko`) | `values-ko/` |
| Portuguese (`pt`) | `values-pt/` |
| Indonesian (`id`) | `values-id/` |

Keys use semantic snake-case names. Values are plain text without HTML. Dynamic arguments use numbered Compose placeholders such as `%1$s`; every translation of a key must have the same placeholder set as English.

To add a message, add the same nonblank key to all five files and run `./gradlew :server:validateI18n`. To add a language, first add it to the core `Language` enum, then add the matching qualified directory and translations. `:server:generateChurchPages` validates and consumes these catalogs for static pages; app resource generation consumes the same directory.
