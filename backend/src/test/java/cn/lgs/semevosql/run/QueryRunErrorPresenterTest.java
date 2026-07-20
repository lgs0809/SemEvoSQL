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
package cn.lgs.semevosql.run;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QueryRunErrorPresenterTest {
    private final QueryRunErrorPresenter presenter=new QueryRunErrorPresenter();

    @Test void invalidGovernedModelSelectionRetainsItsCauseAfterRecoveryWindowExpires() {
        var current=presenter.present("INVALID_GOVERNED_SELECTION");
        assertThat(current.code()).isEqualTo("INVALID_GOVERNED_SELECTION");
        assertThat(current.retryable()).isTrue();
        var expired=presenter.present(QueryRun.builder().status(QueryRun.RunStatus.FAILED)
            .errorCode("INVALID_GOVERNED_SELECTION").deadlineEpochMillis(1L).build());
        assertThat(expired.code()).isEqualTo(current.code());
        assertThat(expired.message()).contains("业务口径方案未通过校验","本次运行已结束").doesNotContain("超时");
        assertThat(expired.retryable()).isFalse();
    }

    @Test void missingMetricIsNotRetryableAndDoesNotBecomeAnExpiredQueryLater() {
        var run=QueryRun.builder().status(QueryRun.RunStatus.FAILED).errorCode("MODEL_UNRESOLVABLE")
            .deadlineEpochMillis(1L).build();
        var result=presenter.present(run);
        assertThat(result.code()).isEqualTo("SEMANTIC_PLANNING_REJECTED");
        assertThat(result.retryable()).isFalse();
        assertThat(result.message()).contains("业务模型").doesNotContain("超时","时限");
    }

    @Test void transientFailureKeepsItsOriginalRunDeadline() {
        var run=QueryRun.builder().status(QueryRun.RunStatus.FAILED).errorCode("MODEL_PROVIDER_TIMEOUT")
            .deadlineEpochMillis(1L).build();
        assertThat(presenter.present(run).code()).isEqualTo("QUERY_TIMEOUT");
        assertThat(presenter.present(run).retryable()).isFalse();
        assertThat(presenter.present("MODEL_PROVIDER_TIMEOUT").retryable()).isTrue();
    }
    @Test void anExpiredRecoveryBudgetDoesNotRelabelAnEarlierExecutionFailureAsTimeout() {
        var run=QueryRun.builder().status(QueryRun.RunStatus.FAILED).errorCode("GRAPH_EXECUTION_FAILED")
            .deadlineEpochMillis(1L).build();
        var result=presenter.present(run);
        assertThat(result.code()).isEqualTo("QUERY_EXECUTION_FAILED");
        assertThat(result.message()).doesNotContain("超时","时限");
        assertThat(result.retryable()).isFalse();
    }

}
