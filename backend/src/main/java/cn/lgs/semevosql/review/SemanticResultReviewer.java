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
package cn.lgs.semevosql.review;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.PostExecutionReview.IssueType;
import cn.lgs.semevosql.review.PostExecutionReview.ModelEvidence;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.run.RunDeadlineUtil;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Constrained semantic acceptance reviewer. It can classify a result but cannot author SQL or Catalog changes. */
@Service
@RequiredArgsConstructor
public class SemanticResultReviewer {

	private static final String SYSTEM_PROMPT = """
			You are SemEvoSQL's governed post-execution semantic reviewer.
			You validate whether the executed result is semantically compatible with the user's question and the supplied
			governed Semantic Blueprint. The Semantic Catalog facts embedded in the plan are authoritative.

			STRICT RULES:
			1. Never generate, rewrite or suggest SQL. Never propose a Semantic Catalog, alias or prompt change.
			2. Never introduce a metric, dimension, rule, relationship, grain, filter, enum value or definition that is not
			   already present in the supplied Semantic Blueprint.
			3. Treat deterministicErrors as authoritative: if they are non-empty you MUST NOT return PASS.
			4. RETRY_SQL means the governed semantic binding and current execution strategy still appear correct, but the
			   current SQL implementation is repairable in-place.
			5. REPLAN_EXECUTION means the governed semantic binding still appears correct, but the supplied execution plan or
			   decomposition strategy is materially wrong. Use it when changing only the current SQL text is unlikely to be
			   sufficient, for example when a one-step strategy should become multiple dependent steps or vice versa.
			6. REBIND_SEMANTIC means the selected governed metric/dimension/rule/relationship/grain/filter binding itself is
			   materially suspect while the required governed evidence was already recalled.
			7. RERETRIEVE is only for RETRIEVAL_MISS: the governed definition is believed to exist, but the recalled candidate
			   set was insufficient. Do not invent the missing binding; evidence should only describe what to search for.
			8. CLARIFY means user input is required, including DEFINITION_GAP where the Catalog itself lacks a safe definition.
			9. FAIL means the request/result cannot be safely repaired within the supplied governed facts.
			10. Validate result-grain minimality against the current question: every returned dimension, grouping, bucket,
			   ordering breakdown, and non-metric projection must be justified by what the user asked to see. Extra semantic
			   structure is a semantic mismatch even when the selected governed metric itself is correct; use REBIND_SEMANTIC
			   when that extra structure comes from the governed semantic binding, and REPLAN_EXECUTION when it comes from the
			   execution plan/decomposition.
			11. A deterministic warning such as NULLABLE_COLUMN_REFERENCED is a schema fact, not an automatic filter rule.
			   Judge NULL handling from the actual question, execution plan, SQL and result. Never require blanket IS NOT NULL.
			   For temporal grouping/window/order/comparison, a NULL time bucket or NULL temporal ordering is a SQL-repair issue
			   only when it contradicts the requested chronological analysis. If the user explicitly asks about missing/unpaid/
			   NULL values or wants them as a category, preserving NULL is correct. Treat multiple nullable time columns independently.
			12. When advancedExecution=true, the executionPlan owns physical result shape, ordering, windowing and row-count strategy.
			   The semanticPlan payload intentionally omits planner-owned orderBy/limit/expectedResult. Do not reconstruct or enforce
			   those omitted fields from historical expectations; validate governed metrics/dimensions/relationships/rules instead.
			   Planner-owned derived output columns such as LAG/LEAD values, period deltas, ranks, bucket aliases or comparison columns
			   are allowed even when they are not direct Semantic Blueprint projections. If deterministicErrors is empty and those
			   derived columns implement an explicit executionPlan/user requirement using governed inputs, do NOT reject them as extra
			   semantic structure or RESULT_SHAPE_MISMATCH.
			13. Result transport may serialize a SQL NULL cell as an empty string. Do not reject a result solely because an empty
			   string appears where SQL semantics (for example the first LAG row) naturally produce NULL; inspect executedSql and intent.
			14. REPORT_GENERATOR_NODE is the normal final presentation step after successful data execution. Its presence does not
			   add a data result set, grouping, dimension or metric and MUST NOT by itself be treated as RESULT_SHAPE_MISMATCH or
			   REPLAN_EXECUTION. Judge physical result shape from executedSql/resultSample and data-producing execution steps only.
			15. suspectedAssetKeys may contain only keys from allowedAssetKeys.
			16. evidence contains only short observable facts, never hidden reasoning or chain-of-thought.
			17. Return exactly one JSON object and no Markdown. Do not add SQL or patch fields.

			Schema:
			{"decision":"PASS|RETRY_SQL|REPLAN_EXECUTION|REBIND_SEMANTIC|RERETRIEVE|CLARIFY|FAIL","issueType":"NONE|RESULT_SHAPE_MISMATCH|RESULT_DOMAIN_VIOLATION|RESULT_SEMANTIC_MISMATCH|SEMANTIC_BINDING_SUSPECTED|SQL_REPAIRABLE|RETRIEVAL_MISS|DEFINITION_GAP|AMBIGUITY|POLICY_FATAL","confidence":0.0,"suspectedAssetKeys":[],"evidence":[]}
			""";

