# Japanese address normalization cache

`JapaneseAddressNormalizationPipeline` in
`crawl/src/main/kotlin/jp/co/crossmap/crawl/JapaneseAddressNormalizationPipeline.kt`
uses this directory for `normalized-addresses.json`.

The pipeline runs the locally cloned Geolonia
`normalize-japanese-addresses` Node module, records its normalization level and
structured result for each church, then adds Crossmap's typed address parts and
geoname codes. The generated JSON is machine-local, reproducible processing
state and is ignored by Git. This README is committed.
