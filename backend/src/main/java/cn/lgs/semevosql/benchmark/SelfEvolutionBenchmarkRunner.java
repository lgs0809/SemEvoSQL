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
package cn.lgs.semevosql.benchmark;

import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmark.Observation;
import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmark.Stage;
import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageCommand;
import cn.lgs.semevosql.conversation.QueryApprovalMode;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.FeedbackRequest;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.SemanticPlanSnapshotService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.trajectory.TrajectoryAnalysisService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Controlled real-run benchmark harness for measuring how accumulated experience changes query quality.
 *
 * <p>Each stage must use an explicitly supplied project. This keeps cold and evolved stages isolated rather than
 * toggling production learning features or deleting experience in place. Warm-up questions can be adopted as normal
 * successful Episodes; the same held-out cases are then executed against every stage and summarized by
 * {@link SelfEvolutionBenchmark}.</p>
 */
@Service
public class SelfEvolutionBenchmarkRunner {

	private static final String PRINCIPAL = "self-evolution-benchmark";

	private final ProjectConversationService conversations;

	private final QueryRunService runs;

	private final SemanticPlanSnapshotService semanticPlanSnapshots;

	private final TrajectoryAnalysisService trajectoryAnalysis;

	private final SemEvoSQLProductionService productionService;

	private final JdbcTemplate jdbc;

	public SelfEvolutionBenchmarkRunner(ProjectConversationService conversations, QueryRunService runs,
			SemanticPlanSnapshotService semanticPlanSnapshots, TrajectoryAnalysisService trajectoryAnalysis,
			SemEvoSQLProductionService productionService, JdbcTemplate jdbc) {
		this.conversations = conversations;
		this.runs = runs;
		this.semanticPlanSnapshots = semanticPlanSnapshots;
		this.trajectoryAnalysis = trajectoryAnalysis;
		this.productionService = productionService;
		this.jdbc = jdbc;
	}

	public ExperimentResult runExperiment(List<StagePlan> stages, List<BenchmarkCase> heldOutCases, Duration timeout) {
		if (stages == null || stages.isEmpty()) {
			throw new IllegalArgumentException("At least one benchmark stage is required");
		}
		if (heldOutCases == null || heldOutCases.isEmpty()) {
			throw new IllegalArgumentException("Held-out benchmark cases are required");
		}
		Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(3)
				: timeout;
		assertStageIsolation(stages);
		List<StageResult> results = new ArrayList<>();
		for (StagePlan stage : stages) {
			for (String warmup : stage.warmupQuestions()) {
				runWarmup(stage.projectId(), warmup, effectiveTimeout);
			}
			List<CaseResult> cases = heldOutCases.stream()
				.map(testCase -> executeCase(stage.projectId(), testCase, effectiveTimeout))
				.toList();
			List<Observation> observations = cases.stream().map(CaseResult::observation).toList();
			results.add(new StageResult(stage.stage(), stage.label(), stage.projectId(), stage.warmupQuestions().size(),
					SelfEvolutionBenchmark.evaluate(stage.stage(), observations), cases));
		}
		Map<Stage, SelfEvolutionBenchmark.Summary> summaries = results.stream()
			.collect(java.util.stream.Collectors.toMap(StageResult::stage, StageResult::summary, (left, right) -> right,
					() -> new java.util.EnumMap<>(Stage.class)));
		return new ExperimentResult(List.copyOf(results), Map.copyOf(summaries));
	}

	private void runWarmup(Long projectId, String question, Duration timeout) {
		if (!StringUtils.hasText(question)) {
			return;
		}
		QueryRun run = execute(projectId, question, timeout);
		if (run.status() == QueryRun.RunStatus.SUCCEEDED && StringUtils.hasText(run.episodeId())) {
			productionService.feedback(run.episodeId(), new FeedbackRequest(PRINCIPAL, 5, true, "benchmark warm-up"));
		}
	}

	private CaseResult executeCase(Long projectId, BenchmarkCase testCase, Duration timeout) {
		long started = System.nanoTime();
		QueryRun run = execute(projectId, testCase.question(), timeout);
		long clientLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
		if (StringUtils.hasText(run.episodeId()) && run.terminal()) {
			try {
				trajectoryAnalysis.analyzeEpisode(run.episodeId());
			}
			catch (RuntimeException ignored) {
				// Benchmark observation can still be reconstructed from Run/Episode facts when analysis is fail-soft.
			}
		}
		SemanticBlueprint plan = semanticPlanSnapshots.latest(run.runId()).orElse(null);
		boolean semanticCorrect = semanticMatches(plan, testCase.expectedAssetCodes());
		Map<String, Number> trajectory = trajectoryMetrics(run.runId());
		int clarificationCount = count("SELECT COUNT(*) FROM qw_runtime_clarification WHERE run_id = ?", run.runId());
		int wrongRecalls = count("""
				SELECT COUNT(*) FROM qw_query_case_usage
				WHERE run_id = ? AND failed_after_recall = TRUE
				""", run.runId());
		int usefulRecalls = count("""
				SELECT COUNT(*) FROM qw_query_case_usage
				WHERE run_id = ? AND recalled = TRUE AND failed_after_recall = FALSE AND outcome = 'SUCCEEDED'
				""", run.runId());
		int contamination = count("""
				SELECT COUNT(*)
				FROM qw_query_case_usage u
				JOIN qw_query_case_binding_dependency d ON d.query_example_id = u.query_example_id
				WHERE u.run_id = ? AND d.binding_scope = 'USER'
				  AND COALESCE(d.principal_id, '') <> ?
				""", run.runId(), PRINCIPAL);
		int patternReuse = count("""
				SELECT COUNT(*) FROM qw_semantic_sql_pattern_usage
				WHERE run_id = ? AND event_type IN ('APPLIED', 'SUCCEEDED', 'FAILED')
				""", run.runId());
		int llmSqlGeneration = count("""
				SELECT COUNT(*) FROM qw_node_trace n
				JOIN qw_query_run r ON r.attempt_id = n.attempt_id
				WHERE r.run_id = ? AND UPPER(n.node_name) LIKE '%SQL%GENERAT%'
				""", run.runId());
		long latencyMs = number(trajectory.get("latency_ms"), clientLatencyMs);
		long tokenCount = number(trajectory.get("token_count"), 0L);
		int retries = (int) number(trajectory.get("retry_count"), 0L);
		Observation observation = new Observation(run.status() == QueryRun.RunStatus.SUCCEEDED, semanticCorrect,
				clarificationCount, retries, wrongRecalls, contamination, usefulRecalls, patternReuse, llmSqlGeneration,
				latencyMs, tokenCount);
		return new CaseResult(testCase.id(), run.runId(), run.status().name(), run.errorCode(), observation);
	}

