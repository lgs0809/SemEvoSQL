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

import cn.lgs.semevosql.learning.QueryCaseRecallService.RecalledQueryCase;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Compatibility facade for existing runtime and controller contracts. New code should
 * depend on the focused QueryCase* application services and repositories directly.
 */
@Service
public class ValidatedQueryExampleService {

	private final QueryCaseRepository repository;

	private final QueryCaseAssetReferenceRepository assetReferences;

	private final QueryCaseCaptureService captureService;

	private final QueryCaseRecallService recallService;

	private final QueryCaseUsageService usageService;

	private final QueryCaseRebindService rebindService;

	public ValidatedQueryExampleService(QueryCaseRepository repository,
			QueryCaseAssetReferenceRepository assetReferences, QueryCaseCaptureService captureService,
			QueryCaseRecallService recallService, QueryCaseUsageService usageService,
			QueryCaseRebindService rebindService) {
		this.repository = repository;
		this.assetReferences = assetReferences;
		this.captureService = captureService;
		this.recallService = recallService;
		this.usageService = usageService;
		this.rebindService = rebindService;
	}

	public Optional<Map<String, Object>> captureEligibleCandidate(String episodeId) {
		return captureService.captureEligibleCandidate(episodeId).map(QueryCaseSummary::toMap);
	}

	public List<Map<String, Object>> list(Long projectId, Long projectVersionId, String status, int limit) {
		return list(projectId, projectVersionId, status, null, limit);
	}

	public List<Map<String, Object>> list(Long projectId, Long projectVersionId, String status, String rebindStatus,
			int limit) {
		return repository.list(projectId, projectVersionId, status, rebindStatus, limit)
			.stream()
			.map(QueryCaseSummary::toMap)
			.toList();
	}

	public Map<String, Object> detail(Long projectId, String exampleId) {
		return repository.detail(projectId, exampleId).toMap();
	}

	public List<QueryExample> recallApproved(Long projectId, Long projectVersionId, String catalogHash, String question,
			int limit) {
		return recallService.recallApproved(projectId, projectVersionId, catalogHash, question, limit)
			.stream()
			.map(this::queryExample)
			.toList();
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			int limit) {
		return recallService.recallHints(projectId, projectVersionId, catalogHash, question, limit);
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, int limit) {
		return recallService.recallHints(projectId, projectVersionId, catalogHash, question, contextHash, limit);
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, String principalId, int limit) {
		return recallService.recallHints(projectId, projectVersionId, catalogHash, question, contextHash, principalId,
				limit);
	}

	public QueryCaseHints recallHintsForEvaluation(Long projectId, Long projectVersionId, String catalogHash,
			String question, String contextHash, int limit) {
		return recallService.recallHintsForEvaluation(projectId, projectVersionId, catalogHash, question, contextHash,
				limit);
	}

	public void recordHintUsage(String runId, QueryCaseHints hints) {
		usageService.recordHintUsage(runId, hints);
	}

	public void recordHintUsage(String runId, String attemptId, QueryCaseHints hints) {
		usageService.recordHintUsage(runId, attemptId, hints);
	}

	public void recordEpisodeOutcome(String episodeId, String outcome) {
		usageService.recordEpisodeOutcome(episodeId, outcome);
	}

	public void recordEpisodeAdoption(String episodeId, boolean adopted) {
		usageService.recordEpisodeAdoption(episodeId, adopted);
	}

	public String renderApprovedExamples(Long projectId, Long projectVersionId, String catalogHash, String question,
			int limit) {
		return recallService.renderApprovedExamples(projectId, projectVersionId, catalogHash, question, limit);
	}

	public String renderApprovedExamples(Long projectId, Long projectVersionId, String catalogHash, String question,
			String principalId, int limit) {
		return recallService.renderApprovedExamples(projectId, projectVersionId, catalogHash, question, principalId,
				limit);
	}

	public RebindReport rebindApprovedExamples(Long projectId, Long sourceVersionId, Long targetVersionId,
			String targetCatalogHash, SemanticCatalogSnapshot targetCatalog) {
		QueryCaseRebindService.RebindReport report = rebindService.rebindApprovedExamples(projectId, sourceVersionId,
				targetVersionId, targetCatalogHash, targetCatalog);
		return new RebindReport(report.total(), report.rebound(), report.needsReview());
	}

	private QueryExample queryExample(RecalledQueryCase value) {
		return new QueryExample(value.id(), value.question(), value.sql(), value.datasourceId(), value.catalogHash(),
				value.score());
	}

	public record QueryExample(String id, String question, String sql, Integer datasourceId, String catalogHash,
			double score) {
	}

	public record RebindReport(int total, int rebound, int needsReview) {
	}

}
