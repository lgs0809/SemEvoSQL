-- PostgreSQL only. All tables and data are temporary; rollback and connection close remove them.
-- Run with psql -v ON_ERROR_STOP=1 -f scripts/sql/cte-scope-repro.sql in an isolated local DB.
-- This demonstrates database name binding, not a call to the application's SQL guard.
BEGIN;
CREATE TEMP TABLE sem_cte_orders(id integer PRIMARY KEY, paid_amount numeric) ON COMMIT DROP;
INSERT INTO sem_cte_orders VALUES (1,150),(2,120);
CREATE TEMP TABLE sem_cte_secret(id integer PRIMARY KEY) ON COMMIT DROP;
INSERT INTO sem_cte_secret VALUES (999);

-- A valid CTE aggregate: 270.
WITH paid AS (SELECT paid_amount FROM sem_cte_orders)
SELECT sum(paid_amount) AS expected_270 FROM paid;

-- The qualified name addresses the real temporary table, not the same-named CTE: 999.
-- The application guard must reject this when only sem_cte_orders is authorized.
WITH sem_cte_secret AS (SELECT id FROM sem_cte_orders)
SELECT id AS forbidden_physical_table_sentinel FROM pg_temp.sem_cte_secret;

-- Inner CTE declarations do not become visible outside their scope: 999 comes from the real table.
WITH inner_query AS (
    WITH sem_cte_secret AS (SELECT id FROM sem_cte_orders)
    SELECT id FROM sem_cte_secret
)
SELECT id AS forbidden_outside_inner_scope FROM sem_cte_secret;
ROLLBACK;
