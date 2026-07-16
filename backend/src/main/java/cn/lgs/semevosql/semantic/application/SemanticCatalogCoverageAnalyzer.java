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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import org.springframework.stereotype.Component;

/**
 * Deterministic structural coverage checks for a draft Semantic Catalog. The analyzer
 * only emits gaps that can be proven from the current structured catalog and never
 * guesses business definitions.
 */
@Component
public class SemanticCatalogCoverageAnalyzer {

	private static final String GAP_PREFIX = "semantic-coverage:";

	private final MultiSourcePolicyService multiSourcePolicyService;

	public SemanticCatalogCoverageAnalyzer(MultiSourcePolicyService multiSourcePolicyService) {
		this.multiSourcePolicyService = multiSourcePolicyService;
	}

	public CoverageAnalysis analyze(SemanticCatalogSnapshot snapshot) {
		if (snapshot == null) {
			return new CoverageAnalysis(List.of(), 0, 0);
		}
		Long projectId = snapshot.getProjectId();
		Long versionId = snapshot.getProjectVersionId();
		Map<String, SemanticCatalogSnapshot.Model> models = safe(snapshot.getModels()).stream()
			.filter(model -> hasText(model.getModelCode()))
			.collect(Collectors.toMap(model -> model.getModelCode().trim(), model -> model, (left, right) -> left,
					LinkedHashMap::new));
		Map<String, Set<String>> columnsByModel = new LinkedHashMap<>();
		for (SemanticCatalogSnapshot.Column column : safe(snapshot.getColumns())) {
			if (!hasText(column.getModelCode()) || !hasText(column.getColumnName())) {
				continue;
			}
			columnsByModel.computeIfAbsent(column.getModelCode().trim(), ignored -> new LinkedHashSet<>())
				.add(column.getColumnName().trim());
		}
		Map<String, SemanticGap> gaps = new LinkedHashMap<>();
		int coveredModels = 0;
		for (String modelCode : models.keySet()) {
			Set<String> modelColumns = columnsByModel.getOrDefault(modelCode, Set.of());
			boolean hasColumns = !modelColumns.isEmpty();
			if (!hasColumns) {
				add(gaps,
						gap(projectId, versionId, "model:" + modelCode + ":columns", "MISSING_MODEL_COLUMNS",
								"模型 " + modelCode + " 尚未绑定任何字段，哪些物理字段属于该模型？", "补充数据库扫描、数据字典或逻辑字段绑定。",
								"Catalog 中不存在该模型的字段定义。", modelCode, 5));
			}
			else {
				coveredModels++;
			}
		}

		for (SemanticCatalogSnapshot.Metric metric : safe(snapshot.getMetrics())) {
			if (!hasText(metric.getMetricCode()) || !hasText(metric.getModelCode())) {
				continue;
			}
			if (!models.containsKey(metric.getModelCode().trim())) {
				add(gaps,
						gap(projectId, versionId, "metric:" + metric.getMetricCode() + ":model", "MISSING_METRIC_MODEL",
								"指标 " + metric.getMetricCode() + " 引用了不存在的模型 " + metric.getModelCode() + "，应绑定到哪个模型？",
								"修正指标所属模型或补充缺失模型。", "Metric.modelCode 无对应 Model。", "METRIC:" + metric.getMetricCode(),
								5));
			}
		}

		for (SemanticCatalogSnapshot.Dimension dimension : safe(snapshot.getDimensions())) {
			if (!hasText(dimension.getDimensionCode()) || !hasText(dimension.getModelCode())) {
				continue;
			}
			String modelCode = dimension.getModelCode().trim();
			if (!models.containsKey(modelCode)) {
				add(gaps,
						gap(projectId, versionId, "dimension:" + dimension.getDimensionCode() + ":model",
								"MISSING_DIMENSION_MODEL",
								"维度 " + dimension.getDimensionCode() + " 引用了不存在的模型 " + modelCode + "，应绑定到哪个模型？",
								"修正维度所属模型或补充缺失模型。", "Dimension.modelCode 无对应 Model。",
								"DIMENSION:" + dimension.getDimensionCode(), 10));
				continue;
			}
			if (!hasText(dimension.getColumnName()) && !hasText(dimension.getExpression())) {
				add(gaps, gap(projectId, versionId, "dimension:" + dimension.getDimensionCode() + ":binding",
						"MISSING_DIMENSION_BINDING", "维度 " + dimension.getDimensionCode() + " 尚未绑定物理字段或表达式，应如何计算？",
						"绑定 columnName 或定义受控 expression。", "Dimension 同时缺少 columnName 与 expression。",
						"DIMENSION:" + dimension.getDimensionCode(), 20));
			}
			else if (hasText(dimension.getColumnName())
					&& !columnsByModel.getOrDefault(modelCode, Set.of()).contains(dimension.getColumnName().trim())) {
				add(gaps,
						gap(projectId, versionId, "dimension:" + dimension.getDimensionCode() + ":column",
								"INVALID_DIMENSION_COLUMN",
								"维度 " + dimension.getDimensionCode() + " 绑定的字段 " + dimension.getColumnName()
										+ " 不存在，应改为哪个字段？",
								"修正字段绑定或补充逻辑字段定义。", "Dimension.columnName 无对应 Column。",
								"DIMENSION:" + dimension.getDimensionCode(), 10));
			}
		}

		for (SemanticCatalogSnapshot.Relationship relationship : safe(snapshot.getRelationships())) {
			if (!hasText(relationship.getRelationshipCode())) {
				continue;
			}
			List<String> missingModels = new ArrayList<>();
			if (!hasText(relationship.getSourceModelCode())
					|| !models.containsKey(relationship.getSourceModelCode().trim())) {
				missingModels.add(String.valueOf(relationship.getSourceModelCode()));
			}
			if (!hasText(relationship.getTargetModelCode())
					|| !models.containsKey(relationship.getTargetModelCode().trim())) {
				missingModels.add(String.valueOf(relationship.getTargetModelCode()));
			}
			if (!missingModels.isEmpty()) {
				add(gaps,
						gap(projectId, versionId, "relationship:" + relationship.getRelationshipCode() + ":models",
								"MISSING_RELATIONSHIP_MODEL",
								"关系 " + relationship.getRelationshipCode() + " 引用了不存在的模型 "
										+ String.join(", ", missingModels) + "，应如何修正？",
								"修正关系端点或补充缺失模型。", "Relationship 端点无对应 Model。",
								"RELATIONSHIP:" + relationship.getRelationshipCode(), 5));
			}
		}

		for (SemanticCatalogSnapshot.Grain grain : safe(snapshot.getGrains())) {
			if (!hasText(grain.getModelCode()) || !hasText(grain.getGrainCode())) {
				continue;
			}
			String modelCode = grain.getModelCode().trim();
			if (!models.containsKey(modelCode)) {
				add(gaps,
						gap(projectId, versionId, "grain:" + grain.getGrainCode() + ":model", "MISSING_GRAIN_MODEL",
								"粒度 " + grain.getGrainCode() + " 引用了不存在的模型 " + modelCode + "，应绑定到哪个模型？",
								"修正 Grain.modelCode 或补充缺失模型。", "Grain.modelCode 无对应 Model。",
								"GRAIN:" + grain.getGrainCode(), 5));
				continue;
			}
			Set<String> modelColumns = columnsByModel.getOrDefault(modelCode, Set.of());
			List<String> keyColumns = splitColumns(grain.getKeyColumns());
			List<String> missingKeys = keyColumns.stream().filter(column -> !modelColumns.contains(column)).toList();
			if (keyColumns.isEmpty() || !missingKeys.isEmpty()) {
				String defect = keyColumns.isEmpty() ? "没有定义键字段" : "引用了不存在的键字段 " + String.join(", ", missingKeys);
				add(gaps,
						gap(projectId, versionId, "grain:" + grain.getGrainCode() + ":keys",
								"INVALID_GRAIN_KEY_COLUMNS", "粒度 " + grain.getGrainCode() + defect + "，正确唯一键是什么？",
								"修正 keyColumns 或补充逻辑字段绑定。", "Grain.keyColumns 为空或无对应 Column。",
								"GRAIN:" + grain.getGrainCode(), 5));
			}
		}

		for (SemanticCatalogSnapshot.EnumValue value : safe(snapshot.getEnumValues())) {
			if (!hasText(value.getModelCode()) || !hasText(value.getColumnName()) || !hasText(value.getValueCode())) {
				continue;
			}
			String modelCode = value.getModelCode().trim();
			boolean valid = models.containsKey(modelCode)
					&& columnsByModel.getOrDefault(modelCode, Set.of()).contains(value.getColumnName().trim());
			if (!valid) {
				String asset = modelCode + ":" + value.getColumnName() + ":" + value.getValueCode();
				add(gaps, gap(projectId, versionId, "enum:" + asset + ":binding", "INVALID_ENUM_BINDING",
						"枚举值 " + value.getValueCode() + " 绑定的模型或字段不存在，应如何修正？", "修正枚举的 modelCode/columnName 或补充字段定义。",
						"EnumValue 无对应 Model/Column。", "ENUM_VALUE:" + asset, 20));
			}
		}

		for (SemanticCatalogSnapshot.Rule rule : safe(snapshot.getRules())) {
			if (!hasText(rule.getRuleCode()) || !hasText(rule.getModelCode())) {
				continue;
			}
			if (!models.containsKey(rule.getModelCode().trim())) {
				add(gaps,
						gap(projectId, versionId, "rule:" + rule.getRuleCode() + ":model", "MISSING_RULE_MODEL",
								"规则 " + rule.getRuleCode() + " 引用了不存在的模型 " + rule.getModelCode() + "，应如何修正？",
								"修正规则所属模型或补充缺失模型。", "Rule.modelCode 无对应 Model。", "RULE:" + rule.getRuleCode(), 20));
			}
		}

		analyzeCrossAssetConflicts(snapshot, projectId, versionId, gaps);

		if (projectId != null && versionId != null) {
			for (String violation : multiSourcePolicyService.validateForRelease(projectId, versionId, snapshot)) {
				String type = policyGapType(violation);
				int priority = policyGapPriority(type);
				String stableSuffix = Integer.toUnsignedString(violation.hashCode(), 16);
				add(gaps,
						gap(projectId, versionId, "multi-source:" + type.toLowerCase() + ":" + stableSuffix, type,
								policyQuestion(type, violation), policyRecommendation(type), violation,
								"MULTI_SOURCE_POLICY", priority));
			}
		}

		return new CoverageAnalysis(List.copyOf(gaps.values()), models.size(), coveredModels);
	}

