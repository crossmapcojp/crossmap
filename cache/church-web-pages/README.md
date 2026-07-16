# Church web page cache

`WebsiteRefresher.refresh`, `WebsiteRefresher.fetch`, and `CachedDirectoryPageLoader.load` use this directory.
`pages/` stores content-addressed downloaded church/denomination HTML, `url-cache-map.json` maps URL hashes to
content hashes, and `manifest.json` records acquisition metadata. `OfficialDirectoryCrawler` and
`SocialLinkPipeline.extractCrawledPageLinks` consume the same cache.
