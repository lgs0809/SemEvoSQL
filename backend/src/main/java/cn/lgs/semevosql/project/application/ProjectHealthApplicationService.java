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
package cn.lgs.semevosql.project.application;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only aggregation of factual project readiness, quality, and freshness signals. */
@Service
@RequiredArgsConstructor
public class ProjectHealthApplicationService {

	private static final int QUALITY_WINDOW_DAYS = 30;

	private final SemanticProjectRepository repository;

	private final ProjectVersionCatalogReadiness catalogReadiness;

	private final ProjectScopeService projectScope;

	private final JdbcTemplate jdbc;

	public ProjectHealthView getHealth(Long projectId, OperatorContext operator) {
		projectScope.requireProject(projectId, operator);
		SemanticProject project = repository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
		SemanticProjectVersion activeVersion = project.getActiveVersionId() == null ? null
				: repository.findVersion(project.getActiveVersionId()).orElse(null);
		SemanticProjectVersion workingVersion = repository.findLatestVersion(projectId).orElse(activeVersion);

		UnderstandingHealth understanding = understanding(projectId, workingVersion);
		QueryQuality quality = queryQuality(projectId);
		DataFreshness freshness = dataFreshness(projectId);
		ReleaseHealth release = releaseHealth(workingVersion);
		List<NextAction> nextActions = nextActions(project, activeVersion, workingVersion, understanding, quality);

		return new ProjectHealthView(projectId, project.getStatus().name(), activeVersion != null,
				version(activeVersion), version(workingVersion), understanding, quality, freshness, release,
				nextActions);
	}

	public ProjectHealthSummaryView getSummary(Long projectId, OperatorContext operator) {
		projectScope.requireProject(projectId, operator);
		try {
			ProjectHealthView health = getHealth(projectId, operator);
			NextAction nextAction = health.nextActions().isEmpty() ? null : health.nextActions().get(0);
			return new ProjectHealthSummaryView(projectId, true, health.queryReady(), health.activeVersion(), nextAction,
					health.quality().totalQueries(), health.quality().querySuccessRate(), health.quality().correctionCount());
		}
		catch (RuntimeException ex) {
			return new ProjectHealthSummaryView(projectId, false, false, null, null, 0, BigDecimal.ZERO, 0);
		}
	}

	private UnderstandingHealth understanding(Long projectId, SemanticProjectVersion version) {
		if (version == null) {
			return new UnderstandingHealth(false, List.of("No project version exists"), 0, 0, 0, 0, 0, 0, 0, 0);
		}
		CatalogReadiness readiness = catalogReadiness.assess(projectId, version.getId());
		long openGaps = repository.countOpenGaps(projectId, version.getId());
		long datasourceCount = repository.findDatasourceBindings(version.getId()).size();
		long documentCount = count("SELECT COUNT(*) FROM qw_semantic_material WHERE project_version_id = ?",
				version.getId());
		long modelCount = count(
				"SELECT COUNT(*) FROM qw_semantic_model WHERE project_version_id = ? AND status = 'ENABLED'",
				version.getId());
		long metricCount = count(
				"SELECT COUNT(*) FROM qw_semantic_metric WHERE project_version_id = ? AND status = 'ENABLED'",
				version.getId());
		long dimensionCount = count(
				"SELECT COUNT(*) FROM qw_semantic_dimension WHERE project_version_id = ? AND status = 'ENABLED'",
				version.getId());
		long relationshipCount = count(
				"SELECT COUNT(*) FROM qw_semantic_relationship WHERE project_version_id = ? AND status = 'ENABLED'",
				version.getId());
		long unresolvedConflicts = count("""
				SELECT COUNT(*)
				FROM qw_onboarding_conflict c
				JOIN qw_onboarding_session s ON s.session_id = c.session_id
				WHERE s.project_id = ? AND s.project_version_id = ? AND c.status <> 'RESOLVED'
				""", projectId, version.getId());
		return new UnderstandingHealth(readiness.ready(), readiness.violations(), openGaps, unresolvedConflicts,
				datasourceCount, documentCount, modelCount, metricCount, dimensionCount, relationshipCount);
	}

