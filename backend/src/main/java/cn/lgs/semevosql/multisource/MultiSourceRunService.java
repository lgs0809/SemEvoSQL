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
package cn.lgs.semevosql.multisource;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiSourceRunService {

	private final JdbcTemplate jdbcTemplate;

	private final MultiSourceMergeEngine mergeEngine;

	private final QueryRunService runService;

	private final RunExecutionFenceService executionFence;

	@Transactional
	public MultiSourceRunView initialize(String runId, String executionKey, Long projectId, Long versionId,
			SemanticBlueprint plan) {
		return initialize(runId, executionKey, projectId, versionId, plan, null);
	}

	@Transactional
	public MultiSourceRunView initialize(String runId, String executionKey, Long projectId, Long versionId,
			SemanticBlueprint plan, String attemptId) {
		assertActive(runId, attemptId);
		if (plan == null || !plan.isExecutable()) {
			throw new IllegalArgumentException("An executable Semantic Blueprint is required");
		}
		if (plan.getSourceSubPlans() == null || plan.getSourceSubPlans().isEmpty()) {
			throw new IllegalArgumentException("Semantic Blueprint has no source subplans");
		}
		String scope = requiredExecutionKey(executionKey);
		MultiSourceRunView existing = find(runId, scope).orElse(null);
		if (existing != null) {
			return existing;
		}
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			String subRunId = UUID.randomUUID().toString();
			jdbcTemplate.update("""
					INSERT INTO qw_source_sub_run
					(sub_run_id, run_id, execution_key, project_id, project_version_id, datasource_id, status, source_plan_json)
					VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?)
					""", subRunId, runId, scope, projectId, versionId, source.getDatasourceId(), json(source));
		}
		String mergeId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO qw_merge_execution
				(merge_id, run_id, execution_key, project_id, project_version_id, policy_code, policy_json, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", mergeId, runId, scope, projectId, versionId,
				plan.getMergePlan() == null ? null : plan.getMergePlan().getPolicyCode(),
				plan.getMergePlan() == null ? null : json(plan.getMergePlan()),
				plan.getSourceSubPlans().size() > 1 ? "WAITING_SOURCES" : "NOT_REQUIRED");
		runService.appendEvent(runId, "SOURCE_SUBRUNS_CREATED", "multi-source-plan", json(plan.getSourceSubPlans()),
				"Created " + plan.getSourceSubPlans().size() + " source subruns for execution " + scope,
				"source-plan:" + runId + ":" + scope);
		return get(runId, scope);
	}

	@Transactional
	public SourceSubRun startSource(String runId, String subRunId, String sqlText) {
		return startSource(runId, subRunId, sqlText, null);
	}

	@Transactional
	public SourceSubRun startSource(String runId, String subRunId, String sqlText, String attemptId) {
		assertActive(runId, attemptId);
		SourceSubRun subRun = requireSubRun(runId, subRunId);
		if (subRun.status() == SourceSubRunStatus.COMPLETED || subRun.status() == SourceSubRunStatus.FAILED
				|| subRun.status() == SourceSubRunStatus.CANCELLED) {
			return subRun;
		}
		String normalizedSql = sqlText == null || sqlText.isBlank() ? subRun.sqlText() : sqlText.trim();
		if (subRun.status() == SourceSubRunStatus.RUNNING && Objects.equals(subRun.sqlText(), normalizedSql)) {
			return subRun;
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_source_sub_run
				SET status = 'RUNNING', sql_text = ?, error_message = NULL,
				    revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE sub_run_id = ? AND run_id = ? AND revision = ? AND status IN ('QUEUED', 'RUNNING')
				""", normalizedSql, subRunId, runId, subRun.revision());
		if (updated != 1) {
			throw new IllegalStateException("Source subrun changed concurrently: " + subRunId);
		}
		SourceSubRun current = requireSubRun(runId, subRunId);
		runService.appendEvent(runId, "SOURCE_SUBRUN_RUNNING", "source-subrun", current.sourcePlanJson(),
				"Datasource " + current.datasourceId() + " source execution started", "source-running:" + subRunId);
		return current;
	}

	@Transactional
	public ResultArtifact completeSource(String runId, String subRunId, String sqlText, ResultSetBO result,
			String freshnessAsOf) {
		return completeSource(runId, subRunId, sqlText, result, freshnessAsOf, null);
	}

	@Transactional
	public ResultArtifact completeSource(String runId, String subRunId, String sqlText, ResultSetBO result,
			String freshnessAsOf, String attemptId) {
		assertActive(runId, attemptId);
		SourceSubRun subRun = requireSubRun(runId, subRunId);
		if (subRun.status() == SourceSubRunStatus.COMPLETED && subRun.resultArtifactId() != null) {
			return requireArtifact(subRun.resultArtifactId());
		}
		if (result == null) {
			throw new IllegalArgumentException("Source result is required");
		}
		String artifactId = UUID.randomUUID().toString();
		String schemaJson = json(result.getColumn() == null ? List.of() : result.getColumn());
		String dataJson = json(result.getData() == null ? List.of() : result.getData());
		long rowCount = result.getData() == null ? 0 : result.getData().size();
		try {
			jdbcTemplate.update("""
					INSERT INTO qw_result_artifact
					(artifact_id, run_id, source_sub_run_id, artifact_type, schema_json, data_json, row_count,
					 content_hash, status)
					VALUES (?, ?, ?, 'SOURCE_RESULT', ?, ?, ?, ?, 'READY')
					""", artifactId, runId, subRunId, schemaJson, dataJson, rowCount,
					sha256(schemaJson + "\n" + dataJson));
		}
		catch (DuplicateKeyException ex) {
			SourceSubRun reloaded = requireSubRun(runId, subRunId);
			if (reloaded.resultArtifactId() != null) {
				return requireArtifact(reloaded.resultArtifactId());
			}
			throw ex;
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_source_sub_run
				SET status = 'COMPLETED', sql_text = ?, result_artifact_id = ?, row_count = ?, freshness_as_of = ?,
				    error_message = NULL, finish_time = CURRENT_TIMESTAMP, revision = revision + 1,
				    update_time = CURRENT_TIMESTAMP
				WHERE sub_run_id = ? AND run_id = ? AND revision = ? AND status IN ('QUEUED', 'RUNNING')
				""", sqlText, artifactId, rowCount, freshnessAsOf, subRunId, runId, subRun.revision());
		if (updated != 1) {
			throw new IllegalStateException("Source subrun changed concurrently: " + subRunId);
		}
		runService.appendEvent(runId, "SOURCE_SUBRUN_COMPLETED", "source-subrun", json(result),
				"Datasource " + subRun.datasourceId() + " completed with " + rowCount + " rows",
				"source-complete:" + subRunId);
		return requireArtifact(artifactId);
	}

	@Transactional
	public SourceSubRun failSource(String runId, String subRunId, String errorMessage) {
		return failSource(runId, subRunId, errorMessage, null);
	}

	@Transactional
	public SourceSubRun failSource(String runId, String subRunId, String errorMessage, String attemptId) {
		assertActive(runId, attemptId);
		SourceSubRun subRun = requireSubRun(runId, subRunId);
		if (subRun.status() == SourceSubRunStatus.COMPLETED || subRun.status() == SourceSubRunStatus.FAILED) {
			return subRun;
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_source_sub_run
				SET status = 'FAILED', error_message = ?, finish_time = CURRENT_TIMESTAMP,
				    revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE sub_run_id = ? AND run_id = ? AND revision = ?
				""", errorMessage, subRunId, runId, subRun.revision());
		if (updated != 1) {
			throw new IllegalStateException("Source subrun changed concurrently: " + subRunId);
		}
		runService.appendEvent(runId, "SOURCE_SUBRUN_FAILED", "source-subrun", null,
				"Datasource " + subRun.datasourceId() + " failed: " + errorMessage, "source-failed:" + subRunId);
		return requireSubRun(runId, subRunId);
	}

	@Transactional
	public ResultArtifact merge(String runId, String executionKey) {
		return merge(runId, executionKey, null, null);
	}

	@Transactional
	public ResultArtifact merge(String runId, String executionKey, SemanticBlueprint plan) {
		return merge(runId, executionKey, plan, null);
	}

	@Transactional
	public ResultArtifact merge(String runId, String executionKey, SemanticBlueprint plan, String attemptId) {
		assertActive(runId, attemptId);
		String scope = requiredExecutionKey(executionKey);
		MultiSourceRunView view = get(runId, scope);
		if (view.mergeExecution().status() == MergeStatus.COMPLETED
				&& view.mergeExecution().outputArtifactId() != null) {
			return requireArtifact(view.mergeExecution().outputArtifactId());
		}
		List<SourceSubRun> failed = view.sourceSubRuns()
			.stream()
			.filter(item -> item.status() == SourceSubRunStatus.FAILED)
			.toList();
		if (!failed.isEmpty()) {
			String policy = readMergePolicy(view.mergeExecution()).getPartialFailurePolicy();
			if (!"ALLOW_PARTIAL".equalsIgnoreCase(policy)) {
				throw new IllegalStateException("Source subruns failed and partial results are not allowed: "
						+ failed.stream().map(SourceSubRun::subRunId).toList());
			}
		}
		List<SourceSubRun> completed = view.sourceSubRuns()
			.stream()
			.filter(item -> item.status() == SourceSubRunStatus.COMPLETED)
			.toList();
		if (completed.isEmpty() || completed.size() + failed.size() != view.sourceSubRuns().size()) {
			throw new IllegalStateException("All source subruns must reach a terminal state before merge");
		}
		List<ResultArtifact> inputs = completed.stream()
			.map(SourceSubRun::resultArtifactId)
			.map(this::requireArtifact)
			.toList();
		List<ResultSetBO> results = inputs.stream().map(this::toResultSet).toList();
		MergePolicy policy = readMergePolicy(view.mergeExecution());
		ResultSetBO merged = results.size() == 1 ? results.get(0) : mergeEngine.merge(policy, results);
		merged = shapeMergedResult(merged, plan);
		String artifactId = UUID.randomUUID().toString();
		String schemaJson = json(merged.getColumn() == null ? List.of() : merged.getColumn());
		String dataJson = json(merged.getData() == null ? List.of() : merged.getData());
		long rowCount = merged.getData() == null ? 0 : merged.getData().size();
		jdbcTemplate.update("""
				INSERT INTO qw_result_artifact
				(artifact_id, run_id, source_sub_run_id, artifact_type, schema_json, data_json, row_count,
				 content_hash, status)
				VALUES (?, ?, NULL, 'MERGED_RESULT', ?, ?, ?, ?, 'READY')
				""", artifactId, runId, schemaJson, dataJson, rowCount, sha256(schemaJson + "\n" + dataJson));
		int updated = jdbcTemplate.update("""
				UPDATE qw_merge_execution
				SET status = 'COMPLETED', input_artifacts_json = ?, output_artifact_id = ?, error_message = NULL,
				    finish_time = CURRENT_TIMESTAMP, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND execution_key = ? AND revision = ?
				  AND status IN ('WAITING_SOURCES', 'NOT_REQUIRED', 'RUNNING')
				""", json(inputs.stream().map(ResultArtifact::artifactId).toList()), artifactId, runId, scope,
				view.mergeExecution().revision());
		if (updated != 1) {
			throw new IllegalStateException("Merge execution changed concurrently: " + runId);
		}
		runService.appendEvent(runId, "MERGE_COMPLETED", "multi-source-merge", json(merged),
				"Merged result completed with " + rowCount + " rows for execution " + scope,
				"merge-complete:" + runId + ":" + scope);
		return requireArtifact(artifactId);
	}

	private void assertActive(String runId, String attemptId) {
		if (attemptId == null || attemptId.isBlank()) {
			return;
		}
		executionFence.assertActiveAndLock(runId, attemptId);
	}

	private ResultSetBO shapeMergedResult(ResultSetBO merged, SemanticBlueprint plan) {
		if (merged == null || plan == null || plan.getExpectedResult() == null
				|| plan.getExpectedResult().getColumns() == null || plan.getExpectedResult().getColumns().isEmpty()) {
			return merged;
		}
		List<String> expectedColumns = List.copyOf(plan.getExpectedResult().getColumns());
		List<Map<String, String>> rows = merged.getData() == null ? List.of() : merged.getData();
		if (rows.isEmpty()) {
			return ResultSetBO.builder().column(expectedColumns).data(List.of()).errorMsg(merged.getErrorMsg()).build();
		}
		boolean scalar = "SCALAR".equalsIgnoreCase(Objects.toString(plan.getExpectedResult().getGrain(), ""))
				&& (plan.getGroupBy() == null || plan.getGroupBy().isEmpty());
		if (!scalar || rows.size() == 1) {
			List<Map<String, String>> projected = rows.stream().map(row -> projectRow(row, expectedColumns)).toList();
			return ResultSetBO.builder().column(expectedColumns).data(projected).errorMsg(merged.getErrorMsg()).build();
		}
		Map<String, String> collapsed = new LinkedHashMap<>();
		for (String column : expectedColumns) {
			SemanticBlueprint.MetricSelection metric = plan.getMetrics()
				.stream()
				.filter(item -> Objects.equals(item.getMetricCode(), column))
				.findFirst()
				.orElse(null);
			if (metric == null) {
				throw new IllegalStateException("Cannot safely collapse multi-source scalar column: " + column);
			}
			collapsed.put(column, aggregateMetric(rows, column, metric, plan));
		}
		return ResultSetBO.builder()
			.column(expectedColumns)
			.data(List.of(collapsed))
			.errorMsg(merged.getErrorMsg())
			.build();
	}

	private Map<String, String> projectRow(Map<String, String> row, List<String> expectedColumns) {
		Map<String, String> projected = new LinkedHashMap<>();
		for (String column : expectedColumns) {
			if (row == null || !row.containsKey(column)) {
				throw new IllegalStateException("Merged result is missing expected output column: " + column);
			}
			projected.put(column, row.get(column));
		}
		return projected;
	}

	private String aggregateMetric(List<Map<String, String>> rows, String column,
			SemanticBlueprint.MetricSelection metric, SemanticBlueprint plan) {
		List<BigDecimal> values = rows.stream()
			.map(row -> row == null ? null : row.get(column))
			.filter(Objects::nonNull)
			.filter(value -> !value.isBlank())
			.map(value -> {
				try {
					return new BigDecimal(value);
				}
				catch (NumberFormatException ex) {
					throw new IllegalStateException("Cannot aggregate non-numeric merged metric: " + column, ex);
				}
			})
			.toList();
		if (values.isEmpty()) {
			return null;
		}
		String aggregation = Objects.toString(metric.getAggregation(), "").trim().toUpperCase(java.util.Locale.ROOT);
		BigDecimal result = switch (aggregation) {
			case "SUM", "COUNT" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			case "COUNT_DISTINCT" -> {
				if (!canSafelyRecombineCountDistinct(plan, metric)) {
					throw new IllegalStateException("Multi-source scalar merge cannot safely recombine COUNT_DISTINCT metric: "
							+ metric.getMetricCode() + "; the DISTINCT column must be protected by a single-column UNIQUE grain");
				}
				yield values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			}
			case "MIN" -> values.stream().min(BigDecimal::compareTo).orElseThrow();
			case "MAX" -> values.stream().max(BigDecimal::compareTo).orElseThrow();
			default -> throw new IllegalStateException(
					"Multi-source scalar merge cannot safely recombine metric aggregation: " + aggregation);
		};
		return result.stripTrailingZeros().toPlainString();
	}

	static boolean canSafelyRecombineCountDistinct(SemanticBlueprint plan, SemanticBlueprint.MetricSelection metric) {
		if (plan == null || metric == null || metric.getExpression() == null || metric.getModelCode() == null) {
			return false;
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("(?i)^\\s*COUNT\\s*\\(\\s*DISTINCT\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*$")
			.matcher(metric.getExpression());
		if (!matcher.matches()) {
			return false;
		}
		String distinctColumn = matcher.group(1);
		String normalizedUniqueRule = "UNIQUE(" + distinctColumn + ")";
		return plan.getGrains() != null && plan.getGrains().stream().anyMatch(grain -> {
			if (grain == null || !Objects.equals(metric.getModelCode(), grain.getModelCode())) {
				return false;
			}
			String keys = Objects.toString(grain.getKeyColumns(), "").replaceAll("\\s+", "");
			String uniqueness = Objects.toString(grain.getUniquenessRule(), "").replaceAll("\\s+", "");
			return keys.equalsIgnoreCase(distinctColumn) && uniqueness.equalsIgnoreCase(normalizedUniqueRule);
		});
	}

	public MultiSourceRunView get(String runId, String executionKey) {
		return find(runId, executionKey)
			.orElseThrow(() -> new IllegalArgumentException("Multi-source execution not found: " + runId + "/" + executionKey));
	}

	public Optional<ResultArtifact> mergedArtifact(String runId) {
		Optional<ResultArtifact> merged = jdbcTemplate.query("""
				SELECT output_artifact_id FROM qw_merge_execution
				WHERE run_id = ? AND status = 'COMPLETED' AND output_artifact_id IS NOT NULL
				ORDER BY update_time DESC, merge_id DESC LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), runId).stream().findFirst().map(this::requireArtifact);
		if (merged.isPresent()) {
			return merged;
		}
		return jdbcTemplate.query("""
				SELECT * FROM qw_result_artifact
				WHERE run_id = ? AND source_sub_run_id IS NULL AND artifact_type = 'DIRECT_RESULT' AND status = 'READY'
				ORDER BY update_time DESC, artifact_id DESC LIMIT 1
				""", ARTIFACT_MAPPER, runId).stream().findFirst();
	}

	/**
	 * Persists a single-source result only after the post-execution review has accepted
	 * it. The attempt is part of the deterministic artifact identity so a replay is
	 * idempotent while a replacement attempt cannot overwrite an earlier result.
	 */
	@Transactional
	public ResultArtifact persistDirectResult(String runId, ResultSetBO result, String attemptId) {
		assertActive(runId, attemptId);
		if (result == null) {
			throw new IllegalArgumentException("Direct result is required");
		}
		String identity = runId + ":" + Objects.toString(attemptId, "unbound") + ":direct-result";
		String artifactId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
		String schemaJson = json(result.getColumn() == null ? List.of() : result.getColumn());
		String dataJson = json(result.getData() == null ? List.of() : result.getData());
		long rowCount = result.getData() == null ? 0 : result.getData().size();
		try {
			jdbcTemplate.update("""
					INSERT INTO qw_result_artifact
					(artifact_id, run_id, source_sub_run_id, artifact_type, schema_json, data_json, row_count,
					 content_hash, status)
					VALUES (?, ?, NULL, 'DIRECT_RESULT', ?, ?, ?, ?, 'READY')
					""", artifactId, runId, schemaJson, dataJson, rowCount, sha256(schemaJson + "\n" + dataJson));
		}
		catch (DuplicateKeyException duplicate) {
			return requireArtifact(artifactId);
		}
		ResultArtifact artifact = requireArtifact(artifactId);
		runService.appendEvent(runId, attemptId, "RESULT_ARTIFACT_READY", "result-artifact", json(artifact),
				"Validated single-source result is ready", "result-artifact-ready:" + artifactId);
		return artifact;
	}

	public ResultSetBO resultSet(ResultArtifact artifact) {
		return toResultSet(Objects.requireNonNull(artifact, "artifact is required"));
	}

