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
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual MySQL CTE binding and case-sensitive Linux table identities, in a disposable fixture. */
@Testcontainers
class SqlCteScopeMysqlIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("business")
        .withCommand("--performance-schema=OFF");
    static JdbcTemplate jdbc;
    final SqlExecutionGuard guard=new SqlExecutionGuard();
    @BeforeAll static void seed(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE orders(id integer,paid_amount integer)");jdbc.execute("INSERT INTO orders VALUES(1,150),(2,120)");
        jdbc.execute("CREATE TABLE secret(id integer)");jdbc.execute("INSERT INTO secret VALUES(999)");
        jdbc.execute("CREATE TABLE alias(id integer)");jdbc.execute("INSERT INTO alias VALUES(777)");
        jdbc.execute("CREATE TABLE Orders(id integer)");jdbc.execute("INSERT INTO Orders VALUES(888)");
    }
    @Test void qualificationBypassesTheCteAtTheDatabaseButNotTheGuard(){
        String sql="WITH secret AS (SELECT id FROM orders) SELECT id FROM business.secret";
        assertEquals(999,jdbc.queryForObject(sql,Integer.class));
        assertThrows(SqlGuardViolationException.class,()->guard.validate(sql,"mysql",List.of("orders"),"business"));
    }
    @Test void cteAndTableCaseRemainExactOnTheActualLinuxServer(){
        assertEquals(888,jdbc.queryForObject("SELECT id FROM Orders",Integer.class));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT id FROM Orders","mysql",List.of("orders"),"business"));
        assertEquals(777,jdbc.queryForObject("WITH ALIAS AS (SELECT id FROM orders) SELECT id FROM alias",Integer.class));
        assertThrows(SqlGuardViolationException.class,()->guard.validate("WITH ALIAS AS (SELECT id FROM orders) SELECT id FROM alias","mysql",List.of("orders"),"business"));
        assertEquals(2,jdbc.queryForObject("WITH ALIAS AS (SELECT id FROM orders) SELECT count(*) FROM ALIAS",Integer.class));
        assertDoesNotThrow(()->guard.validate("WITH ALIAS AS (SELECT id FROM orders) SELECT count(*) FROM ALIAS","mysql",List.of("orders"),"business"));
    }
    @Test void ordinaryNestedAndRecursiveQueriesStillExecute(){
        for(String sql:List.of("WITH paid AS (SELECT paid_amount FROM orders) SELECT sum(paid_amount) FROM paid",
            "SELECT sum(v) FROM (WITH paid(v) AS (SELECT paid_amount FROM orders) SELECT v FROM paid) nested_query")){
            guard.validate(sql,"mysql",List.of("orders"),"business");assertEquals(270,jdbc.queryForObject(sql,Integer.class));
        }
        String sql="WITH RECURSIVE r(id) AS (SELECT id FROM orders WHERE id=1 UNION ALL SELECT id+1 FROM r WHERE id<3) SELECT sum(id) FROM r";
        guard.validate(sql,"mysql",List.of("orders"),"business");assertEquals(6,jdbc.queryForObject(sql,Integer.class));
        System.out.println("REAL_MYSQL_CTE_SCOPE validAggregate=270 recursiveSum=6 qualifiedSentinel=999 distinctCaseSentinel=888");
    }
}
