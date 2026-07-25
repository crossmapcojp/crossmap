#!/usr/bin/env sh
set -eu
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <churches.json> [--dry-run]" >&2
    exit 2
fi
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
input=$1
shift
dry_run=""
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=" --dry-run"
    shift
fi
if [ "$#" -ne 0 ]; then
    echo "Unexpected argument: $1" >&2
    exit 2
fi
cd "$project_root"
exec ./gradlew :crawl:run --args="catalog-neo4j-import --input $input$dry_run" --console=plain
