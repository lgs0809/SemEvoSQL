#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE="$PROJECT_ROOT/deploy/semevosql/.env.example"
TARGET="${SEMEVOSQL_COMPOSE_ENV_FILE:-$PROJECT_ROOT/deploy/semevosql/.env}"

if [[ ! -f "$TEMPLATE" ]]; then
  echo "Deployment template is missing: $TEMPLATE" >&2
  exit 1
fi
if [[ -e "$TARGET" ]]; then
  echo "Deployment environment already exists: $TARGET" >&2
  echo "Refusing to overwrite it." >&2
  exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required to generate deployment credentials." >&2
  exit 1
fi

metadata_value="$(openssl rand -hex 24)"
execution_value="$(openssl rand -hex 32)"
encryption_value="$(openssl rand -base64 32 | tr -d '\r\n')"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    SEMEVOSQL_METADATA_PASSWORD=)
      printf 'SEMEVOSQL_METADATA_PASSWORD=%s\n' "$metadata_value" >>"$tmp"
      ;;
    SEMEVOSQL_EXECUTION_INTERNAL_TOKEN=)
      printf 'SEMEVOSQL_EXECUTION_INTERNAL_TOKEN=%s\n' "$execution_value" >>"$tmp"
      ;;
    SEMEVOSQL_SECRET_ENCRYPTION_KEY=)
      printf 'SEMEVOSQL_SECRET_ENCRYPTION_KEY=%s\n' "$encryption_value" >>"$tmp"
      ;;
    SEMEVOSQL_MCP_PUBLIC_BASE_URL=*)
      printf 'SEMEVOSQL_MCP_PUBLIC_BASE_URL=\n' >>"$tmp"
      ;;
    SEMEVOSQL_METADATA_PORT=*)
      ;;
    *)
      printf '%s\n' "$line" >>"$tmp"
      ;;
  esac
done <"$TEMPLATE"

umask 077
mkdir -p "$(dirname "$TARGET")"
mv "$tmp" "$TARGET"
trap - EXIT
chmod 600 "$TARGET" 2>/dev/null || true

echo "Created $TARGET"
echo "Review ports/exposure settings if needed, then run: ./scripts/start-semevosql.sh"
