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
package cn.lgs.semevosql.learning;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryCaseScopeIsolationTest {

	@Test
	void userBoundCaseIsReusableOnlyByItsOwner() {
		SemanticBlueprint plan = plan("USER", "user-a");

		assertThat(QueryCaseRecallService.scopeCompatible(plan, "user-a")).isTrue();
		assertThat(QueryCaseRecallService.scopeCompatible(plan, "user-b")).isFalse();
		assertThat(QueryCaseRecallService.scopeCompatible(plan, null)).isFalse();
	}

	@Test
	void queryAndProjectPendingCasesNeverBecomeSharedHistoricalSemanticHints() {
		assertThat(QueryCaseRecallService.scopeCompatible(plan("QUERY", "user-a"), "user-a")).isFalse();
		assertThat(QueryCaseRecallService.scopeCompatible(plan("PROJECT_PENDING", "user-a"), "user-a")).isFalse();
		assertThat(QueryCaseRecallService.scopeCompatible(plan("PROJECT_PENDING", "user-a"), "user-b")).isFalse();
	}

	@Test
	void publishedProjectAndCatalogCasesRemainProjectReusable() {
		assertThat(QueryCaseRecallService.scopeCompatible(plan("PROJECT", null), "user-b")).isTrue();
		assertThat(QueryCaseRecallService.scopeCompatible(plan("CATALOG", null), null)).isTrue();
	}

	@Test
	void queryCaseFingerprintScopeSignatureSeparatesPrincipals() {
		assertThat(QueryCaseCaptureService.scopeSignature(plan("USER", "user-a")))
			.isNotEqualTo(QueryCaseCaptureService.scopeSignature(plan("USER", "user-b")));
		assertThat(QueryCaseCaptureService.scopeSignature(plan("QUERY", "user-a")))
			.isNotEqualTo(QueryCaseCaptureService.scopeSignature(plan("PROJECT_PENDING", "user-a")));
	}

	private SemanticBlueprint plan(String scope, String principal) {
		return SemanticBlueprint.builder()
			.bindingDependencies(List.of(SemanticBlueprint.BindingDependency.builder()
				.phrase("成交额")
				.assetType("METRIC")
				.assetKey("paid_amount")
				.scope(scope)
				.source(scope)
				.principalId(principal)
				.build()))
			.build();
	}
}
