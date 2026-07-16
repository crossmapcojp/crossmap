# Search index and snapshot cache

`SnapshotBuilder.build` writes Lucene indexes, manifests, and downloadable ZIP archives under `churches/`.
The Ktor server reads `churches/latest.json`, serves its archive, and opens the selected index. These generated
binary artifacts are reproducible from the committed catalog and are not committed.
