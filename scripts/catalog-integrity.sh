#!/usr/bin/env sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"
exec ./gradlew :crawl:run --args="catalog-neo4j-integrity" --console=plain
