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
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.Disposition;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Compares one incoming catalog patch with the current draft before merge. Assets whose
 * stable business definition conflicts with an existing asset are excluded from the
 * accepted patch and converted into blocking semantic gaps. Every incoming asset,
 * including rejected conflicts, receives immutable provenance.
 */
@Component
public class SemanticCatalogPatchAnalyzer {

	private static final int CONFLICT_PRIORITY = 20;

	private final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

	public PatchAnalysis analyze(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming,
			SemanticMaterial material, SemanticMaterialAttempt attempt) {
		if (incoming == null) {
			return PatchAnalysis.empty(material.getProjectId(), material.getProjectVersionId());
		}
		Accumulator accumulator = new Accumulator(material, attempt);
		SemanticCatalogSnapshot accepted = SemanticCatalogSnapshot.builder()
			.projectId(material.getProjectId())
			.projectVersionId(material.getProjectVersionId())
			.models(analyzeAssets(AssetType.MODEL, safe(current == null ? null : current.getModels()),
					safe(incoming.getModels()), SemanticCatalogSnapshot.Model::getModelCode, this::modelFingerprint,
					SemanticCatalogSnapshot.Model::getEvidence, accumulator))
			.columns(analyzeAssets(AssetType.COLUMN, safe(current == null ? null : current.getColumns()),
					safe(incoming.getColumns()), column -> key(column.getModelCode(), column.getColumnName()),
					this::columnFingerprint, SemanticCatalogSnapshot.Column::getEvidence, accumulator))
			.metrics(analyzeAssets(AssetType.METRIC, safe(current == null ? null : current.getMetrics()),
					safe(incoming.getMetrics()), SemanticCatalogSnapshot.Metric::getMetricCode, this::metricFingerprint,
					SemanticCatalogSnapshot.Metric::getEvidence, accumulator))
			.dimensions(analyzeAssets(AssetType.DIMENSION, safe(current == null ? null : current.getDimensions()),
					safe(incoming.getDimensions()), SemanticCatalogSnapshot.Dimension::getDimensionCode,
					this::dimensionFingerprint, SemanticCatalogSnapshot.Dimension::getEvidence, accumulator))
			.relationships(analyzeAssets(AssetType.RELATIONSHIP,
					safe(current == null ? null : current.getRelationships()), safe(incoming.getRelationships()),
					SemanticCatalogSnapshot.Relationship::getRelationshipCode, this::relationshipFingerprint,
					SemanticCatalogSnapshot.Relationship::getEvidence, accumulator))
			.grains(analyzeAssets(AssetType.GRAIN, safe(current == null ? null : current.getGrains()),
					safe(incoming.getGrains()), grain -> key(grain.getModelCode(), grain.getGrainCode()),
					this::grainFingerprint, SemanticCatalogSnapshot.Grain::getEvidence, accumulator))
			.enumValues(analyzeAssets(AssetType.ENUM_VALUE, safe(current == null ? null : current.getEnumValues()),
					safe(incoming.getEnumValues()),
					value -> key(key(value.getModelCode(), value.getColumnName()), value.getValueCode()),
					this::enumValueFingerprint, SemanticCatalogSnapshot.EnumValue::getEvidence, accumulator))
			.rules(analyzeAssets(AssetType.RULE, safe(current == null ? null : current.getRules()),
					safe(incoming.getRules()), SemanticCatalogSnapshot.Rule::getRuleCode, this::ruleFingerprint,
					SemanticCatalogSnapshot.Rule::getEvidence, accumulator))
			.build();
		return new PatchAnalysis(accepted, List.copyOf(accumulator.provenance), List.copyOf(accumulator.gaps),
				accumulator.appliedCount, accumulator.conflictCount);
	}

	private <T> List<T> analyzeAssets(AssetType type, List<T> current, List<T> incoming, Function<T, String> keyFn,
			Function<T, String> fingerprintFn, Function<T, String> evidenceFn, Accumulator accumulator) {
		Map<String, String> currentFingerprintByKey = new LinkedHashMap<>();
		Map<String, T> currentByKey = new LinkedHashMap<>();
		for (T asset : current) {
			String assetKey = requireKey(type, keyFn.apply(asset));
			currentFingerprintByKey.put(assetKey, fingerprintFn.apply(asset));
			currentByKey.put(assetKey, asset);
		}
		List<T> accepted = new ArrayList<>();
		Set<String> incomingKeys = new HashSet<>();
		for (T asset : incoming) {
			String assetKey = requireKey(type, keyFn.apply(asset));
			if (!incomingKeys.add(assetKey)) {
				throw new IllegalArgumentException(
						"Duplicate semantic asset key in one material: " + type + ":" + assetKey);
			}
			String incomingFingerprint = fingerprintFn.apply(asset);
			String currentFingerprint = currentFingerprintByKey.get(assetKey);
			boolean conflict = currentFingerprint != null && !currentFingerprint.equals(incomingFingerprint);
			SemanticGap conflictGap = conflict ? conflictGap(type, assetKey, currentFingerprint, incomingFingerprint,
					currentByKey.get(assetKey), asset, accumulator.material, accumulator.attempt) : null;
			accumulator.provenance.add(provenance(type, assetKey, incomingFingerprint,
					conflict ? Disposition.CONFLICT : Disposition.APPLIED,
					conflictGap == null ? null : conflictGap.getGapKey(), evidenceFn.apply(asset), accumulator));
			if (conflict) {
				accumulator.conflictCount++;
				accumulator.gaps.add(conflictGap);
			}
			else {
				accumulator.appliedCount++;
				accepted.add(asset);
			}
		}
		return accepted;
	}

