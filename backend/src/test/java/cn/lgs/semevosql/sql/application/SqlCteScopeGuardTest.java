/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.sql.application;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlCteScopeGuardTest {
    private final SqlExecutionGuard guard=new SqlExecutionGuard();
    @ParameterizedTest @ValueSource(strings={
        "WITH secret AS (SELECT id FROM orders) SELECT id FROM restricted.secret",
        "WITH pg_class AS (SELECT id FROM orders) SELECT oid FROM pg_catalog.pg_class",
        "WITH x AS (WITH secret AS (SELECT id FROM orders) SELECT id FROM secret) SELECT id FROM secret",
        "WITH first AS (SELECT id FROM secret), secret AS (SELECT id FROM orders) SELECT id FROM first",
        "/* WITH secret AS ( */ SELECT id FROM secret",
        "SELECT 'WITH secret AS (' AS misleading FROM secret",
        "WITH deleted AS (DELETE FROM orders RETURNING id) SELECT id FROM deleted",
        "WITH changed AS (UPDATE orders SET id=1 RETURNING id) SELECT id FROM changed",
        "WITH added AS (INSERT INTO orders(id) VALUES(2) RETURNING id) SELECT id FROM added",
        "WITH \"Secret\" AS (SELECT id FROM orders) SELECT id FROM secret",
        "SELECT id FROM public.\"Orders\"",
        "SELECT id FROM orders FOR /*comment*/ UPDATE",
        "SELECT id FROM orders FOR /*comment*/ SHARE"
    })
    void rejectsOutOfScopeTablesAndWritesInsideReadShapedStatements(String sql){
        assertThrows(SqlGuardViolationException.class,()->guard.validate(sql,"postgresql",List.of("orders"),"public"),sql);
    }
    @ParameterizedTest @ValueSource(strings={
        "WITH permitted AS (SELECT id FROM orders) SELECT id FROM permitted",
        "WITH permitted(id) AS (SELECT id FROM orders) SELECT id FROM permitted",
        "WITH permitted AS (SELECT id FROM orders), second AS (SELECT id FROM permitted) SELECT count(*) FROM second",
        "WITH permitted AS (SELECT id FROM orders) SELECT (SELECT count(*) FROM permitted) FROM orders",
        "SELECT * FROM (WITH permitted AS (SELECT id FROM orders) SELECT id FROM permitted) sub",
        "WITH permitted AS (SELECT id FROM orders) SELECT * FROM (WITH permitted AS (SELECT id FROM orders) SELECT id FROM permitted) sub",
        "WITH RECURSIVE permitted(id) AS (SELECT id FROM orders UNION ALL SELECT id+1 FROM permitted WHERE id<3) SELECT id FROM permitted"
    })
    void permitsScopedCtesAndReturnsOnlyRealPhysicalDependencies(String sql){
        assertEquals(Set.of("orders"),guard.validate(sql,"postgresql",List.of("orders"),"public").referencedTables());
    }
    @Test void aQualifiedWhitelistEntryDoesNotAuthorizeAnotherSchemaOrSearchPath() {
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM public.orders","postgresql",List.of("restricted.orders"),"public"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM orders","postgresql",List.of("restricted.orders"),"public"));
        assertDoesNotThrow(()->guard.validate("SELECT id FROM orders","postgresql",List.of("public.orders"),"public"));
        assertDoesNotThrow(()->guard.validate("SELECT id FROM public.orders","postgresql",List.of("public.orders"),"public"));
    }
    @Test void quotedCaseSensitiveCteBindsOnlyTheIdenticalName() {
        assertEquals(Set.of("orders"),guard.validate("WITH \"Secret\" AS (SELECT id FROM orders) SELECT id FROM \"Secret\"",
            "postgresql",List.of("orders"),"public").referencedTables());
    }
    @Test void mysqlCteVisibilityAndPhysicalSchemaChecksAlsoApply() {
        assertThrows(SqlGuardViolationException.class,()->guard.validate("WITH secret AS (SELECT id FROM orders) SELECT id FROM restricted.secret","mysql",List.of("orders"),"app"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("WITH RECURSIVE first AS (SELECT id FROM secret), secret AS (SELECT id FROM orders) SELECT id FROM first","mysql",List.of("orders"),"app"));
        assertEquals(Set.of("orders"),guard.validate("WITH permitted AS (SELECT id FROM orders), second AS (SELECT id FROM permitted) SELECT id FROM second",
            "mysql",List.of("orders"),"app").referencedTables());
    }
    @Test void mysqlPhysicalTableCaseIsNotCollapsedIntoAnAllowedDifferentTable() {
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM Orders","mysql",List.of("orders"),"app"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM `Orders`","mysql",List.of("orders"),"app"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("WITH CTE AS (SELECT id FROM orders) SELECT id FROM cte","mysql",List.of("orders"),"app"));
        assertEquals(Set.of("orders"),guard.validate("WITH CTE AS (SELECT id FROM orders) SELECT id FROM CTE","mysql",List.of("orders"),"app").referencedTables());
    }
    @Test void catalogPhysicalCaseAndSpacesRemainExactRatherThanSqlTokenFolding() {
        assertDoesNotThrow(()->guard.validate("SELECT id FROM \"Orders\"","postgresql",List.of("Orders"),"public"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM orders","postgresql",List.of("Orders"),"public"));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM \" orders \"","postgresql",List.of("orders"),"public"));
    }
}