	private QueryQuality queryQuality(Long projectId) {
		LocalDateTime from = LocalDateTime.now().minusDays(QUALITY_WINDOW_DAYS);
		long total = count("""
				SELECT COUNT(*) FROM qw_query_run
				WHERE project_id = ? AND run_type = 'INTERACTIVE_QUERY' AND create_time >= ?
				""", projectId, from);
		long succeeded = count("""
				SELECT COUNT(*) FROM qw_query_run
				WHERE project_id = ? AND run_type = 'INTERACTIVE_QUERY'
				  AND status = 'SUCCEEDED' AND create_time >= ?
				""", projectId, from);
		long failed = count("""
				SELECT COUNT(*) FROM qw_query_run
				WHERE project_id = ? AND run_type = 'INTERACTIVE_QUERY'
				  AND status IN ('FAILED', 'CANCELLED', 'EXPIRED') AND create_time >= ?
				""", projectId, from);
		long clarifiedRuns = count("""
				SELECT COUNT(DISTINCT r.run_id)
				FROM qw_query_run r
				JOIN qw_runtime_clarification c ON c.run_id = r.run_id
				WHERE r.project_id = ? AND r.run_type = 'INTERACTIVE_QUERY'
				  AND r.create_time >= ?
				""", projectId, from);
		long corrections = count("""
				SELECT COUNT(DISTINCT f.episode_id)
				FROM qw_feedback f
				JOIN qw_episode e ON e.id = f.episode_id
				WHERE e.project_id = ? AND f.create_time >= ? AND f.comment_text LIKE 'CORRECTION[%'
				""", projectId, from);
		long confirmedTrusted = count("""
				SELECT COUNT(DISTINCT f.episode_id)
				FROM qw_feedback f
				JOIN qw_episode e ON e.id = f.episode_id
				WHERE e.project_id = ? AND f.create_time >= ?
				  AND (f.adopted = TRUE OR COALESCE(f.rating, 0) >= 4)
				""", projectId, from);
		long successfulWithoutCorrection = count("""
				SELECT COUNT(*)
				FROM qw_query_run r
				WHERE r.project_id = ? AND r.run_type = 'INTERACTIVE_QUERY'
				  AND r.status = 'SUCCEEDED' AND r.create_time >= ?
				  AND NOT EXISTS (
				    SELECT 1 FROM qw_feedback f
				    WHERE f.episode_id = r.episode_id AND f.comment_text LIKE 'CORRECTION[%'
				  )
				""", projectId, from);
		long queryCaseReusedRuns = count("""
				SELECT COUNT(DISTINCT r.run_id)
				FROM qw_query_run r
				JOIN qw_query_case_usage u ON u.run_id = r.run_id AND u.recalled = TRUE
				WHERE r.project_id = ? AND r.run_type = 'INTERACTIVE_QUERY'
				  AND r.create_time >= ?
				""", projectId, from);
		return new QueryQuality(QUALITY_WINDOW_DAYS, total, succeeded, failed, clarifiedRuns, corrections,
				confirmedTrusted, successfulWithoutCorrection, queryCaseReusedRuns, rate(succeeded, total),
				rate(clarifiedRuns, total), rate(corrections, total), rate(confirmedTrusted, total),
				rate(successfulWithoutCorrection, total), rate(queryCaseReusedRuns, total));
	}

