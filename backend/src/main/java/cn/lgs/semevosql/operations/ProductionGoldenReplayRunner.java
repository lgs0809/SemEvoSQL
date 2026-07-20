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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.GoldenReplayMode;
import cn.lgs.semevosql.evolution.GoldenReplayResultValidator;
import cn.lgs.semevosql.evolution.GoldenReplayResultValidator.AssertionReport;
import cn.lgs.semevosql.evolution.ReplayDatasetVersionResolver;
import cn.lgs.semevosql.evolution.SemanticReplayExecutor;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.application.SemanticCatalogFingerprint;
import cn.lgs.semevosql.semantic.application.SemanticPlanningClarificationRequiredException;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningRequest;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningResult;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.DatabaseUtil;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Runs production Golden Cases through the same governed semantic planning, deterministic
 * compiler and guarded replay execution used by SemEvoSQL runtime. This deliberately does
 * not depend on benchmark-only beans or feature flags.
 */
@Service
@RequiredArgsConstructor
public class ProductionGoldenReplayRunner {

	private static final int RECALL_LIMIT = 12;

	private static final int EXAMPLE_LIMIT = 5;

	private final JdbcTemplate jdbc;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticBlueprintPipeline planningPipeline;

	private final SemanticSqlCompiler sqlCompiler;

	private final SemanticReplayExecutor replayExecutor;

	private final DatabaseUtil databaseUtil;

	private final GoldenReplayResultValidator resultValidator;

	private final ReplayDatasetVersionResolver datasetVersionResolver;

	private final VersionedJson versionedJson = new VersionedJson();