	public String gapPrefix() {
		return GAP_PREFIX;
	}

	private SemanticGap gap(Long projectId, Long versionId, String keySuffix, String type, String question,
			String recommendation, String evidence, String impactScope, int priority) {
		return SemanticGap.openWithKey(projectId, versionId, GAP_PREFIX + keySuffix, type, question, recommendation,
				evidence, impactScope, priority);
	}

	private void add(Map<String, SemanticGap> gaps, SemanticGap gap) {
		gaps.putIfAbsent(gap.getGapKey(), gap);
	}

	private void analyzeCrossAssetConflicts(SemanticCatalogSnapshot snapshot, Long projectId, Long versionId,
			Map<String, SemanticGap> gaps) {
		Map<String, List<SemanticCatalogSnapshot.Metric>> metricsByBusinessName = safe(snapshot.getMetrics()).stream()
			.filter(metric -> hasText(metric.getBusinessName()) && hasText(metric.getExpression()))
			.collect(Collectors.groupingBy(metric -> normalizeBusinessName(metric.getBusinessName()),
					LinkedHashMap::new, Collectors.toList()));
		for (Map.Entry<String, List<SemanticCatalogSnapshot.Metric>> entry : metricsByBusinessName.entrySet()) {
			Set<String> expressions = entry.getValue()
				.stream()
				.map(SemanticCatalogSnapshot.Metric::getExpression)
				.map(this::normalizeExpression)
				.collect(Collectors.toCollection(LinkedHashSet::new));
			if (entry.getValue().size() > 1 && expressions.size() > 1) {
				String metricCodes = entry.getValue()
					.stream()
					.map(SemanticCatalogSnapshot.Metric::getMetricCode)
					.collect(Collectors.joining(", "));
				add(gaps, gap(projectId, versionId, "cross-asset:metric-name:" + stableKey(entry.getKey()),
						"CROSS_ASSET_METRIC_DEFINITION_CONFLICT", "业务名称相同的指标 " + metricCodes + " 使用了不同公式，哪一个是权威口径？",
						"统一指标代码和公式，或明确不同指标的业务名称、适用条件与 Authority Rule。",
						"businessName=" + entry.getKey() + "; expressions=" + expressions, "METRICS:" + metricCodes,
						15));
			}
		}

		Set<String> manySideModels = new LinkedHashSet<>();
		for (SemanticCatalogSnapshot.Relationship relationship : safe(snapshot.getRelationships())) {
			if (relationship.getCardinality() == null) {
				continue;
			}
			switch (relationship.getCardinality()) {
				case ONE_TO_MANY -> manySideModels.add(relationship.getTargetModelCode());
				case MANY_TO_ONE -> manySideModels.add(relationship.getSourceModelCode());
				case MANY_TO_MANY -> {
					manySideModels.add(relationship.getSourceModelCode());
					manySideModels.add(relationship.getTargetModelCode());
				}
				default -> {
				}
			}
		}
		for (SemanticCatalogSnapshot.Metric metric : safe(snapshot.getMetrics())) {
			if (!manySideModels.contains(metric.getModelCode()) || !isAdditiveMetric(metric)) {
				continue;
			}
			boolean hasGrain = safe(snapshot.getGrains()).stream()
				.anyMatch(
						grain -> metric.getModelCode().equals(grain.getModelCode()) && hasText(grain.getKeyColumns()));
			if (!hasGrain) {
				add(gaps,
						gap(projectId, versionId, "cross-asset:fanout:" + stableKey(metric.getMetricCode()),
								"FANOUT_METRIC_RISK",
								"指标 " + metric.getMetricCode() + " 位于多值关系一侧且缺少可靠 Grain，跨模型查询可能重复聚合，正确预聚合粒度是什么？",
								"补充 Grain，并要求 Planner 在关系连接前按该粒度预聚合。",
								"metric=" + metric.getMetricCode() + "; model=" + metric.getModelCode(),
								"METRIC:" + metric.getMetricCode(), 15));
			}
		}
	}