	private DataFreshness dataFreshness(Long projectId) {
		List<String> freshness = jdbc.query("""
				SELECT freshness_as_of
				FROM qw_source_sub_run
				WHERE project_id = ? AND freshness_as_of IS NOT NULL AND freshness_as_of <> ''
				ORDER BY COALESCE(finish_time, update_time) DESC
				LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), projectId);
		List<LocalDateTime> lastQueries = jdbc.query(
				"""
						SELECT MAX(finish_time)
						FROM qw_query_run
						WHERE project_id = ? AND run_type = 'INTERACTIVE_QUERY' AND status = 'SUCCEEDED'
						""",
				(rs, rowNum) -> {
					Timestamp value = rs.getTimestamp(1);
					return value == null ? null : value.toLocalDateTime();
				}, projectId);
		LocalDateTime lastSuccessfulQueryAt = lastQueries.stream()
			.filter(value -> value != null)
			.findFirst()
			.orElse(null);
		return new DataFreshness(freshness.stream().findFirst().orElse(null), lastSuccessfulQueryAt,
				freshness.isEmpty() ? "UNOBSERVED" : "OBSERVED");
	}

	private ReleaseHealth releaseHealth(SemanticProjectVersion version) {
		if (version == null) {
			return new ReleaseHealth(0, 0, 0, null);
		}
		long replayTotal = count("""
				SELECT COUNT(*) FROM qw_semantic_replay_result
				WHERE target_version_id = ? AND status IN ('PASSED', 'FAILED', 'REVIEW_REQUIRED')
				""", version.getId());
		long replayPassed = count("""
				SELECT COUNT(*) FROM qw_semantic_replay_result
				WHERE target_version_id = ? AND status = 'PASSED'
				""", version.getId());
		long pendingChanges = count("""
				SELECT COUNT(*) FROM qw_semantic_evolution_candidate
				WHERE project_id = ? AND (source_version_id = ? OR target_draft_version_id = ?)
				  AND status NOT IN ('PUBLISHED', 'REJECTED', 'STALE')
				""", version.getProjectId(), version.getId(), version.getId());
		return new ReleaseHealth(replayTotal, replayPassed, pendingChanges,
				replayTotal == 0 ? null : rate(replayPassed, replayTotal));
	}

	private List<NextAction> nextActions(SemanticProject project, SemanticProjectVersion activeVersion,
			SemanticProjectVersion workingVersion, UnderstandingHealth understanding, QueryQuality quality) {
		List<NextAction> actions = new ArrayList<>();
		if (understanding.datasourceCount() == 0) {
			actions.add(new NextAction("CONNECT_DATA", "连接业务数据", "项目还没有可用于问数的数据连接。", "data"));
		}
		if (understanding.openGapCount() > 0 || understanding.unresolvedConflictCount() > 0) {
			actions.add(new NextAction("RESOLVE_BUSINESS_GAPS", "补充必要业务规则", "还有 " + understanding.openGapCount()
					+ " 个待解决问题和 " + understanding.unresolvedConflictCount() + " 个待处理冲突。", "business"));
		}
		else if (!understanding.catalogReady() && workingVersion != null) {
			actions.add(new NextAction("COMPLETE_BUSINESS_MODEL", "完善业务模型", "当前版本仍有发布前校验项需要处理。", "business"));
		}
		if (activeVersion == null && workingVersion != null && understanding.catalogReady()
				&& understanding.openGapCount() == 0) {
			if (workingVersion
				.getStatus() == cn.lgs.semevosql.project.domain.ProjectVersionStatus.PUBLISHED) {
				actions.add(new NextAction("ACTIVATE_VERSION", "激活正式业务模型", "已有发布版本但尚未激活，激活后新会话即可开始问数。", "release"));
			}
			else {
				actions.add(new NextAction("PUBLISH_VERSION", "验证并发布业务模型", "当前还没有正式业务模型，发布后即可开始问数。", "release"));
			}
		}
		if (quality.totalQueries() >= 5 && quality.querySuccessRate().compareTo(new BigDecimal("0.9000")) < 0) {
			actions.add(new NextAction("REVIEW_QUERY_QUALITY", "检查近期失败查询",
					"最近 " + QUALITY_WINDOW_DAYS + " 天查询成功率为 " + percent(quality.querySuccessRate()) + "。", "test"));
		}
		if (quality.correctionCount() >= 3) {
			actions.add(new NextAction("REVIEW_CORRECTIONS", "处理重复纠错信号",
					"最近 " + QUALITY_WINDOW_DAYS + " 天已有 " + quality.correctionCount() + " 条明确纠错。", "improve"));
		}
		if (actions.isEmpty() && project.getActiveVersionId() != null) {
			actions.add(new NextAction("ASK_DATA", "开始问数", "当前正式业务模型已可用。", "chat"));
		}
		return actions.stream().limit(3).toList();
	}

	private VersionHealth version(SemanticProjectVersion version) {
		return version == null ? null
				: new VersionHealth(version.getId(), version.getVersionNumber(), version.getStatus().name(),
						version.getCreateTime(), version.getValidatedTime(), version.getPublishedTime());
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private BigDecimal rate(long numerator, long denominator) {
		if (denominator <= 0) {
			return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
		}
		return BigDecimal.valueOf(numerator)
			.divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
			.min(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
	}

	private String percent(BigDecimal rate) {
		return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
	}

	public record ProjectHealthView(Long projectId, String projectStatus, boolean queryReady,
			VersionHealth activeVersion, VersionHealth workingVersion, UnderstandingHealth understanding,
			QueryQuality quality, DataFreshness freshness, ReleaseHealth release, List<NextAction> nextActions) {
	}

	public record ProjectHealthSummaryView(Long projectId, boolean available, boolean queryReady,
			VersionHealth activeVersion, NextAction nextAction, long totalQueries, BigDecimal querySuccessRate,
			long correctionCount) {
	}

	public record VersionHealth(Long id, String versionNumber, String status, LocalDateTime createTime,
			LocalDateTime validatedTime, LocalDateTime publishedTime) {
	}

	public record UnderstandingHealth(boolean catalogReady, List<String> readinessViolations, long openGapCount,
			long unresolvedConflictCount, long datasourceCount, long documentCount, long modelCount, long metricCount,
			long dimensionCount, long relationshipCount) {
	}

	public record QueryQuality(int windowDays, long totalQueries, long succeededQueries, long failedQueries,
			long clarifiedRunCount, long correctionCount, long confirmedTrustedAnswerCount,
			long successfulWithoutCorrectionCount, long queryCaseReusedRunCount, BigDecimal querySuccessRate,
			BigDecimal clarificationRate, BigDecimal correctionRate, BigDecimal confirmedTrustedAnswerRate,
			BigDecimal correctionFreeSuccessfulAnswerRate, BigDecimal queryCaseReuseRate) {
	}

	public record DataFreshness(String latestSourceFreshnessAsOf, LocalDateTime lastSuccessfulQueryAt,
			String observationStatus) {
	}

	public record ReleaseHealth(long replayCaseCount, long replayPassedCount, long pendingLearningChangeCount,
			BigDecimal replayPassRate) {
	}

	public record NextAction(String code, String label, String description, String target) {
	}

}