	private final SemanticDocumentExtractionClient extractionClient;

	private final PostExecutionReviewProperties properties;

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			List<String> deterministicErrors, List<String> deterministicWarnings) {
		return review(question, plan, sql, resultSet, "", deterministicErrors, deterministicWarnings, false);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			String executionPlan, List<String> deterministicErrors, List<String> deterministicWarnings) {
		return review(question, plan, sql, resultSet, executionPlan, deterministicErrors, deterministicWarnings, false);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			String executionPlan, List<String> deterministicErrors, List<String> deterministicWarnings,
			boolean advancedExecution) {
		return review(question, plan, sql, resultSet, executionPlan, deterministicErrors, deterministicWarnings,
				advancedExecution, null);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			String executionPlan, List<String> deterministicErrors, List<String> deterministicWarnings,
			boolean advancedExecution, Long runDeadlineEpochMillis) {
		Set<String> allowedAssetKeys = allowedAssetKeys(plan);
		String prompt = prompt(question, plan, sql, resultSet, executionPlan, deterministicErrors, deterministicWarnings,
				allowedAssetKeys, advancedExecution);
		ModelCallResult call = runDeadlineEpochMillis == null
				? extractionClient.complete(ModelCallPurpose.SEMANTIC_RESULT_REVIEW, SYSTEM_PROMPT, prompt)
				: extractionClient.complete(ModelCallPurpose.SEMANTIC_RESULT_REVIEW, SYSTEM_PROMPT, prompt,
						RunDeadlineUtil.remaining(runDeadlineEpochMillis));
		JsonNode root = parseObject(call.response());
		assertNoForbiddenOutput(root);
		Decision decision = enumValue(Decision.class, requiredText(root, "decision"), "decision");
		IssueType issueType = enumValue(IssueType.class, requiredText(root, "issueType"), "issueType");
		if (deterministicErrors != null && !deterministicErrors.isEmpty() && decision == Decision.PASS) {
			throw new IllegalArgumentException("Semantic reviewer cannot override deterministic errors with PASS");
		}
		Set<String> suspected = textSet(root.path("suspectedAssetKeys"));
		if (!allowedAssetKeys.containsAll(suspected)) {
			Set<String> invalid = new LinkedHashSet<>(suspected);
			invalid.removeAll(allowedAssetKeys);
			throw new IllegalArgumentException("Semantic reviewer returned non-governed asset keys: " + invalid);
		}
		List<String> evidence = textList(root.path("evidence"), 12, 240);
		double confidence = root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.0d;
		ModelEvidence modelEvidence = new ModelEvidence(call.callId(), call.latencyMs(), call.promptTokens(),
				call.completionTokens());
		return new PostExecutionReview(decision, issueType, confidence, suspected, evidence, deterministicErrors,
				deterministicWarnings, true, modelEvidence);
	}

	private String prompt(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet, String executionPlan,
			List<String> deterministicErrors, List<String> deterministicWarnings, Set<String> allowedAssetKeys,
			boolean advancedExecution) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("question", question);
		payload.put("semanticPlan", reviewerPlan(plan, advancedExecution));
		payload.put("advancedExecution", advancedExecution);
		payload.put("executionPlan", StringUtils.hasText(executionPlan) ? executionPlan : "");
		payload.put("executedSql", sql);
		payload.put("allowedAssetKeys", allowedAssetKeys);
		payload.put("deterministicErrors", deterministicErrors == null ? List.of() : deterministicErrors);
		payload.put("deterministicWarnings", deterministicWarnings == null ? List.of() : deterministicWarnings);
		payload.put("resultSample", boundedResult(resultSet));
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(payload);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize post-execution review payload", ex);
		}
	}

	static Object reviewerPlan(SemanticBlueprint plan, boolean advancedExecution) {
		if (plan == null || !advancedExecution) {
			return plan;
		}
		JsonNode node = JsonUtil.getObjectMapper().valueToTree(plan);
		if (node.isObject()) {
			var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
			object.remove("orderBy");
			object.remove("limit");
			object.remove("expectedResult");
		}
		return node;
	}

	private Map<String, Object> boundedResult(ResultSetBO resultSet) {
		if (resultSet == null) {
			return Map.of("columns", List.of(), "rows", List.of(), "rowCount", 0);
		}
		List<String> columns = resultSet.getColumn() == null ? List.of()
				: resultSet.getColumn().stream().limit(Math.max(1, properties.getSampleColumns())).toList();
		List<Map<String, String>> rows = new ArrayList<>();
		if (resultSet.getData() != null) {
			for (Map<String, String> row : resultSet.getData().stream().limit(Math.max(1, properties.getSampleRows())).toList()) {
				Map<String, String> bounded = new LinkedHashMap<>();
				for (String column : columns) {
					if (row != null && row.containsKey(column)) {
						bounded.put(column, row.get(column));
					}
				}
				rows.add(bounded);
			}
		}
		return Map.of("columns", columns, "rows", rows, "rowCount",
				resultSet.getData() == null ? 0 : resultSet.getData().size());
	}

	private Set<String> allowedAssetKeys(SemanticBlueprint plan) {
		Set<String> keys = new LinkedHashSet<>();
		if (plan == null) {
			return keys;
		}
		plan.getModels().forEach(value -> addKey(keys, "model", value.getModelCode()));
		plan.getMetrics().forEach(value -> addKey(keys, "metric", value.getMetricCode()));
		plan.getDimensions().forEach(value -> addKey(keys, "dimension", value.getDimensionCode()));
		plan.getRules().forEach(value -> addKey(keys, "rule", value.getRuleCode()));
		plan.getRelationships().forEach(value -> addKey(keys, "relationship", value.getRelationshipCode()));
		plan.getGrains().forEach(value -> addKey(keys, "grain", value.getGrainCode()));
		return Set.copyOf(keys);
	}

	private void addKey(Set<String> keys, String type, String code) {
		if (StringUtils.hasText(code)) {
			keys.add(type + ":" + code);
		}
	}

	private JsonNode parseObject(String response) {
		String value = response == null ? "" : response.trim();
		if (value.startsWith("```")) {
			int firstNewline = value.indexOf('\n');
			int lastFence = value.lastIndexOf("```");
			if (firstNewline >= 0 && lastFence > firstNewline) {
				value = value.substring(firstNewline + 1, lastFence).trim();
			}
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(value);
			if (root == null || !root.isObject()) {
				throw new IllegalArgumentException("Semantic reviewer must return one JSON object");
			}
			return root;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Semantic reviewer returned invalid JSON", ex);
		}
	}

	private void assertNoForbiddenOutput(JsonNode root) {
		for (String field : List.of("sql", "rewrittenSql", "catalogPatch", "semanticCatalogPatch", "promptPatch",
				"aliasPatch", "chainOfThought", "reasoning")) {
			if (root.has(field)) {
				throw new IllegalArgumentException("Semantic reviewer returned forbidden field: " + field);
			}
		}
	}

	private String requiredText(JsonNode root, String field) {
		String value = root.path(field).asText(null);
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Semantic reviewer field is required: " + field);
		}
		return value.trim();
	}

	private <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Semantic reviewer returned unsupported " + field + ": " + value, ex);
		}
	}

	private Set<String> textSet(JsonNode node) {
		return Set.copyOf(textList(node, 24, 200));
	}

	private List<String> textList(JsonNode node, int maxItems, int maxLength) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return List.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("Semantic reviewer list field must be an array");
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
				throw new IllegalArgumentException("Semantic reviewer list values must be non-blank strings");
			}
			String value = item.asText().trim();
			values.add(value.length() <= maxLength ? value : value.substring(0, maxLength));
			if (values.size() >= maxItems) {
				break;
			}
		}
		return List.copyOf(values);
	}

}
