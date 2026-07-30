#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Deprecated: use ./scripts/start-semevosql.sh" >&2
exec "$SCRIPT_DIR/start-semevosql.sh" "$@"