	private boolean isAdditiveMetric(SemanticCatalogSnapshot.Metric metric) {
		String aggregation = metric.getAggregation() == null ? "" : metric.getAggregation().trim().toUpperCase();
		String additiveType = metric.getAdditiveType() == null ? "" : metric.getAdditiveType().trim().toUpperCase();
		return "SUM".equals(aggregation) || additiveType.contains("ADDITIVE");
	}

	private String normalizeBusinessName(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
	}

	private String normalizeExpression(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
	}

	private String stableKey(String value) {
		return Integer.toUnsignedString(String.valueOf(value).hashCode(), 16);
	}

	private String policyGapType(String violation) {
		String normalized = violation == null ? "" : violation.toLowerCase();
		if (normalized.contains("logical binding") || normalized.contains("logical attribute")) {
			return "MISSING_LOGICAL_BINDING";
		}
		if (normalized.contains("authoritative") || normalized.contains("authority rule")) {
			return "MISSING_AUTHORITY_RULE";
		}
		if (normalized.contains("freshness")) {
			return "MISSING_FRESHNESS_POLICY";
		}
		if (normalized.contains("cross-source relationship") || normalized.contains("participate")) {
			return "MISSING_CROSS_SOURCE_RELATIONSHIP";
		}
		if (normalized.contains("merge policy")) {
			return "MISSING_MERGE_POLICY";
		}
		return "INVALID_MULTI_SOURCE_POLICY";
	}

