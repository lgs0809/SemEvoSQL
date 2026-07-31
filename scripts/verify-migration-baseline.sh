#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BASELINE=${1:-docs/release-baselines/v1.0.0-migrations.sha256}
cd "$ROOT_DIR"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check "$BASELINE"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c "$BASELINE"
else
  echo "Neither sha256sum nor shasum is available" >&2
  exit 1
fi
