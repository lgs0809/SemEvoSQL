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
package cn.lgs.semevosql.task;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Deterministic final synthesis for a multi-task request.
 *
 * <p>The service only renders facts that already exist in each executed Semantic Blueprint and durable accepted Todo
 * result. It does not invoke a model and therefore cannot add a new metric/filter/time definition during final
 * wording.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroundedRequestSynthesisService {

	private static final int MAX_RESULT_CHARS_PER_TASK = 12000;

	private final QueryTaskRepository taskRepository;

	public String synthesize(String runId, String originalRequest) {
		List<QueryTask> tasks = taskRepository.list(runId);
		if (tasks.isEmpty() || tasks.stream().anyMatch(task -> task.status() != QueryTask.TaskStatus.DONE)) {
			throw new IllegalStateException("Grounded synthesis requires all Query Todos to be DONE");
		}
		StringBuilder output = new StringBuilder();
		if (StringUtils.hasText(originalRequest)) {
			output.append("已完成：").append(originalRequest.trim()).append("\n\n");
		}
		for (int index = 0; index < tasks.size(); index++) {
			QueryTask task = tasks.get(index);
			SemanticBlueprint plan = taskRepository.plan(runId, task.taskId());
			output.append(index + 1).append(". ").append(task.question()).append('\n');
			String planFacts = renderPlanFacts(plan);
			if (!planFacts.isBlank()) {
				output.append("口径：").append(planFacts).append('\n');
			}
			output.append("结果：").append(bounded(renderAcceptedResult(taskRepository.resultSummaryJson(runId, task.taskId()), plan)))
				.append('\n');
			if (index + 1 < tasks.size()) {
				output.append('\n');
			}
		}
		return output.toString().trim();
	}

	private String renderAcceptedResult(String json, SemanticBlueprint plan) {
		if (!StringUtils.hasText(json)) {
			return "结果已通过验收，但持久化结果摘要为空。";
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(json);
			String report = root.path("report").asText("").trim();
			if (!report.isBlank()) {
				return report;
			}
			String payload = root.path("resultPayload").asText("").trim();
			JsonNode result = payload.isBlank() ? root : JsonUtil.getObjectMapper().readTree(payload);
			JsonNode table = result.has("resultSet") ? result.path("resultSet") : result;
			if (!table.path("column").isArray() || !table.path("data").isArray()) {
				return "结果已保存，请展开查询依据查看。";
			}
			if (table.path("data").isEmpty()) return "未找到符合条件的数据。";
			var labels = new java.util.HashMap<String, String>();
			if (plan != null) {
				plan.getMetrics().forEach(metric -> labels.put(metric.getMetricCode(), preferred(metric.getBusinessName(), metric.getMetricCode())));
				plan.getDimensions().forEach(dimension -> labels.put(dimension.getDimensionCode(), preferred(dimension.getBusinessName(), dimension.getDimensionCode())));
				plan.getProjections().stream().filter(projection -> projection.getTimeBucketGranularity() != null)
					.forEach(projection -> labels.put(projection.getAlias(), "时间"));
			}
			List<String> rows = new ArrayList<>();
			int displayed = 0;
			for (JsonNode row : table.path("data")) {
				if (displayed++ == 20) break;
				List<String> cells = new ArrayList<>();
				for (JsonNode column : table.path("column")) {
					String key = column.asText();
					JsonNode value = row.path(key);
					cells.add(labels.getOrDefault(key, key) + "：" + (value.isMissingNode() || value.isNull() ? "—" : value.asText()));
				}
				rows.add(String.join("；", cells));
			}
			if (table.path("data").size() > 20) rows.add("仅展示前20行，共" + table.path("data").size() + "行；完整数据已保存。");
			return String.join("\n", rows);
		}
		catch (Exception ex) {
			return "结果已保存，请展开查询依据查看。";
		}
	}

	private String renderPlanFacts(SemanticBlueprint plan) {
		if (plan == null) {
			return "";
		}
		List<String> facts = new ArrayList<>();
		plan.getMetrics().forEach(metric -> facts.add("指标=" + preferred(metric.getBusinessName(), metric.getMetricCode())));
		plan.getDimensions()
			.forEach(dimension -> facts.add("维度=" + preferred(dimension.getBusinessName(), dimension.getDimensionCode())));
		if (plan.getTimeRange() != null && StringUtils.hasText(plan.getTimeRange().getTimeColumn())) {
			facts.add("时间字段=" + plan.getTimeRange().getTimeColumn());
			if (StringUtils.hasText(plan.getTimeRange().getRelativeExpression())) {
				facts.add("时间范围=" + plan.getTimeRange().getRelativeExpression());
			}
		}
		plan.getRules().forEach(rule -> facts.add("规则=" + preferred(rule.getBusinessName(), rule.getRuleCode())));
		return String.join("；", facts);
	}

	private String preferred(String businessName, String code) {
		return StringUtils.hasText(businessName) ? businessName.trim() : code;
	}

	private String bounded(String value) {
		String text = value == null ? "" : value.trim();
		if (text.length() <= MAX_RESULT_CHARS_PER_TASK) {
			return text;
		}
		return text.substring(0, MAX_RESULT_CHARS_PER_TASK) + "…（结果已截断，完整内容保存在持久化 Run 中）";
	}
}
