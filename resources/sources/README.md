# Standalone crawler sources

`denominations.json` is the data-driven list of denominations and official directories. Each record has a stable `id`, `denominationId`, `denominationName`, `denominationWebsiteUrl`, `churchListUrlList`, optional social URLs, and CSS selectors for a church entry, its name, optional address, and optional link. Add or revise records here; no denomination-specific Kotlin class is required.

An empty list is valid. `crossmap-crawl crawl-denomination-directories` writes normalized evidence to `resources/evidence/denomination-directory.json` and merges candidates into `resources/cleanup/denomination-candidates.json`.

`social-accounts.json` lives under `resources/evidence`. It accepts typed Facebook, X, Instagram, and YouTube candidates collected by future social crawlers. `crossmap-crawl link-social` first checks cached church-page hyperlinks, then exact/containing normalized names, and only then asks the configured local LLM.