	private int policyGapPriority(String type) {
		return switch (type) {
			case "MISSING_LOGICAL_BINDING" -> 30;
			case "MISSING_AUTHORITY_RULE" -> 40;
			case "MISSING_FRESHNESS_POLICY" -> 50;
			case "MISSING_CROSS_SOURCE_RELATIONSHIP" -> 60;
			case "MISSING_MERGE_POLICY" -> 70;
			default -> 80;
		};
	}

	private String policyQuestion(String type, String violation) {
		return switch (type) {
			case "MISSING_LOGICAL_BINDING" -> "请确认逻辑属性对应的数据源、模型、物理字段或受控表达式。当前问题：" + violation;
			case "MISSING_AUTHORITY_RULE" -> "同一逻辑资产存在多个来源时，哪个数据源是权威来源，哪些是副本、快照或备用？当前问题：" + violation;
			case "MISSING_FRESHNESS_POLICY" -> "请确认数据源的业务日期字段、时区、更新类型、延迟分钟数和可用截止规则。当前问题：" + violation;
			case "MISSING_CROSS_SOURCE_RELATIONSHIP" -> "请确认跨源两侧模型、关联键、基数、空值策略、唯一性和转换规则。当前问题：" + violation;
			case "MISSING_MERGE_POLICY" -> "请确认跨源结果的合并类型、键、粒度、空值、重复、行数上限和部分失败策略。当前问题：" + violation;
			default -> "请修复多数据源语义策略。当前问题：" + violation;
		};
	}

	private String policyRecommendation(String type) {
		return switch (type) {
			case "MISSING_LOGICAL_BINDING" -> "先完成逻辑属性到物理字段或表达式的绑定。";
			case "MISSING_AUTHORITY_RULE" -> "每个多来源逻辑资产只设置一个 AUTHORITATIVE 来源。";
			case "MISSING_FRESHNESS_POLICY" -> "为每个启用数据源配置可验证的 Freshness Policy。";
			case "MISSING_CROSS_SOURCE_RELATIONSHIP" -> "仅保存已经确认且键、基数与唯一性明确的跨源关系。";
			case "MISSING_MERGE_POLICY" -> "配置有限结果集 Merge Policy，禁止无界明细回传应用层。";
			default -> "修复策略后重新执行 Coverage 分析。";
		};
	}

	private List<String> splitColumns(String value) {
		if (!hasText(value)) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(this::hasText).distinct().toList();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
	}

	public record CoverageAnalysis(List<SemanticGap> gaps, int totalModelCount, int coveredModelCount) {
	}

}