public Optional<MultiSourceRunView> find(String runId, String executionKey) {
		String scope = requiredExecutionKey(executionKey);
		List<SourceSubRun> subRuns = jdbcTemplate.query("""
				SELECT * FROM qw_source_sub_run WHERE run_id = ? AND execution_key = ? ORDER BY datasource_id
				""", SOURCE_SUB_RUN_MAPPER, runId, scope);
		List<MergeExecution> merges = jdbcTemplate.query("""
				SELECT * FROM qw_merge_execution WHERE run_id = ? AND execution_key = ?
				""", MERGE_EXECUTION_MAPPER, runId, scope);
		if (subRuns.isEmpty() && merges.isEmpty()) {
			return Optional.empty();
		}
		if (merges.size() != 1) {
			throw new IllegalStateException("Multi-source execution has no stable merge state: " + runId + "/" + scope);
		}
		return Optional.of(new MultiSourceRunView(runId, subRuns, merges.get(0)));
	}

	public ResultArtifact requireArtifact(String artifactId) {
		return jdbcTemplate.query("SELECT * FROM qw_result_artifact WHERE artifact_id = ?", ARTIFACT_MAPPER, artifactId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Result artifact not found: " + artifactId));
	}

	private SourceSubRun requireSubRun(String runId, String subRunId) {
		return jdbcTemplate
			.query("SELECT * FROM qw_source_sub_run WHERE run_id = ? AND sub_run_id = ?", SOURCE_SUB_RUN_MAPPER, runId,
					subRunId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Source subrun not found: " + subRunId));
	}

	private MergePolicy readMergePolicy(MergeExecution merge) {
		if (merge.policyJson() == null || merge.policyJson().isBlank()) {
			return MergePolicy.builder()
				.policyCode("single-source")
				.mergeType(MultiSourcePolicySnapshot.MergeType.UNION)
				.nullPolicy("KEEP")
				.duplicatePolicy("KEEP_ALL")
				.partialFailurePolicy("FAIL_ALL")
				.maxRows(10_000)
				.build();
		}
		try {
			SemanticBlueprint.MergePlan plan = JsonUtil.getObjectMapper()
				.readValue(merge.policyJson(), SemanticBlueprint.MergePlan.class);
			return MergePolicy.builder()
				.policyCode(plan.getPolicyCode())
				.mergeType(plan.getMergeType())
				.relationshipCode(plan.getRelationshipCode())
				.leftInputKey(plan.getLeftInputKey())
				.rightInputKey(plan.getRightInputKey())
				.outputKey(plan.getOutputKey())
				.inputGrain(plan.getInputGrain())
				.nullPolicy(plan.getNullPolicy())
				.duplicatePolicy(plan.getDuplicatePolicy())
				.maxRows(plan.getMaxRows())
				.partialFailurePolicy(plan.getPartialFailurePolicy())
				.calculationExpression(plan.getCalculationExpression())
				.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to read persisted merge policy", ex);
		}
	}

	private ResultSetBO toResultSet(ResultArtifact artifact) {
		try {
			List<String> columns = JsonUtil.getObjectMapper()
				.readValue(artifact.schemaJson(), new TypeReference<List<String>>() {
				});
			List<java.util.Map<String, String>> data = JsonUtil.getObjectMapper()
				.readValue(artifact.dataJson(), new TypeReference<List<java.util.Map<String, String>>>() {
				});
			return ResultSetBO.builder().column(columns).data(data).build();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to read result artifact: " + artifact.artifactId(), ex);
		}
	}

	private String requiredExecutionKey(String executionKey) {
		if (executionKey == null || executionKey.isBlank()) {
			throw new IllegalArgumentException("executionKey is required");
		}
		String normalized = executionKey.trim();
		if (normalized.length() > 128) {
			throw new IllegalArgumentException("executionKey exceeds 128 characters");
		}
		return normalized;
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize multi-source state", ex);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static final RowMapper<SourceSubRun> SOURCE_SUB_RUN_MAPPER = (rs, rowNum) -> new SourceSubRun(
			rs.getString("sub_run_id"), rs.getString("run_id"), rs.getLong("project_id"),
			rs.getLong("project_version_id"), rs.getInt("datasource_id"),
			SourceSubRunStatus.valueOf(rs.getString("status")), rs.getString("source_plan_json"),
			rs.getString("sql_text"), rs.getString("result_artifact_id"), nullableLong(rs, "row_count"),
			rs.getString("freshness_as_of"), rs.getString("error_message"), rs.getLong("revision"),
			timestamp(rs, "create_time"), timestamp(rs, "update_time"), timestamp(rs, "finish_time"));

	private static final RowMapper<ResultArtifact> ARTIFACT_MAPPER = (rs, rowNum) -> new ResultArtifact(
			rs.getString("artifact_id"), rs.getString("run_id"), rs.getString("source_sub_run_id"),
			rs.getString("artifact_type"), rs.getString("schema_json"), rs.getString("data_json"),
			rs.getLong("row_count"), rs.getString("content_hash"), rs.getString("status"), timestamp(rs, "create_time"),
			timestamp(rs, "update_time"));

	private static final RowMapper<MergeExecution> MERGE_EXECUTION_MAPPER = (rs, rowNum) -> new MergeExecution(
			rs.getString("merge_id"), rs.getString("run_id"), rs.getLong("project_id"),
			rs.getLong("project_version_id"), rs.getString("policy_code"), rs.getString("policy_json"),
			MergeStatus.valueOf(rs.getString("status")), rs.getString("input_artifacts_json"),
			rs.getString("output_artifact_id"), rs.getString("error_message"), rs.getLong("revision"),
			timestamp(rs, "create_time"), timestamp(rs, "update_time"), timestamp(rs, "finish_time"));

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toLocalDateTime();
	}

	public enum SourceSubRunStatus {

		QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED

	}

	public enum MergeStatus {

		WAITING_SOURCES, NOT_REQUIRED, RUNNING, COMPLETED, FAILED

	}

	public record SourceSubRun(String subRunId, String runId, Long projectId, Long projectVersionId,
			Integer datasourceId, SourceSubRunStatus status, String sourcePlanJson, String sqlText,
			String resultArtifactId, Long rowCount, String freshnessAsOf, String errorMessage, long revision,
			LocalDateTime createTime, LocalDateTime updateTime, LocalDateTime finishTime) {
	}

	public record ResultArtifact(String artifactId, String runId, String sourceSubRunId, String artifactType,
			String schemaJson, String dataJson, long rowCount, String contentHash, String status,
			LocalDateTime createTime, LocalDateTime updateTime) {
	}

	public record MergeExecution(String mergeId, String runId, Long projectId, Long projectVersionId, String policyCode,
			String policyJson, MergeStatus status, String inputArtifactsJson, String outputArtifactId,
			String errorMessage, long revision, LocalDateTime createTime, LocalDateTime updateTime,
			LocalDateTime finishTime) {
	}

	public record MultiSourceRunView(String runId, List<SourceSubRun> sourceSubRuns, MergeExecution mergeExecution) {
	}

}
