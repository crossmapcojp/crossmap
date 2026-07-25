#!/usr/bin/env sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:-build/reports/catalog-export/churches.json}
cd "$project_root"
exec ./gradlew :crawl:run --args="catalog-neo4j-export --output $output" --console=plain
