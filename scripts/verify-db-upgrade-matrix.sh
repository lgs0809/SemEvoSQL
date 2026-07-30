#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

# DatabaseUpgradeMatrixIT owns its disposable Testcontainers database by default.
# An explicit CI JDBC URL is still supported, with uniquely named temporary schemas.
# Never remove a pre-existing container by a user-supplied name.
exec ./mvnw -s .github/maven-settings.xml -pl backend -am \
  -Dtest=DatabaseUpgradeMatrixIT -Dsurefire.failIfNoSpecifiedTests=false test
