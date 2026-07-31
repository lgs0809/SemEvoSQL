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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryRunPublicPresenterTest {

	private final QueryRunPublicPresenter presenter = new QueryRunPublicPresenter(new QueryRunErrorPresenter());

	@Test
	void failedRunExposesStablePublicFailureInsteadOfDurableTechnicalMessage() {
		QueryRun run = QueryRun.builder()
			.runId("run-1")
			.runType(QueryRun.RunType.INTERACTIVE_QUERY)
			.status(QueryRun.RunStatus.FAILED)
			.errorCode("SQL_EXECUTION_FAILED")
			.errorMessage("relation secret_table does not exist; artifact=internal-42")
			.requestPayload("raw-request")
			.recoveryPayload("raw-recovery")
			.executionSnapshot("raw-snapshot")
			.build();

		QueryRunPublicView view = presenter.present(run);

		assertThat(view.errorCode()).isEqualTo("QUERY_EXECUTION_FAILED");
		assertThat(view.errorMessage()).isEqualTo("查询执行失败，请稍后重试。");
		assertThat(view.errorMessage()).doesNotContain("secret_table", "artifact", "internal-42");
		assertThat(view.retryable()).isTrue();
	}

	@Test
	void activeRunDoesNotExposeStaleInternalFailureFields() {
		QueryRun run = QueryRun.builder()
			.runId("run-2")
			.runType(QueryRun.RunType.INTERACTIVE_QUERY)
			.status(QueryRun.RunStatus.RUNNING)
			.errorCode("OLD_INTERNAL_CODE")
			.errorMessage("old internal error")
			.build();

		QueryRunPublicView view = presenter.present(run);

		assertThat(view.errorCode()).isNull();
		assertThat(view.errorMessage()).isNull();
		assertThat(view.retryable()).isNull();
	}

	@Test
	void timeoutRunKeepsAStableTimeoutCategory() {
		QueryRun run = QueryRun.builder()
			.runId("run-timeout")
			.runType(QueryRun.RunType.INTERACTIVE_QUERY)
			.status(QueryRun.RunStatus.FAILED)
			.errorCode("INTERACTIVE_QUERY_TIMEOUT")
			.errorMessage("internal absolute deadline details")
			.build();

		QueryRunPublicView view = presenter.present(run);

		assertThat(view.errorCode()).isEqualTo("QUERY_TIMEOUT");
		assertThat(view.errorMessage()).contains("查询执行超时").doesNotContain("internal");
		assertThat(view.retryable()).isTrue();
	}

}
