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
import cn.lgs.semevosql.connector.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class SqlFailureRoutingTest {
    final SqlValidationClassifier classifier=new SqlValidationClassifier();
    @Test void timeoutIsSqlAdjustmentWithNoAdditionalLocalRetryPool() {
        for(int local=0;local<4;local++) {
            var result=classifier.classify(new SQLException("canceling statement due to statement timeout","57014"),local);
            assertTrue(result.retryAllowed());assertEquals("sql-generate",result.allowedReturnNode());
        }
        var policy=new cn.lgs.semevosql.review.QueryRepairPolicy();
        var budget=cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget.empty();
        for(int i=0;i<2;i++){var result=policy.consumeTransition(budget,cn.lgs.semevosql.review.PostExecutionReview.Decision.RETRY_SQL);assertTrue(result.allowed());budget=result.budget();}
        assertFalse(policy.consumeTransition(budget,cn.lgs.semevosql.review.PostExecutionReview.Decision.RETRY_SQL).allowed());
    }
    @Test void permissionAndConnectionErrorsCannotBecomeSqlChanges() {
        for(String state:new String[]{"08006","08007","08S01","28000","42501"})
            assertFalse(classifier.classify(new SQLException("syntax timeout connection error",state),0).retryAllowed());
        assertFalse(classifier.classify(new SQLException("access denied","42000",1142),0).retryAllowed());
    }
    @Test void cleanupFailureInSuppressedExceptionOverridesAnOtherwiseRepairableTimeout() {
        var timeout=new SQLTimeoutException("timeout");timeout.addSuppressed(new JdbcQueryCleanupException(new SQLException("rollback failed")));
        var result=classifier.classify(new RuntimeException(timeout),0);assertFalse(result.retryAllowed());assertEquals("SQL_CLEANUP_UNCONFIRMED",result.errorType());
    }
    @Test void explicitCancellationNeverCreatesAnAdjustment() {
        assertFalse(classifier.classify(new RuntimeException(new SqlQueryCancelledException(null)),0).retryAllowed());
    }
}
