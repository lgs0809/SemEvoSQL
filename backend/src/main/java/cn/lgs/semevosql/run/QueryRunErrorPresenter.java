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

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Maps durable technical failure facts to a stable, user-safe public error contract. */
@Component
public class QueryRunErrorPresenter {

	public ErrorPresentation present(QueryRun run) {
		if (run == null) {
			return new ErrorPresentation("QUERY_EXECUTION_FAILED", "查询执行失败，请稍后重试。", true);
		}
		if (run.status() == QueryRun.RunStatus.CANCELLED) {
			return new ErrorPresentation("QUERY_CANCELLED", "查询已取消。", false);
		}
		if (run.status() == QueryRun.RunStatus.EXPIRED) {
			return new ErrorPresentation("QUERY_EXPIRED", "查询已过期，请重新发起。", true);
		}
		ErrorPresentation failure = present(run.errorCode());
		if (failure.retryable() && run.status() == QueryRun.RunStatus.FAILED && run.deadlineEpochMillis() != null
				&& run.deadlineEpochMillis() <= System.currentTimeMillis()) {
			// Exhausted recovery budget does not rewrite the historical failure cause.
            return new ErrorPresentation(failure.code(),failure.message()+"本次运行已结束，请重新发起查询。",false);
		}
		return failure;
	}

	public ErrorPresentation present(String errorCode) {
		String code = StringUtils.hasText(errorCode) ? errorCode.trim() : "QUERY_EXECUTION_FAILED";
		return switch (code) {
			case "RUN_DEADLINE_EXCEEDED" ->
				new ErrorPresentation("QUERY_EXPIRED", "本次查询的执行时限已到，请重新发起查询。", false);
			case "MODEL_OUTPUT_INVALID" -> new ErrorPresentation(code, "模型返回结果格式无效，请重试。", true);
            case "INVALID_GOVERNED_SELECTION" -> new ErrorPresentation(code,
                "模型生成的业务口径方案未通过校验，可以重试。", true);
			case "INTERACTIVE_QUERY_TIMEOUT", "MODEL_PROVIDER_TIMEOUT" ->
				new ErrorPresentation("QUERY_TIMEOUT", "查询执行超时，请稍后重试；如果问题较复杂，可缩小查询范围后重试。", true);
			case "MODEL_UNAVAILABLE", "MODEL_CONNECTION_FAILED", "MODEL_PROVIDER_UNAVAILABLE" ->
				new ErrorPresentation("MODEL_UNAVAILABLE", "模型服务暂时不可用，请稍后重试。", true);
			case "SEMANTIC_CLARIFICATION_REQUIRED" ->
				new ErrorPresentation(code, "当前问题需要补充信息后才能继续。", false);
			case "CANDIDATE_BUDGET_EXCEEDED" -> new ErrorPresentation(code, "本次问题关联的业务模型较多，无法在当前查询范围内完整加载，请缩小问题范围后重试。", false);
            case "CANDIDATE_IDENTITY_REQUIRED", "CANDIDATE_SCOPE_CHANGED", "CATALOG_IDENTITY_MISSING", "CATALOG_IDENTITY_CHANGED" ->
                new ErrorPresentation("SEMANTIC_PLANNING_REJECTED", "业务模型的可用范围已变化，请检查当前项目和业务模型后重新查询。", false);
            case "SEMANTIC_PLANNING_REJECTED", "PLAN_RESOLUTION_ERROR", "CANDIDATE_BUILD_EMPTY",
					"RETRIEVAL_MISS", "MODEL_UNRESOLVABLE" -> new ErrorPresentation("SEMANTIC_PLANNING_REJECTED",
							"暂时无法生成可靠的查询方案，请调整问题或检查业务模型。", false);
			case "QUERY_POLICY_REJECTED", "SQL_SAFETY_REJECTED", "SQL_POLICY_REJECTED", "COST_GUARD_REJECTED" ->
				new ErrorPresentation("QUERY_POLICY_REJECTED", "查询未通过安全或资源策略，请缩小查询范围后重试。", false);
			case "QUERY_EXECUTION_FAILED", "SQL_EXECUTION_FAILED", "MCP_QUERY_EXECUTION_FAILED", "JOB_FAILED",
					"GRAPH_DISPATCH_FAILED" -> new ErrorPresentation("QUERY_EXECUTION_FAILED", "查询执行失败，请稍后重试。", true);
			case "QUERY_CANCELLED" -> new ErrorPresentation(code, "查询已取消。", false);
			case "QUERY_EXPIRED" -> new ErrorPresentation(code, "查询已过期，请重新发起。", true);
			default -> new ErrorPresentation("QUERY_EXECUTION_FAILED", "查询执行失败，请稍后重试。", true);
		};
	}

	public record ErrorPresentation(String code, String message, boolean retryable) {
	}
}