	public Map<String, Object> replay(Long projectId, Long versionId) {
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, versionId);
		String catalogHash = SemanticCatalogFingerprint.fingerprint(catalog);
		List<Map<String, Object>> cases = jdbc.queryForList(
				"SELECT * FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE ORDER BY case_code", projectId);
		int passed = 0;
		List<Map<String, Object>> failures = new ArrayList<>();
		List<Map<String, Object>> proofs = new ArrayList<>();
		for (Map<String, Object> golden : cases) {
			String caseCode = Objects.toString(golden.get("case_code"), "");
			String question = Objects.toString(golden.get("question"), "");
			Map<String, Object> expected = versionedJson.readMap(Objects.toString(golden.get("expected_json"), "{}"),
					JsonPayloadRegistry.GOLDEN_CASE_EXPECTED);
			GoldenReplayMode replayMode = GoldenReplayMode.from(golden.get("replay_mode"));
			String expectedOutcome = expectedOutcome(expected);
			try {
				if (replayMode == GoldenReplayMode.FIXTURE) {
					datasetVersionResolver.requireMatch(projectId, Objects.toString(golden.get("dataset_version"), null));
				}
				PlanningResult planning = planningPipeline.plan(new PlanningRequest(projectId, versionId, catalogHash, question,
						List.of(), QueryCaseHints.empty(), RECALL_LIMIT, EXAMPLE_LIMIT));
				SemanticBlueprint plan = planning.plan();
				List<String> errors = new ArrayList<>(planErrors(expected, plan));
				CompiledSemanticQuery compiled = null;
				SemanticReplayExecutor.ReplayExecution execution = null;
				if (errors.isEmpty() && Set.of("REQUIRE_REVIEW", "CONSTRAINED_GENERATION_REQUIRED").contains(expectedOutcome)) {
					if (!"DETERMINISTIC".equalsIgnoreCase(plan.getCompilerMode())) {
						proofs.add(proof(caseCode, expectedOutcome, planning, null, null, List.of()));
						passed++;
						continue;
					}
					errors.add("Expected outcome " + expectedOutcome
							+ " was not observed; deterministic compilation was selected");
				}
				if (errors.isEmpty() && Set.of("REQUIRE_CLARIFICATION", "OUT_OF_SCOPE", "PERMISSION_DENIED")
					.contains(expectedOutcome)) {
					errors.add("Expected outcome " + expectedOutcome + " was not observed; semantic planning resolved the query");
				}
				if (errors.isEmpty()) {
					try {
						compiled = sqlCompiler.compile(plan, catalog, dialects(plan), Clock.systemUTC(), ZoneId.of("UTC"));
						execution = replayExecutor.executeDetailed(projectId, catalog, plan, compiled.sources(),
								"production-golden:" + caseCode);
					}
					catch (RuntimeException executionFailure) {
						if (matchesExpectedExecutionFailure(expectedOutcome, expected, executionFailure)) {
							passed++;
							proofs.add(Map.of("caseCode", caseCode, "status", "PASSED", "expectedOutcome",
									expectedOutcome, "observedError", message(executionFailure)));
							continue;
						}
						throw executionFailure;
					}
					if (!"SUCCEED".equals(expectedOutcome)) {
						errors.add("Expected outcome " + expectedOutcome + " but governed replay succeeded");
					}
					else {
						AssertionReport assertions = resultValidator.validate(expected, execution.finalResult(), execution.latencyMs(),
								execution.estimatedRows(), replayMode);
						errors.addAll(assertions.errors());
					}
				}
				proofs.add(proof(caseCode, expectedOutcome, planning, compiled, execution, errors));
				if (errors.isEmpty()) {
					passed++;
				}
				else {
					failures.add(Map.of("caseCode", caseCode, "errors", List.copyOf(errors)));
				}
			}
			catch (RuntimeException ex) {
				String error = message(ex);
				if (matchesExpectedFailure(expectedOutcome, expected, ex)) {
					passed++;
					proofs.add(Map.of("caseCode", caseCode, "status", "PASSED", "expectedOutcome", expectedOutcome,
							"observedError", error));
				}
				else {
					failures.add(Map.of("caseCode", caseCode, "errors", List.of(error)));
					proofs.add(Map.of("caseCode", caseCode, "status", "FAILED", "expectedOutcome", expectedOutcome,
							"errors", List.of(error)));
				}
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("replayMode", "REAL_SEMANTIC_EXECUTION");
		result.put("catalogHash", catalogHash);
		result.put("total", cases.size());
		result.put("passed", passed);
		result.put("failed", cases.size() - passed);
		result.put("failures", List.copyOf(failures));
		result.put("proofs", List.copyOf(proofs));
		return java.util.Collections.unmodifiableMap(result);
	}

	private Map<Integer, SqlDialect> dialects(SemanticBlueprint plan) {
		Map<Integer, SqlDialect> dialects = new LinkedHashMap<>();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			Integer datasourceId = source.getDatasourceId();
			if (datasourceId == null || dialects.containsKey(datasourceId)) {
				continue;
			}
			DbConfigBO config = databaseUtil.getDatasourceDbConfig(datasourceId);
			dialects.put(datasourceId, SqlDialect.from(config.getDialectType()));
		}
		return Map.copyOf(dialects);
	}

