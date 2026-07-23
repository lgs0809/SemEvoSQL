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

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlTimePredicateGuardTest {
    @Test void quotedMultilineAndBoundRangesAreActualPredicates() {
        assertTrue(SqlTimePredicateGuard.hasTimeFilter("SELECT sum(amount) FROM orders t\nWHERE\n t.\"paid_at\" >= TIMESTAMP '2026-01-01 00:00:00' AND t.\"paid_at\" < TIMESTAMP '2026-02-01 00:00:00'", List.of("paid_at")));
        assertTrue(SqlTimePredicateGuard.hasTimeFilter("SELECT * FROM orders WHERE paid_at >= ? AND paid_at < ?", List.of("orders.paid_at")));
        assertTrue(SqlTimePredicateGuard.hasTimeFilter("SELECT * FROM orders WHERE `paid_at` BETWEEN ? AND ?", List.of("paid_at")));
    }
    @Test void mentionsAndUnboundedOrDoNotSatisfyTheGuard() {
        for (String predicate : List.of("note = 'paid_at'", "1=1 /* paid_at >= ? */", "paid_at=paid_at", "paid_at > ? OR 1=1", "paid_at IS NOT NULL"))
            assertFalse(SqlTimePredicateGuard.hasTimeFilter("SELECT * FROM orders WHERE " + predicate, List.of("paid_at")), predicate);
        assertFalse(SqlTimePredicateGuard.hasTimeFilter("SELECT paid_at FROM orders", List.of("paid_at")));
    }
    @Test void temporalLiteralAndParameterCastsRemainBoundedPredicates() {
        for (String predicate : List.of(
                "paid_at >= '2026-01-01T00:00'::timestamp AND paid_at < '2026-02-01T00:00'::timestamp",
                "paid_at >= CAST('2026-01-01' AS DATE) AND paid_at < CAST('2026-02-01' AS TIMESTAMP)",
                "paid_at >= CAST(? AS TIMESTAMP) AND paid_at < ?::timestamp",
                "paid_at BETWEEN CAST(? AS DATETIME) AND CAST(? AS DATETIME)",
                "paid_at >= '2026-01-01T00:00+08:00'::timestamptz"))
            assertTrue(SqlTimePredicateGuard.hasTimeFilter("SELECT * FROM orders WHERE " + predicate,
                    List.of("paid_at")), predicate);
    }
    @Test void temporalCastDoesNotMakeAColumnFunctionNullOrOtherTypeConstant() {
        for (String predicate : List.of(
                "paid_at >= other_time::timestamp", "paid_at >= CAST(other_time AS DATE)",
                "paid_at >= CAST(now() AS TIMESTAMP)", "paid_at >= NULL::timestamp",
                "paid_at >= CAST(? AS TEXT)", "paid_at >= CAST(CAST(? AS TEXT) AS TIMESTAMP)"))
            assertFalse(SqlTimePredicateGuard.hasTimeFilter("SELECT * FROM orders WHERE " + predicate,
                    List.of("paid_at")), predicate);
    }
    @Test void castCannotBypassAnUnboundedOrOrInventAMissingSemanticTimeColumn() {
        assertFalse(SqlTimePredicateGuard.hasTimeFilter(
                "SELECT * FROM orders WHERE paid_at >= ?::timestamp OR 1=1", List.of("paid_at")));
        assertFalse(SqlTimePredicateGuard.hasTimeFilter(
                "SELECT * FROM orders WHERE paid_at >= ?::timestamp", List.of()));
        assertFalse(SqlTimePredicateGuard.hasTimeFilter(
                "SELECT * FROM orders WHERE other_time >= ?::timestamp", List.of("paid_at")));
    }

}
