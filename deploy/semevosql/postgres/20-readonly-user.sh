#!/bin/sh
set -eu

psql --username "${POSTGRES_USER}" --dbname postgres \
  --set=reader_password="${SEMEVOSQL_DEMO_POSTGRES_READER_PASSWORD}" <<'SQL'
SELECT format('CREATE ROLE semevosql_reader LOGIN PASSWORD %L', :'reader_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'semevosql_reader')\gexec
ALTER ROLE semevosql_reader NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE china_population_db TO semevosql_reader;
SQL

psql --username "${POSTGRES_USER}" --dbname china_population_db <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO semevosql_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO semevosql_reader;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO semevosql_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO semevosql_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO semevosql_reader;
SQL
