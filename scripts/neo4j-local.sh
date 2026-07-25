#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
data_root="$project_root/cache/neo4j-data"

mkdir -p "$data_root/data" "$data_root/transactions" "$data_root/logs" "$data_root/run" "$data_root/import" "$data_root/plugins"

export NEO4J_HOME="$project_root"
export NEO4J_CONF="$project_root/config/neo4j"

case "${1:-}" in
    start|stop|restart|status|console)
        command neo4j "$1"
        ;;
    validate)
        command neo4j-admin server validate-config
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|console|validate}" >&2
        exit 2
        ;;
esac
