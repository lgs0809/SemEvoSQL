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

import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.ai.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Metadata-filtered Exact/BM25/Vector/Shape recall and RRF ranking boundary. */
@Service
public class QueryCaseRecallService {

	private static final double MAX_FUSED_SCORE = 10d * 1000d / 61d;

	private static final double MIN_HINT_RELEVANCE = 15d;

	private static final double MIN_HINT_CONFIDENCE = 0.60d;

	private static final Logger log = LoggerFactory.getLogger(QueryCaseRecallService.class);

	private final QueryCaseRepository repository;

	private final QueryCaseUsageService usageService;

	private final QueryCaseRetrievalIndexService retrievalIndex;

	private final VersionedJson versionedJson = new VersionedJson();

	private final Optional<EmbeddingModel> embeddingModel;

	public QueryCaseRecallService(QueryCaseRepository repository, QueryCaseUsageService usageService,
			QueryCaseRetrievalIndexService retrievalIndex, Optional<EmbeddingModel> embeddingModel) {
		this.repository = repository;
		this.usageService = usageService;
		this.retrievalIndex = retrievalIndex;
		this.embeddingModel = embeddingModel;
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
		return rankedCandidates(projectId, projectVersionId, catalogHash, null, question,
				candidates(projectId, projectVersionId, catalogHash, null, question), principalId)
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
		List<ScoredCase> cases = rankedCandidates(projectId, projectVersionId, catalogHash, contextHash, question,
				candidates(projectId, projectVersionId, catalogHash, contextHash, question), principalId)
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

	private List<Map<String, Object>> candidates(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash, String question) {
		retrievalIndex.ensureCatalogIndexed(projectId, projectVersionId, catalogHash);
		List<String> terms = QueryCaseTextFeatures.queryTerms(question, 32);
		return repository.approvedCandidates(projectId, projectVersionId, catalogHash, contextHash, terms, 500);
	}

	private ScoredCase scoredCase(Map<String, Object> row, double score, double relevance) {
		SemanticBlueprint plan = readPlanJson(Objects.toString(row.get("typed_ir_json"), "")).orElse(null);
		return new ScoredCase(Objects.toString(row.get("id")), Objects.toString(row.get("intent_type")), plan, score,
				relevance);
	}

	private List<ScoredCandidate> rankedCandidates(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash, String question, List<Map<String, Object>> rows, String principalId) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<String, Double> exact = new LinkedHashMap<>();
		Map<String, Double> bm25 = retrievalIndex.bm25Scores(projectId, projectVersionId, catalogHash, contextHash,
				QueryCaseTextFeatures.queryTerms(question, 32), rows);
		Map<String, Double> vector = vectorScores(question, rows);
		Map<String, Double> structured = new LinkedHashMap<>();
		Map<String, Double> relevance = new LinkedHashMap<>();
		String normalized = normalizeText(question);
		for (Map<String, Object> row : rows) {
			String id = Objects.toString(row.get("id"));
			String candidate = normalizeText(Objects.toString(row.get("normalized_question")));
			exact.put(id, normalized.equals(candidate) ? 1d
					: normalized.contains(candidate) || candidate.contains(normalized) ? 0.5d : 0d);
			double candidateRelevance = relevanceScore(question, row);
			relevance.put(id, candidateRelevance);
			structured.put(id, candidateRelevance <= 0 ? 0 : candidateRelevance + qualityBonus(row));
		}
		Map<String, Double> fused = new LinkedHashMap<>();
		fuse(fused, exact, 4);
		fuse(fused, bm25, 2);
		fuse(fused, vector, 1.5);
		fuse(fused, structured, 2.5);
		return rows.stream()
			.filter(row -> repository.trustedForReuse(row, catalogHash))
			.filter(row -> scopeCompatible(row, principalId))
			.map(row -> {
				String id = Objects.toString(row.get("id"));
				return new ScoredCandidate(row, fused.getOrDefault(id, 0d), relevance.getOrDefault(id, 0d));
			})
			.filter(value -> value.score() > 0 && value.relevance() >= MIN_HINT_RELEVANCE)
			.sorted(Comparator.comparingDouble(ScoredCandidate::score)
				.reversed()
				.thenComparing(value -> Objects.toString(value.row().get("id"))))
			.toList();
	}

