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
package cn.lgs.semevosql.service.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.application.SemanticPlanningRejectedException;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

class GraphServiceImplFailureClassificationTest {

	@Test
	void transientProviderFailuresAreRecoverableAndHaveStableCode() {
		WebClientResponseException unavailable = WebClientResponseException.create(503, "Service Unavailable",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

		assertThat(GraphFailureClassifier.recoverableModelFailure(unavailable)).isTrue();
		assertThat(GraphFailureClassifier.errorCode(unavailable)).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
	}

	@Test
	void deterministicProviderFourHundredsAreNotAutoRecovered() {
		WebClientResponseException badRequest = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY,
				new byte[0], StandardCharsets.UTF_8);

		assertThat(GraphFailureClassifier.recoverableModelFailure(badRequest)).isFalse();
		assertThat(GraphFailureClassifier.errorCode(badRequest)).isEqualTo("MODEL_PROVIDER_REQUEST_REJECTED");
		assertThat(GraphFailureClassifier.publicMessage(badRequest)).doesNotContain("Bad Request");
	}

	@Test
	void semanticPlanningFailuresKeepStableCodeWithoutLeakingResolverDetails() {
		SemanticPlanningRejectedException failure = new SemanticPlanningRejectedException("PLAN_RESOLUTION_ERROR",
				"Invalid SCALAR resultComposition shape: metricModels=[pay_order], effectiveModels=[internal_model]");

		assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("PLAN_RESOLUTION_ERROR");
		assertThat(GraphFailureClassifier.publicMessage(failure)).doesNotContain("metricModels", "internal_model");
	}

	@Test
	void unknownFailuresUseStableGenericBoundary() {
		IllegalStateException failure = new IllegalStateException("jdbc:postgresql://secret-host/private_schema");

		assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("GRAPH_EXECUTION_FAILED");
		assertThat(GraphFailureClassifier.publicMessage(failure)).isEqualTo("查询执行失败，请稍后重试。");
	}

	@Test
	void absoluteRunDeadlineExpiresEvenWhileGraphKeepsEmittingOutput() {
		Flux<Long> activeStream = Flux.interval(java.time.Duration.ofMillis(5));

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> GraphServiceImpl
				.enforceAbsoluteDeadline(activeStream, System.currentTimeMillis() + 40)
				.collectList()
				.block())
			.isInstanceOf(RunDeadlineExceededException.class);
	}

	@Test
	void explicitRunDeadlineHasStableTimeoutClassification() {
		RunDeadlineExceededException failure = new RunDeadlineExceededException("deadline");

		assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("INTERACTIVE_QUERY_TIMEOUT");
	}
}