	private SemanticAssetProvenance provenance(AssetType type, String assetKey, String fingerprint,
			Disposition disposition, String conflictGapKey, String evidence, Accumulator accumulator) {
		return SemanticAssetProvenance.builder()
			.projectId(accumulator.material.getProjectId())
			.projectVersionId(accumulator.material.getProjectVersionId())
			.materialId(accumulator.material.getId())
			.attemptId(accumulator.attempt.getId())
			.assetType(type)
			.assetKey(assetKey)
			.assetFingerprint(fingerprint)
			.disposition(disposition)
			.conflictGapKey(conflictGapKey)
			.confidence(confidence(accumulator.material.getMaterialType()))
			.sourceLocation(
					firstText(accumulator.attempt.getSourceLocation(), accumulator.material.getSourceLocation()))
			.extractionModel(firstText(accumulator.attempt.getExtractionModel(), "built-in-parser"))
			.evidence(evidence)
			.createTime(LocalDateTime.now())
			.build();
	}

	private SemanticGap conflictGap(AssetType type, String assetKey, String currentFingerprint,
			String incomingFingerprint, Object currentAsset, Object incomingAsset, SemanticMaterial material,
			SemanticMaterialAttempt attempt) {
		String conflictHash = sha256(
				type.name() + "|" + assetKey + "|" + currentFingerprint + "|" + incomingFingerprint);
		String gapKey = "semantic-conflict:" + type.name().toLowerCase() + ":" + conflictHash.substring(0, 24);
		String sourceName = firstText(material.getOriginalFilename(), material.getSourceName(),
				"document-" + material.getId());
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("assetType", type.name());
		evidence.put("assetKey", assetKey);
		evidence.put("existingFingerprint", currentFingerprint);
		evidence.put("incomingFingerprint", incomingFingerprint);
		evidence.put("currentAsset", currentAsset);
		evidence.put("incomingAsset", incomingAsset);
		evidence.put("materialId", material.getId());
		evidence.put("attemptId", attempt.getId());
		evidence.put("sourceLocation", firstText(attempt.getSourceLocation(), material.getSourceLocation(), "-"));
		return SemanticGap.openWithKey(material.getProjectId(), material.getProjectVersionId(), gapKey,
				"SEMANTIC_ASSET_CONFLICT",
				"文档 " + sourceName + " 对 " + type + " 资产 " + assetKey + " 的定义与当前 Semantic Catalog 不一致，应采用哪一份定义？",
				"回答 {\"choice\":\"CURRENT\"} 保留当前定义，或回答 {\"choice\":\"INCOMING\"} 采用该材料定义。", json(evidence),
				type + ":" + assetKey, CONFLICT_PRIORITY);
	}

	public String fingerprintAsset(AssetType type, Object asset) {
		return switch (type) {
			case MODEL -> modelFingerprint((SemanticCatalogSnapshot.Model) asset);
			case COLUMN -> columnFingerprint((SemanticCatalogSnapshot.Column) asset);
			case METRIC -> metricFingerprint((SemanticCatalogSnapshot.Metric) asset);
			case DIMENSION -> dimensionFingerprint((SemanticCatalogSnapshot.Dimension) asset);
			case RELATIONSHIP -> relationshipFingerprint((SemanticCatalogSnapshot.Relationship) asset);
			case GRAIN -> grainFingerprint((SemanticCatalogSnapshot.Grain) asset);
			case ENUM_VALUE -> enumValueFingerprint((SemanticCatalogSnapshot.EnumValue) asset);
			case RULE -> ruleFingerprint((SemanticCatalogSnapshot.Rule) asset);
		};
	}

	private String modelFingerprint(SemanticCatalogSnapshot.Model model) {
		return fingerprint(map("datasourceId", model.getDatasourceId(), "physicalTable", model.getPhysicalTable(),
				"modelType", model.getModelType(), "status", defaultStatus(model.getStatus())));
	}

