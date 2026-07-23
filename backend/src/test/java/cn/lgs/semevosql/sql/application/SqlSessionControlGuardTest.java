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
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlSessionControlGuardTest {
    @Test void generatedQueriesCannotManipulateTimeoutOrOwnedSessions() {
        var guard=new SqlExecutionGuard();
        for(String sql:new String[]{"SELECT pg_terminate_backend(1) FROM orders", "SELECT pg_catalog.\"pg_cancel_backend\"(1) FROM orders",
            "SELECT pg_advisory_unlock_all() FROM orders", "SELECT get_lock('another',0) FROM orders",
            "SELECT release_lock('another') FROM orders", "SELECT set_config('statement_timeout','0',true) FROM orders"})
            assertThrows(SqlGuardViolationException.class,()->guard.validate(sql,"postgresql",Set.of("orders"),"public"),sql);
        for(String hint:new String[]{"MAX_EXECUTION_TIME(100000)","SET_VAR(max_execution_time=0)"})
            assertThrows(SqlGuardViolationException.class,()->guard.validate("SELECT /*+ "+hint+" */ amount FROM orders","mysql",Set.of("orders"),"public"));
        assertDoesNotThrow(()->guard.validate("SELECT SUM(amount) FROM orders WHERE status='PAID'","postgresql",Set.of("orders"),"public"));
    }
}
