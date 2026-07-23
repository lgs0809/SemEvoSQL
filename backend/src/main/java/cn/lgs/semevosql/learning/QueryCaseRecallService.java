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

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Permission-checked consumer of independent PostgreSQL FTS/pgvector case rankings. */
@Service
public class QueryCaseRecallService {

	private static final double MIN_HINT_RELEVANCE = 15d;

	private static final double MIN_HINT_CONFIDENCE = 0.60d;

	private final QueryCaseRepository repository;

	private final QueryCaseUsageService usageService;

	private final QueryCaseRetrievalIndexService retrievalIndex;

	private final VersionedJson versionedJson = new VersionedJson();
    private cn.lgs.semevosql.clarification.PersonalSemanticDefinitionStore personalDefinitions;
    @org.springframework.beans.factory.annotation.Autowired
    public void personalDefinitions(cn.lgs.semevosql.clarification.PersonalSemanticDefinitionStore personalDefinitions){this.personalDefinitions=personalDefinitions;}

	public QueryCaseRecallService(QueryCaseRepository repository, QueryCaseUsageService usageService,
			QueryCaseRetrievalIndexService retrievalIndex, Optional<EmbeddingModel> embeddingModel) {
		this.repository = repository;
		this.usageService = usageService;
		this.retrievalIndex = retrievalIndex;
	}

	public List<RecalledQueryCase> recallApproved(Long projectId, Long projectVersionId, String catalogHash,
			String question, int limit) {
		return recallApproved(projectId, projectVersionId, catalogHash, question, null, limit);
	}