	private List<String> planErrors(Map<String, Object> expected, SemanticBlueprint plan) {
		List<String> errors = new ArrayList<>(plan.getValidationErrors() == null ? List.of() : plan.getValidationErrors());
		if (!plan.isExecutable()) {
			errors.add("Golden semantic plan is not executable");
		}
		assertIncludes("model", strings(expected.get("modelCodes")),
				plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).collect(java.util.stream.Collectors.toSet()),
				errors);
		assertIncludes("metric", strings(expected.get("metricCodes")),
				plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).collect(java.util.stream.Collectors.toSet()),
				errors);
		assertIncludes("dimension", strings(expected.get("dimensionCodes")),
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(java.util.stream.Collectors.toSet()), errors);
		String expectedTimeColumn = text(expected.get("timeColumn"));
		if (StringUtils.hasText(expectedTimeColumn)) {
			String actual = plan.getTimeRange() == null ? null : plan.getTimeRange().getTimeColumn();
			if (!Objects.equals(expectedTimeColumn, actual)) {
				errors.add("Expected timeColumn " + expectedTimeColumn + " but got " + actual);
			}
		}
		String expectedStart = text(expected.get("timeStartInclusive"));
		if (StringUtils.hasText(expectedStart)) {
			String actual = plan.getTimeRange() == null ? null : plan.getTimeRange().getStartInclusive();
			if (!sameTimeBoundary(expectedStart, actual)) {
				errors.add("Expected timeStartInclusive " + expectedStart + " but got " + actual);
			}
		}
		String expectedEnd = text(expected.get("timeEndExclusive"));
		if (StringUtils.hasText(expectedEnd)) {
			String actual = plan.getTimeRange() == null ? null : plan.getTimeRange().getEndExclusive();
			if (!sameTimeBoundary(expectedEnd, actual)) {
				errors.add("Expected timeEndExclusive " + expectedEnd + " but got " + actual);
			}
		}
		String expectedOrderExpression = text(expected.get("orderExpression"));
		String expectedOrderDirection = text(expected.get("orderDirection"));
		if (StringUtils.hasText(expectedOrderExpression) || StringUtils.hasText(expectedOrderDirection)) {
			SemanticBlueprint.OrderSelection actual = plan.getOrderBy().isEmpty() ? null : plan.getOrderBy().get(0);
			if (actual == null) {
				errors.add("Expected ordered result but plan has no orderBy");
			}
			else {
				if (StringUtils.hasText(expectedOrderExpression)
						&& !Objects.equals(expectedOrderExpression, actual.getExpression())) {
					errors.add("Expected order expression " + expectedOrderExpression + " but got " + actual.getExpression());
				}
				if (StringUtils.hasText(expectedOrderDirection)
						&& !Objects.equals(expectedOrderDirection.toUpperCase(), Objects.toString(actual.getDirection(), "").toUpperCase())) {
					errors.add("Expected order direction " + expectedOrderDirection + " but got " + actual.getDirection());
				}
			}
		}
		return errors;
	}

	/** Use the compiler's local-time contract; spelling differences are not time drift. */
	static boolean sameTimeBoundary(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		try {
			return LocalDateTime.parse(expected).equals(LocalDateTime.parse(actual));
		}
		catch (DateTimeParseException invalidBoundary) {
			return false;
		}
	}

	private Map<String, Object> proof(String caseCode, String expectedOutcome, PlanningResult planning,
			CompiledSemanticQuery compiled, SemanticReplayExecutor.ReplayExecution execution, List<String> errors) {
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("caseCode", caseCode);
		proof.put("status", errors.isEmpty() ? "PASSED" : "FAILED");
		proof.put("expectedOutcome", expectedOutcome);
		proof.put("planningId", planning.trace().planningId());
		proof.put("retrievalHits", planning.trace().retrievalHitCount());
		proof.put("modelCallCount", planning.trace().modelCallCount());
		proof.put("nativeReasoningUsed", planning.trace().nativeReasoningUsed());
		proof.put("sourceCount", compiled == null ? 0 : compiled.sources().size());
		proof.put("sqlPresent", compiled != null && compiled.sources().stream().allMatch(source -> StringUtils.hasText(source.sql())));
		proof.put("sourceQueries", compiled == null ? List.of() : compiled.sources().stream().map(source ->
				Map.of("datasourceId", source.datasourceId(), "sql", source.sql(), "parameters", source.parameters())).toList());
		proof.put("executionProof", execution == null ? List.of() : execution.proof());
		proof.put("resultRowCount",
				execution == null || execution.finalResult() == null || execution.finalResult().getData() == null ? 0
						: execution.finalResult().getData().size());
		proof.put("latencyMs", execution == null ? null : execution.latencyMs());
		proof.put("estimatedRows", execution == null ? null : execution.estimatedRows());
		proof.put("errors", List.copyOf(errors));
		return java.util.Collections.unmodifiableMap(proof);
	}

	private String expectedOutcome(Map<String, Object> expected) {
		String outcome = Objects.toString(expected.get("expectedOutcome"), "").trim().toUpperCase(Locale.ROOT);
		if (!outcome.isEmpty()) {
			return outcome;
		}
		if (Boolean.TRUE.equals(expected.get("expectedReviewRequired"))) {
			return "REQUIRE_REVIEW";
		}
		if (StringUtils.hasText(text(expected.get("expectedClarificationType")))) {
			return "REQUIRE_CLARIFICATION";
		}
		return "SUCCEED";
	}

	private boolean matchesExpectedFailure(String expectedOutcome, Map<String, Object> expected, RuntimeException error) {
		if ("REQUIRE_CLARIFICATION".equals(expectedOutcome)
				&& error instanceof SemanticPlanningClarificationRequiredException clarificationError) {
			String expectedType = text(expected.get("expectedClarificationType"));
			if (!StringUtils.hasText(expectedType)) {
				return true;
			}
			var clarification = clarificationError.clarification();
			if (clarification == null) {
				return false;
			}
			String normalizedExpected = expectedType.trim().toUpperCase(Locale.ROOT);
			if (Objects.toString(clarification.issueType(), "").toUpperCase(Locale.ROOT).contains(normalizedExpected)) {
				return true;
			}
			return clarification.options()
				.stream()
				.anyMatch(option -> normalizedExpected.equals(Objects.toString(option.assetType(), "").toUpperCase(Locale.ROOT)));
		}
		String observed = message(error).toUpperCase(Locale.ROOT);
		return switch (expectedOutcome) {
			case "REQUIRE_CLARIFICATION" -> containsAny(observed, "CLARIF", "AMBIGU", "澄清", "歧义");
			case "OUT_OF_SCOPE" -> containsAny(observed, "OUT_OF_SCOPE", "OUT OF SCOPE", "超出");
			case "PERMISSION_DENIED" -> containsAny(observed, "PERMISSION", "DENIED", "FORBIDDEN", "权限");
			case "REQUIRE_REVIEW", "CONSTRAINED_GENERATION_REQUIRED" ->
				containsAny(observed, "REVIEW", "CONSTRAINED", "审核");
			default -> false;
		};
	}

	private boolean matchesExpectedExecutionFailure(String expectedOutcome, Map<String, Object> expected,
			RuntimeException error) {
		if (!"REJECT_BY_GUARD".equals(expectedOutcome)) {
			return false;
		}
		String expectedCode = text(expected.get("expectedErrorCode"));
		String observed = error.getClass().getSimpleName() + ":" + message(error);
		return !StringUtils.hasText(expectedCode)
				|| observed.toUpperCase(Locale.ROOT).contains(expectedCode.toUpperCase(Locale.ROOT));
	}

	private boolean containsAny(String value, String... tokens) {
		return java.util.Arrays.stream(tokens).anyMatch(value::contains);
	}

	private String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return Objects.toString(current.getMessage(), current.getClass().getSimpleName());
	}

	private void assertIncludes(String kind, Set<String> expected, Set<String> actual, List<String> errors) {
		for (String value : expected) {
			if (!actual.contains(value)) {
				errors.add("Missing expected " + kind + " " + value);
			}
		}
	}

	private Set<String> strings(Object value) {
		if (!(value instanceof List<?> list)) {
			return Set.of();
		}
		LinkedHashSet<String> values = new LinkedHashSet<>();
		for (Object item : list) {
			String text = text(item);
			if (StringUtils.hasText(text)) {
				values.add(text);
			}
		}
		return Set.copyOf(values);
	}

	private String text(Object value) {
		return value == null ? null : Objects.toString(value).trim();
	}

}
