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

import cn.lgs.semevosql.semantic.application.SemanticPlanningRejectedException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Classifies graph failures into durable recovery/public error categories. */
final class GraphFailureClassifier {

	private GraphFailureClassifier() {
	}

	static String errorCode(Throwable error) {
        if (queryPolicyFailure(error)) return "QUERY_POLICY_REJECTED";
		if (invalidModelOutput(error)) {
			return "MODEL_OUTPUT_INVALID";
		}
		SemanticPlanningRejectedException planningFailure = semanticPlanningFailure(error);
		if (planningFailure != null && StringUtils.hasText(planningFailure.errorCode())) {
			return planningFailure.errorCode();
		}
		if (recoverableModelFailure(error)) {
			return modelTimeoutFailure(error) ? "MODEL_PROVIDER_TIMEOUT" : "MODEL_PROVIDER_UNAVAILABLE";
		}
		if (error instanceof WebClientResponseException response && response.getStatusCode().is4xxClientError()) {
			return "MODEL_PROVIDER_REQUEST_REJECTED";
		}
		return timeoutFailure(error) ? "INTERACTIVE_QUERY_TIMEOUT" : "GRAPH_EXECUTION_FAILED";
	}

	static String publicMessage(Throwable error) {
        if (queryPolicyFailure(error)) return new cn.lgs.semevosql.run.QueryRunErrorPresenter().present("QUERY_POLICY_REJECTED").message();
		if (invalidModelOutput(error)) {
			return "模型返回结果格式无效，请重试。";
		}
		if (timeoutFailure(error)) {
			return "查询执行超时，请稍后重试；如果问题较复杂，可缩小查询范围后重试。";
		}
		String message = error == null ? null : error.getMessage();
		if (upstreamModelFailure(error, message)) {
			return "模型服务暂时不可用，请稍后重试。";
		}
		if (semanticPlanningFailure(error) != null) {
			return "当前问题无法在已发布业务模型约束下形成可执行查询，请调整问题或业务模型后重试。";
		}
		if (error instanceof WebClientResponseException response && response.getStatusCode().is4xxClientError()) {
			return "模型服务拒绝了当前请求，请检查模型配置后重试。";
		}
		return "查询执行失败，请稍后重试。";
	}

	static boolean recoverableModelFailure(Throwable error) {
		if (invalidModelOutput(error)) {
			return false;
		}
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof org.springframework.ai.retry.TransientAiException
                    || current instanceof WebClientRequestException
                    || current instanceof io.netty.handler.timeout.TimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
				return true;
			}
			if (current instanceof WebClientResponseException response) {
				int status = response.getStatusCode().value();
				if (status == 408 || status == 429 || status >= 500) return true;
                if (!response.getStatusCode().is2xxSuccessful()) return false;
                // A successful header does not mean the streaming response body completed.
                current = current.getCause();
                continue;
			}
			String className = current.getClass().getName();
			String currentMessage = current.getMessage();
			if (className.contains("ModelCircuitOpenException") || className.contains("ModelCapacityException")
					|| className.contains("TransientModelException") || className.contains("PrematureCloseException")
					|| className.contains("ServiceUnavailable") || containsUpstreamHttpFailure(currentMessage)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static SemanticPlanningRejectedException semanticPlanningFailure(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof SemanticPlanningRejectedException planningFailure) {
				return planningFailure;
			}
			current = current.getCause();
		}
		return null;
	}

    private static boolean queryPolicyFailure(Throwable error) {
        for (int depth=0;error!=null && depth<8;depth++,error=error.getCause())
            if(error instanceof cn.lgs.semevosql.sql.application.SqlGuardViolationException) return true;
        return false;
    }

	private static boolean invalidModelOutput(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof cn.lgs.semevosql.exception.ModelOutputInvalidException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean upstreamModelFailure(Throwable error, String message) {
		return recoverableModelFailure(error) || containsUpstreamHttpFailure(message);
	}

	private static boolean modelTimeoutFailure(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof TimeoutException
                    || current instanceof io.netty.handler.timeout.TimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
				return true;
			}
			if (current instanceof WebClientResponseException response && response.getStatusCode().value() == 408) {
				return true;
			}
			String message = current.getMessage();
			if (StringUtils.hasText(message) && message.toLowerCase(Locale.ROOT).contains("timeout")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean containsUpstreamHttpFailure(String message) {
		if (!StringUtils.hasText(message)) {
			return false;
		}
		String normalized = message.toLowerCase(Locale.ROOT);
		return normalized.contains("serviceunavailable") || normalized.contains(" from post http://")
				|| normalized.contains(" from post https://") || normalized.contains("connection refused");
	}

	private static boolean timeoutFailure(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof TimeoutException) {
				return true;
			}
			String message = current.getMessage();
			if (StringUtils.hasText(message) && (message.contains("Timeout on blocking read")
					|| message.contains("Did not observe any item or terminal signal within"))) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

}