	private boolean scopeCompatible(Map<String, Object> row, String principalId) {
		String queryCaseId = Objects.toString(row.get("id"), "");
		if (StringUtils.hasText(queryCaseId)) {
			List<Map<String, Object>> persisted = repository.bindingDependencies(queryCaseId);
			if (!persisted.isEmpty()) {
				return scopeCompatible(persisted, principalId);
			}
		}
		SemanticBlueprint plan = readPlanJson(Objects.toString(row.get("typed_ir_json"), "")).orElse(null);
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

	private void fuse(Map<String, Double> target, Map<String, Double> scores, double weight) {
		List<Map.Entry<String, Double>> ranked = scores.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
			.toList();
		for (int index = 0; index < ranked.size(); index++) {
			target.merge(ranked.get(index).getKey(), weight * 1000d / (60 + index + 1), Double::sum);
		}
	}

	private Map<String, Double> vectorScores(String question, List<Map<String, Object>> rows) {
		if (embeddingModel.isEmpty()) {
			return lightweightVectorScores(question, rows);
		}
		try {
			List<float[]> queryVectors = EmbeddingModelSupport.embedTexts(embeddingModel.orElseThrow(), List.of(question));
			if (queryVectors.size() != 1 || queryVectors.get(0) == null || zero(queryVectors.get(0))) {
				return lightweightVectorScores(question, rows);
			}
			Map<String, Double> scores = retrievalIndex.vectorScores(queryVectors.get(0), rows);
			return scores.isEmpty() ? lightweightVectorScores(question, rows) : scores;
		}
		catch (RuntimeException ex) {
			log.warn("Query Case vector recall failed; falling back to deterministic lexical similarity", ex);
			return lightweightVectorScores(question, rows);
		}
	}

	private Map<String, Double> lightweightVectorScores(String question, List<Map<String, Object>> rows) {
		Set<String> query = bigrams(normalizeText(question));
		Map<String, Double> scores = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			Set<String> candidate = bigrams(normalizeText(Objects.toString(row.get("normalized_question"))));
			Set<String> intersection = new LinkedHashSet<>(query);
			intersection.retainAll(candidate);
			double denominator = Math.sqrt(Math.max(1, query.size()) * Math.max(1, candidate.size()));
			scores.put(Objects.toString(row.get("id")), intersection.size() / denominator);
		}
		return scores;
	}

	private boolean zero(float[] values) {
		for (float value : values) {
			if (Math.abs(value) > 1e-12) {
				return false;
			}
		}
		return true;
	}

	private double relevanceScore(String question, Map<String, Object> row) {
		double lexical = similarity(question, Objects.toString(row.get("normalized_question")));
		double structure = 0;
		Optional<SemanticBlueprint> plan = readPlanJson(Objects.toString(row.get("typed_ir_json"), ""));
		if (plan.isPresent()) {
			String normalized = normalizeText(question);
			structure += mentioned(normalized, plan.orElseThrow().getMetrics(),
					SemanticBlueprint.MetricSelection::getMetricCode,
					SemanticBlueprint.MetricSelection::getBusinessName) * 180;
			structure += mentioned(normalized, plan.orElseThrow().getDimensions(),
					SemanticBlueprint.DimensionSelection::getDimensionCode,
					SemanticBlueprint.DimensionSelection::getBusinessName) * 120;
			structure += plan.orElseThrow()
				.getEnumResolutions()
				.stream()
				.filter(value -> contains(normalized, value.getInputText(), value.getBusinessName(),
						value.getValueCode()))
				.count() * 100;
		}
		return lexical + structure;
	}

	private double qualityBonus(Map<String, Object> row) {
		return StringUtils.hasText(Objects.toString(row.get("quality_proof_json"), "")) ? 25 : 0;
	}

	private double relevanceConfidence(double relevance) {
		return Math.min(1d, Math.max(0d, relevance / 100d));
	}

	private <T> long mentioned(String normalized, List<T> values, Function<T, String> code,
			Function<T, String> businessName) {
		return values.stream()
			.filter(value -> contains(normalized, code.apply(value), businessName.apply(value)))
			.count();
	}

	private boolean contains(String normalized, String... candidates) {
		return java.util.Arrays.stream(candidates)
			.filter(StringUtils::hasText)
			.map(QueryCaseRecallService::normalizeText)
			.anyMatch(normalized::contains);
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

	private static double similarity(String left, String right) {
		String leftValue = normalizeText(left);
		String rightValue = normalizeText(right);
		if (leftValue.isBlank() || rightValue.isBlank()) {
			return 0;
		}
		if (leftValue.equals(rightValue)) {
			return 1000;
		}
		double containment = leftValue.contains(rightValue) || rightValue.contains(leftValue) ? 100 : 0;
		Set<String> leftBigrams = bigrams(leftValue);
		Set<String> rightBigrams = bigrams(rightValue);
		if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) {
			return containment;
		}
		Set<String> intersection = new LinkedHashSet<>(leftBigrams);
		intersection.retainAll(rightBigrams);
		Set<String> union = new LinkedHashSet<>(leftBigrams);
		union.addAll(rightBigrams);
		return containment + (100d * intersection.size() / union.size());
	}

	private static Set<String> bigrams(String value) {
		String compact = value.replaceAll("\\s+", "");
		Set<String> result = new LinkedHashSet<>();
		for (int index = 0; index + 1 < compact.length(); index++) {
			result.add(compact.substring(index, index + 2));
		}
		return result;
	}

	private static String normalizeText(String value) {
		return Objects.toString(value, "").trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
	}

	private record ScoredCase(String id, String intentType, SemanticBlueprint plan, double score, double relevance) {
	}

	private record ScoredCandidate(Map<String, Object> row, double score, double relevance) {
	}

	public record RecalledQueryCase(String id, String question, String sql, Integer datasourceId, String catalogHash,
			double score) {
	}

}
