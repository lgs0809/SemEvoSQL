#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER_NAME="${SEMEVOSQL_UPGRADE_MATRIX_CONTAINER:-semevosql-upgrade-matrix-$$}"
DB_USER="${SEMEVOSQL_UPGRADE_MATRIX_DB_USER:-semevosql}"
DB_NAME="${SEMEVOSQL_UPGRADE_MATRIX_DB_NAME:-semevosql_upgrade}"
POSTGRES_IMAGE="${SEMEVOSQL_UPGRADE_MATRIX_POSTGRES_IMAGE:-pgvector/pgvector:pg16}"

cleanup() {
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup

docker run -d \
  --name "${CONTAINER_NAME}" \
  -e "POSTGRES_USER=${DB_USER}" \
  -e "POSTGRES_DB=${DB_NAME}" \
  -e POSTGRES_HOST_AUTH_METHOD=trust \
  -p 127.0.0.1::5432 \
  "${POSTGRES_IMAGE}" >/dev/null

ready=false
for _ in $(seq 1 60); do
  if docker exec "${CONTAINER_NAME}" pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [[ "${ready}" != "true" ]]; then
  echo "PostgreSQL did not become ready for the migration upgrade matrix" >&2
  docker logs "${CONTAINER_NAME}" >&2 || true
  exit 1
fi

HOST_PORT="$(docker inspect -f '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "${CONTAINER_NAME}")"
python3 - "${HOST_PORT}" <<'PY'
import socket
import sys
import time

port = int(sys.argv[1])
for _ in range(60):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            break
    except OSError:
        time.sleep(0.5)
else:
    raise SystemExit("PostgreSQL host port did not become reachable")
PY

export SEMEVOSQL_UPGRADE_MATRIX_JDBC_URL="jdbc:postgresql://localhost:${HOST_PORT}/${DB_NAME}"
export SEMEVOSQL_UPGRADE_MATRIX_DB_USER="${DB_USER}"

cd "${ROOT_DIR}"
./mvnw -s .github/maven-settings.xml -pl backend -Dtest=DatabaseUpgradeMatrixIT test
