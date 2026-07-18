# Official denomination church-list cache

`DenominationChurchListCrawlerRunner` and `CachedHttpDenominationChurchPageLoader` use this directory.
Each denomination gets a machine-local subdirectory containing the latest official source HTML and acquisition
metadata. `crawl-denomination-directories --force-refresh` deletes the selected cached source page before fetching
it again. `UCCJDenominationChurchListCrawler` and `JBCDenominationChurchListCrawler` parse these pages into the
generated `resources/crawl/uccj-churches.json` and `resources/crawl/jbc-churches.json` artifacts.

Only this README is committed. Downloaded HTML and metadata are ignored by Git.
