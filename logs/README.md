This is the log directory for Gradle task statistics such as crawl results and church-name translation.

The log format should be something like `2026-07-14-17-10-data-cleanup-stat.log`.

Each successful English-name cleanup also writes
`yyyy-MM-dd-HH-mm-llm-composed-name-detail.log`. It lists every church whose final English name
used the LLM pipeline and its ordered name parts. Each child part records its type, Japanese source,
English translation, evidence, and translation method (denomination data, GeoNames data,
special-geoname dictionary, concept dictionary, other deterministic data, or LLM).