	private QueryRun execute(Long projectId, String question, Duration timeout) {
		String requestId = "benchmark-" + UUID.randomUUID();
		var conversation = conversations.create(projectId, "Self-evolution benchmark", PRINCIPAL);
		var submitted = conversations.send(projectId, conversation.conversationId(),
				new SendMessageCommand(question, requestId, requestId, QueryApprovalMode.AUTO_EXECUTE), PRINCIPAL);
		long deadline = System.nanoTime() + timeout.toNanos();
		QueryRun current = submitted.run();
		while (!current.terminal() && current.status() != QueryRun.RunStatus.WAITING_HUMAN) {
			if (System.nanoTime() >= deadline) {
				throw new IllegalStateException("Benchmark Run timed out: " + current.runId());
			}
			try {
				Thread.sleep(200L);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Benchmark Run interrupted: " + current.runId(), interrupted);
			}
			current = runs.get(current.runId());
		}
		return current;
	}

	private boolean semanticMatches(SemanticBlueprint plan, Set<String> expectedAssetCodes) {
		if (expectedAssetCodes == null || expectedAssetCodes.isEmpty()) {
			return plan != null && plan.isExecutable();
		}
		if (plan == null) {
			return false;
		}
		Set<String> actual = new LinkedHashSet<>();
		plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).filter(Objects::nonNull)
			.forEach(actual::add);
		plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).filter(Objects::nonNull)
			.forEach(actual::add);
		plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).filter(Objects::nonNull)
			.forEach(actual::add);
		plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).filter(Objects::nonNull)
			.forEach(actual::add);
		plan.getRelationships().stream().map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
			.filter(Objects::nonNull).forEach(actual::add);
		plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).filter(Objects::nonNull)
			.forEach(actual::add);
		plan.getEnumResolutions().stream().map(SemanticBlueprint.EnumResolution::getValueCode).filter(Objects::nonNull)
			.forEach(actual::add);
		return actual.containsAll(expectedAssetCodes);
	}

	private Map<String, Number> trajectoryMetrics(String runId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT latency_ms, token_count, retry_count FROM qw_trajectory_path
				WHERE run_id = ? ORDER BY create_time DESC LIMIT 1
				""", runId);
		if (rows.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> row = rows.get(0);
		return Map.of("latency_ms", numeric(row.get("latency_ms")), "token_count", numeric(row.get("token_count")),
				"retry_count", numeric(row.get("retry_count")));
	}

	private Number numeric(Object value) {
		return value instanceof Number number ? number : 0L;
	}

	private long number(Number value, long fallback) {
		return value == null ? fallback : value.longValue();
	}

	private int count(String sql, Object... arguments) {
		Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
		return count == null ? 0 : Math.max(0, count);
	}

	private void assertStageIsolation(Collection<StagePlan> stages) {
		Set<Long> projectIds = new LinkedHashSet<>();
		for (StagePlan stage : stages) {
			if (stage == null || stage.stage() == null || stage.projectId() == null) {
				throw new IllegalArgumentException("Every benchmark stage requires stage and projectId");
			}
			if (!projectIds.add(stage.projectId())) {
				throw new IllegalArgumentException(
						"Benchmark stages must use isolated project IDs; duplicate projectId=" + stage.projectId());
			}
		}
	}

	public record BenchmarkCase(String id, String question, Set<String> expectedAssetCodes) {
		public BenchmarkCase {
			if (!StringUtils.hasText(id) || !StringUtils.hasText(question)) {
				throw new IllegalArgumentException("Benchmark case id and question are required");
			}
			expectedAssetCodes = Set.copyOf(expectedAssetCodes == null ? Set.of() : expectedAssetCodes);
		}
	}

	public record StagePlan(Stage stage, String label, Long projectId, List<String> warmupQuestions) {
		public StagePlan {
			label = StringUtils.hasText(label) ? label : stage == null ? "" : stage.name();
			warmupQuestions = List.copyOf(warmupQuestions == null ? List.of() : warmupQuestions);
		}
	}

	public record CaseResult(String caseId, String runId, String runStatus, String errorCode, Observation observation) {
	}

	public record StageResult(Stage stage, String label, Long projectId, int warmupCount,
			SelfEvolutionBenchmark.Summary summary, List<CaseResult> cases) {
		public StageResult {
			cases = List.copyOf(cases == null ? List.of() : cases);
		}
	}

	public record ExperimentResult(List<StageResult> stages, Map<Stage, SelfEvolutionBenchmark.Summary> summaries) {
		public ExperimentResult {
			stages = List.copyOf(stages == null ? List.of() : stages);
			summaries = Map.copyOf(summaries == null ? Map.of() : summaries);
		}
	}
}