	private String columnFingerprint(SemanticCatalogSnapshot.Column column) {
		return fingerprint(map("dataType", column.getDataType(), "role", column.getRole(), "expression",
				column.getExpression(), "nullable", column.getNullable(), "sensitivityLevel",
				column.getSensitivityLevel(), "maskingPolicy", column.getMaskingPolicy(), "allowAggregation",
				column.getAllowAggregation(), "allowFilter", column.getAllowFilter(), "allowProjection",
				column.getAllowProjection(), "allowExport", column.getAllowExport(), "allowSendToLlm",
				column.getAllowSendToLlm(), "status", defaultStatus(column.getStatus())));
	}

	private String metricFingerprint(SemanticCatalogSnapshot.Metric metric) {
		return fingerprint(map("modelCode", metric.getModelCode(), "expression", metric.getExpression(), "aggregation",
				metric.getAggregation(), "unit", metric.getUnit(), "timeColumn", metric.getTimeColumn(),
				"filterExpression", metric.getFilterExpression(), "additiveType", metric.getAdditiveType(), "status",
				defaultStatus(metric.getStatus())));
	}

	private String dimensionFingerprint(SemanticCatalogSnapshot.Dimension dimension) {
		return fingerprint(map("modelCode", dimension.getModelCode(), "columnName", dimension.getColumnName(),
				"expression", dimension.getExpression(), "dimensionType", dimension.getDimensionType(), "hierarchy",
				dimension.getHierarchy(), "status", defaultStatus(dimension.getStatus())));
	}

	private String relationshipFingerprint(SemanticCatalogSnapshot.Relationship relationship) {
		return fingerprint(map("sourceModelCode", relationship.getSourceModelCode(), "targetModelCode",
				relationship.getTargetModelCode(), "cardinality", relationship.getCardinality(), "joinType",
				relationship.getJoinType(), "joinCondition", relationship.getJoinCondition(), "status",
				defaultStatus(relationship.getStatus())));
	}

	private String grainFingerprint(SemanticCatalogSnapshot.Grain grain) {
		return fingerprint(map("keyColumns", grain.getKeyColumns(), "timeColumn", grain.getTimeColumn(),
				"uniquenessRule", grain.getUniquenessRule(), "status", defaultStatus(grain.getStatus())));
	}

	private String enumValueFingerprint(SemanticCatalogSnapshot.EnumValue value) {
		return fingerprint(map("sortOrder", value.getSortOrder(), "status", defaultStatus(value.getStatus())));
	}

	private String ruleFingerprint(SemanticCatalogSnapshot.Rule rule) {
		return fingerprint(map("modelCode", rule.getModelCode(), "ruleType", rule.getRuleType(), "expression",
				rule.getExpression(), "severity", rule.getSeverity(), "status", defaultStatus(rule.getStatus())));
	}

	private Map<String, Object> map(Object... pairs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			values.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		return values;
	}

	private String fingerprint(Map<String, Object> values) {
		return sha256(json(values));
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Unable to serialize semantic asset", ex);
		}
	}

	private BigDecimal confidence(SemanticMaterialType materialType) {
		return switch (materialType) {
			case JSON, YAML -> new BigDecimal("1.0000");
			case DDL -> new BigDecimal("0.9500");
			case MARKDOWN -> new BigDecimal("0.9000");
			case HISTORICAL_SQL -> new BigDecimal("0.7500");
		};
	}

	private SemanticAssetStatus defaultStatus(SemanticAssetStatus status) {
		return status == null ? SemanticAssetStatus.ENABLED : status;
	}

	private String requireKey(AssetType type, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Semantic asset key is required for " + type);
		}
		return value.trim();
	}

	private String key(String left, String right) {
		return String.valueOf(left) + ":" + String.valueOf(right);
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
	}

	public record PatchAnalysis(SemanticCatalogSnapshot acceptedPatch, List<SemanticAssetProvenance> provenance,
			List<SemanticGap> conflicts, int appliedCount, int conflictCount) {

		static PatchAnalysis empty(Long projectId, Long projectVersionId) {
			return new PatchAnalysis(
					SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(projectVersionId).build(),
					List.of(), List.of(), 0, 0);
		}

		public boolean hasAcceptedAssets() {
			return acceptedPatch != null && (size(acceptedPatch.getModels()) + size(acceptedPatch.getColumns())
					+ size(acceptedPatch.getMetrics()) + size(acceptedPatch.getDimensions())
					+ size(acceptedPatch.getRelationships()) + size(acceptedPatch.getGrains())
					+ size(acceptedPatch.getEnumValues()) + size(acceptedPatch.getRules()) > 0);
		}

		private static int size(List<?> values) {
			return values == null ? 0 : values.size();
		}
	}

	private static final class Accumulator {

		private final SemanticMaterial material;

		private final SemanticMaterialAttempt attempt;

		private final List<SemanticAssetProvenance> provenance = new ArrayList<>();

		private final List<SemanticGap> gaps = new ArrayList<>();

		private int appliedCount;

		private int conflictCount;

		private Accumulator(SemanticMaterial material, SemanticMaterialAttempt attempt) {
			this.material = material;
			this.attempt = attempt;
		}

	}

}
