#!/bin/sh
set -eu

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'semevosql_reader'@'%' IDENTIFIED BY '${SEMEVOSQL_DEMO_MYSQL_READER_PASSWORD}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'semevosql_reader'@'%';
GRANT SELECT, SHOW VIEW ON product_db.* TO 'semevosql_reader'@'%';
ALTER USER 'semevosql_reader'@'%' WITH MAX_USER_CONNECTIONS 20;
FLUSH PRIVILEGES;
SQL