	public List<RecalledQueryCase> recallApproved(Long projectId, Long projectVersionId, String catalogHash,
			String question, String principalId, int limit) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(question) || limit <= 0) {
			return List.of();
		}
		return rankedCandidates(projectId, projectVersionId, catalogHash, null, question, principalId)
			.stream()
			.map(scored -> new RecalledQueryCase(Objects.toString(scored.row().get("id")),
					Objects.toString(scored.row().get("normalized_question")),
					Objects.toString(scored.row().get("sql_text")),
					scored.row().get("datasource_id") == null ? null
							: ((Number) scored.row().get("datasource_id")).intValue(),
					Objects.toString(scored.row().get("catalog_hash"), ""), scored.score()))
			.filter(example -> example.score() > 0)
			.sorted(Comparator.comparingDouble(RecalledQueryCase::score)
				.reversed()
				.thenComparing(RecalledQueryCase::id))
			.limit(Math.min(limit, 10))
			.peek(example -> usageService.recordRecall(example.id()))
			.toList();
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			int limit) {
		return recallHints(projectId, projectVersionId, catalogHash, question, null, null, limit, true);
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, int limit) {
		return recallHints(projectId, projectVersionId, catalogHash, question, contextHash, null, limit, true);
	}

	public QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, String principalId, int limit) {
		return recallHints(projectId, projectVersionId, catalogHash, question, contextHash, principalId, limit, true);
	}

	QueryCaseHints recallHintsForEvaluation(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, int limit) {
		return recallHints(projectId, projectVersionId, catalogHash, question, contextHash, null, limit, false);
	}

	public String renderApprovedExamples(Long projectId, Long projectVersionId, String catalogHash, String question,
			int limit) {
		return renderApprovedExamples(projectId, projectVersionId, catalogHash, question, null, limit);
	}

	public String renderApprovedExamples(Long projectId, Long projectVersionId, String catalogHash, String question,
			String principalId, int limit) {
		List<RecalledQueryCase> examples = recallApproved(projectId, projectVersionId, catalogHash, question, principalId,
				limit);
		if (examples.isEmpty()) {
			return "";
		}
		StringBuilder context = new StringBuilder("\n[已验证可召回 Query Case，仅提供当前 Catalog 重新绑定后的参数化 SQL Shape]\n");
		for (int index = 0; index < examples.size(); index++) {
			RecalledQueryCase example = examples.get(index);
			context.append(index + 1)
				.append(". 问题形态: ")
				.append(example.question())
				.append("\nSQL Shape: ")
				.append(parameterizedSqlShape(example.sql()))
				.append("\n");
		}
		context.append("不得复制与当前 Schema、语义计划或安全策略冲突的字段、表和过滤条件。\n");
		return context.toString();
	}

	private QueryCaseHints recallHints(Long projectId, Long projectVersionId, String catalogHash, String question,
			String contextHash, String principalId, int limit, boolean recordUsage) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(question)) {
			return QueryCaseHints.empty();
		}
		List<ScoredCase> cases = rankedCandidates(projectId, projectVersionId, catalogHash, contextHash, question, principalId)
			.stream()
			.map(scored -> scoredCase(scored.row(), scored.score(), scored.relevance()))
			.filter(scored -> scored.plan() != null && scored.relevance() >= MIN_HINT_RELEVANCE)
			.sorted(Comparator.comparingDouble(ScoredCase::score).reversed())
			.limit(Math.max(1, Math.min(limit, 10)))
			.toList();
		if (cases.isEmpty()) {
			return QueryCaseHints.empty();
		}
		ScoredCase top = cases.get(0);
		double confidence = relevanceConfidence(top.relevance());
		if (confidence < MIN_HINT_CONFIDENCE) {
			return QueryCaseHints.empty();
		}
		if (recordUsage) {
			usageService.recordRecall(top.id());
		}
		SemanticBlueprint plan = top.plan();
		Set<String> models = plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> metrics = plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> dimensions = plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> grains = plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> relationships = plan.getRelationships()
			.stream()
			.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> rules = plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		List<EnumBindingHint> enums = plan.getEnumResolutions()
			.stream()
			.map(value -> new EnumBindingHint(value.getInputText(), value.getModelCode(), value.getColumnName(),
					value.getValueCode(), top.id(), confidence))
			.toList();
		return new QueryCaseHints(models, metrics, dimensions, grains, relationships, rules, enums, top.intentType(),
				List.of(top.id()), confidence,
				Map.of("topScore", top.score(), "topRelevance", top.relevance(), "caseCount", (double) cases.size()));
	}

	private ScoredCase scoredCase(Map<String, Object> row, double score, double relevance) {
		SemanticBlueprint plan = readPlanJson(Objects.toString(row.get("typed_ir_json"), "")).orElse(null);
		return new ScoredCase(Objects.toString(row.get("id")), Objects.toString(row.get("intent_type")), plan, score,
				relevance);
	}

	private List<ScoredCandidate> rankedCandidates(Long projectId, Long projectVersionId, String catalogHash,
            String contextHash, String question, String principalId) {
        return retrievalIndex.search(projectId, projectVersionId, catalogHash, contextHash, principalId, question)
            .stream().map(hit -> {
                Map<String,Object> row = repository.require(projectId, hit.caseId());
                if (!repository.trustedForReuse(row, catalogHash)
                    || !repository.singlePlanCompatible(hit.caseId()) || !scopeCompatible(row, principalId)) return null;
                return new ScoredCandidate(row, hit.score(), 100 * hit.confidence());
            }).filter(Objects::nonNull).toList();
    }

	private boolean scopeCompatible(Map<String, Object> row, String principalId) {
		SemanticBlueprint plan = readPlanJson(Objects.toString(row.get("typed_ir_json"), "")).orElse(null);
        if(personalDefinitions!=null&&plan!=null&&plan.getBindingDependencies().stream().anyMatch(b->!personalDefinitions.currentReference(plan.getProjectId(),principalId,b)))return false;
		String queryCaseId = Objects.toString(row.get("id"), "");
		if (StringUtils.hasText(queryCaseId)) {
			List<Map<String, Object>> persisted = repository.bindingDependencies(queryCaseId);
			if (!persisted.isEmpty()) {
				return scopeCompatible(persisted, principalId);
			}
		}
		return scopeCompatible(plan, principalId);
	}

	private static boolean scopeCompatible(List<Map<String, Object>> dependencies, String principalId) {
		for (Map<String, Object> dependency : dependencies) {
			String scope = Objects
				.toString(dependency.get("binding_scope"), Objects.toString(dependency.get("binding_source"), ""))
				.trim()
				.toUpperCase(java.util.Locale.ROOT);
			if ("QUERY".equals(scope) || "PROJECT_PENDING".equals(scope)) {
				return false;
			}
			if ("USER".equals(scope) && (!StringUtils.hasText(principalId)
					|| !Objects.equals(principalId, Objects.toString(dependency.get("principal_id"), null)))) {
				return false;
			}
		}
		return true;
	}

	static boolean scopeCompatible(SemanticBlueprint plan, String principalId) {
		if (plan == null || plan.getBindingDependencies() == null || plan.getBindingDependencies().isEmpty()) {
			return true;
		}
		for (SemanticBlueprint.BindingDependency dependency : plan.getBindingDependencies()) {
			String scope = Objects.toString(dependency.getScope(), dependency.getSource())
				.trim()
				.toUpperCase(java.util.Locale.ROOT);
			if ("QUERY".equals(scope) || "PROJECT_PENDING".equals(scope)) {
				return false;
			}
			if ("USER".equals(scope) && (!StringUtils.hasText(principalId)
					|| !Objects.equals(principalId, dependency.getPrincipalId()))) {
				return false;
			}
		}
		return true;
	}

	private double relevanceConfidence(double relevance) {
		return Math.min(1d, Math.max(0d, relevance / 100d));
	}

	private Optional<SemanticBlueprint> readPlanJson(String value) {
		if (!StringUtils.hasText(value)) {
			return Optional.empty();
		}
		try {
			return Optional
				.of(versionedJson.read(value, JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, SemanticBlueprint.class));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	private String parameterizedSqlShape(String sql) {
		if (!StringUtils.hasText(sql)) {
			return "";
		}
		return sql.replaceAll("'(?:''|[^'])*'", "?")
			.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}:\\d{2})?\\b", "?")
			.replaceAll("(?<![A-Za-z0-9_$])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_$])", "?");
	}

	private record ScoredCase(String id, String intentType, SemanticBlueprint plan, double score, double relevance) {
	}

	private record ScoredCandidate(Map<String, Object> row, double score, double relevance) {
	}

	public record RecalledQueryCase(String id, String question, String sql, Integer datasourceId, String catalogHash,
			double score) {
	}

}
