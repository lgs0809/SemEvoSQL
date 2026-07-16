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
import cn.lgs.semevosql.semantic.domain.BusinessQueryRequirement;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario.Importance;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario.Status;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.semevosql.semantic.domain.MaterialLifecycle;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence.EvidenceType;
import cn.lgs.semevosql.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Persists raw material observations and DB-independent business query scenarios. */
@Service
@RequiredArgsConstructor
public class ProjectEvidenceScenarioService {

	private static final BigDecimal FULL_CONFIDENCE = BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);

	private final ProjectEvidenceRepository evidenceRepository;

	private final BusinessQueryScenarioRepository scenarioRepository;

	private final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

	public CaptureResult capture(SemanticMaterial material, SemanticMaterialAttempt attempt,
			SemanticMaterialParseResult parsed) {
		int evidenceCount = 0;
		SemanticCatalogSnapshot patch = parsed.catalogPatch();
		if (patch != null) {
			evidenceCount += captureAssets(material, attempt, EvidenceType.MODEL, patch.getModels(),
					value -> value.getModelCode());
			evidenceCount += captureAssets(material, attempt, EvidenceType.COLUMN, patch.getColumns(),
					value -> key(value.getModelCode(), value.getColumnName()));
			evidenceCount += captureAssets(material, attempt, EvidenceType.METRIC, patch.getMetrics(),
					SemanticCatalogSnapshot.Metric::getMetricCode);
			evidenceCount += captureAssets(material, attempt, EvidenceType.DIMENSION, patch.getDimensions(),
					SemanticCatalogSnapshot.Dimension::getDimensionCode);
			evidenceCount += captureAssets(material, attempt, EvidenceType.RELATIONSHIP, patch.getRelationships(),
					SemanticCatalogSnapshot.Relationship::getRelationshipCode);
			evidenceCount += captureAssets(material, attempt, EvidenceType.GRAIN, patch.getGrains(),
					value -> key(value.getModelCode(), value.getGrainCode()));
			evidenceCount += captureAssets(material, attempt, EvidenceType.ENUM_VALUE, patch.getEnumValues(),
					value -> key(key(value.getModelCode(), value.getColumnName()), value.getValueCode()));
			evidenceCount += captureAssets(material, attempt, EvidenceType.RULE, patch.getRules(),
					SemanticCatalogSnapshot.Rule::getRuleCode);
		}
		for (SemanticGap gap : parsed.gaps()) {
			if (gap == null) {
				continue;
			}
			String subjectKey = firstText(gap.getGapKey(), gap.getImpactScope(), gap.getGapType(), "review-signal");
			insertEvidence(material, attempt, EvidenceType.REVIEW_SIGNAL, subjectKey, gap, FULL_CONFIDENCE);
			evidenceCount++;
		}

		int createdScenarios = 0;
		for (BusinessQueryScenarioDraft draft : parsed.scenarios()) {
			if (draft == null || draft.requirement() == null || !hasText(draft.businessName())) {
				continue;
			}
			String requirementJson = json(draft.requirement());
			String fingerprint = scenarioFingerprint(draft.requirement());
			BigDecimal confidence = confidence(draft.confidence());
			insertEvidence(material, attempt, EvidenceType.BUSINESS_QUERY_SCENARIO, fingerprint,
					new ScenarioEvidence(draft.businessName(), draft.description(), draft.requirement(),
							draft.importance(), draft.evidence()),
					confidence);
			evidenceCount++;
			BusinessQueryScenario existing = scenarioRepository
				.findByFingerprint(material.getProjectVersionId(), fingerprint)
				.orElse(null);
			if (existing != null) {
				mergeScenarioMetadata(existing, draft, confidence);
				continue;
			}
			LocalDateTime now = LocalDateTime.now();
			BusinessQueryScenario scenario = BusinessQueryScenario.builder()
				.projectId(material.getProjectId())
				.projectVersionId(material.getProjectVersionId())
				.scenarioCode("scenario-" + fingerprint.substring(0, 16))
				.businessName(draft.businessName().trim())
				.description(trim(draft.description()))
				.requirementJson(requirementJson)
				.importance(draft.importance() == null ? Importance.DISCOVERED : draft.importance())
				.status(status(material.getLifecycle()))
				.sourceMaterialId(material.getId())
				.sourceAttemptId(attempt.getId())
				.sourceLocation(material.getSourceLocation())
				.confidence(confidence)
				.scenarioFingerprint(fingerprint)
				.createTime(now)
				.updateTime(now)
				.build();
			scenarioRepository.insert(scenario);
			createdScenarios++;
		}
		scenarioRepository.reconcileStatuses(material.getProjectVersionId());
		return new CaptureResult(evidenceCount, createdScenarios);
	}

	public int captureObservations(SemanticMaterial material, SemanticMaterialAttempt attempt,
			List<SourceCodeMaterialAnalyzer.Observation> observations) {
		int count = 0;
		for (SourceCodeMaterialAnalyzer.Observation observation : safe(observations)) {
			if (observation == null || observation.evidenceType() == null || !hasText(observation.subjectKey())) {
				continue;
			}
			insertEvidence(material, attempt, observation.evidenceType(), observation.subjectKey(),
					observation.payload(), confidence(observation.confidence()));
			count++;
		}
		return count;
	}

	public void reconcileVersion(Long projectVersionId) {
		scenarioRepository.reconcileStatuses(projectVersionId);
	}

	private <T> int captureAssets(SemanticMaterial material, SemanticMaterialAttempt attempt, EvidenceType type,
			List<T> values, java.util.function.Function<T, String> keyExtractor) {
		int count = 0;
		for (T value : safe(values)) {
			String subjectKey = trim(keyExtractor.apply(value));
			if (!hasText(subjectKey)) {
				continue;
			}
			insertEvidence(material, attempt, type, subjectKey, value, FULL_CONFIDENCE);
			count++;
		}
		return count;
	}

	private void insertEvidence(SemanticMaterial material, SemanticMaterialAttempt attempt, EvidenceType type,
			String subjectKey, Object payload, BigDecimal confidence) {
		String payloadJson = json(payload);
		evidenceRepository.insert(ProjectEvidence.builder()
			.projectId(material.getProjectId())
			.projectVersionId(material.getProjectVersionId())
			.materialId(material.getId())
			.attemptId(attempt.getId())
			.evidenceType(type)
			.subjectKey(subjectKey)
			.evidenceHash(sha256(canonical(payload)))
			.payloadJson(payloadJson)
			.confidence(confidence)
			.sourceLocation(material.getSourceLocation())
			.extractionModel(attempt.getExtractionModel())
			.createTime(LocalDateTime.now())
			.build());
	}

	private Status status(MaterialLifecycle lifecycle) {
		if (lifecycle == MaterialLifecycle.HISTORICAL) {
			return Status.HISTORICAL;
		}
		if (lifecycle == MaterialLifecycle.DEPRECATED) {
			return Status.DEPRECATED;
		}
		return Status.ACTIVE;
	}

	private BigDecimal confidence(Integer value) {
		int normalized = value == null ? 100 : Math.max(0, Math.min(100, value));
		return BigDecimal.valueOf(normalized).movePointLeft(2).setScale(4, RoundingMode.HALF_UP);
	}

	private String scenarioFingerprint(BusinessQueryRequirement requirement) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("measures", normalizedTerms(requirement.measures(), true));
		normalized.put("attributes", normalizedTerms(requirement.attributes(), true));
		normalized.put("filters", normalizedTerms(requirement.filters(), true));
		normalized.put("timeConstraints", normalizedTerms(requirement.timeConstraints(), true));
		normalized.put("groupings", normalizedTerms(requirement.groupings(), true));
		normalized.put("sorting", normalizedTerms(requirement.sorting(), false));
		normalized.put("limit", requirement.limit());
		normalized.put("comparison", normalizeScenarioTerm(requirement.comparison()));
		normalized.put("expectedShape", normalizeScenarioTerm(requirement.expectedShape()));
		return sha256(json(normalized));
	}

	private List<String> normalizedTerms(List<String> values, boolean sort) {
		java.util.stream.Stream<String> stream = safe(values).stream()
			.map(this::normalizeScenarioTerm)
			.filter(this::hasText)
			.distinct();
		if (sort) {
			stream = stream.sorted();
		}
		return stream.toList();
	}

	private String normalizeScenarioTerm(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private void mergeScenarioMetadata(BusinessQueryScenario existing, BusinessQueryScenarioDraft draft,
			BigDecimal incomingConfidence) {
		Importance incomingImportance = draft.importance() == null ? Importance.DISCOVERED : draft.importance();
		boolean changed = false;
		if (existing.getImportance() == null || incomingImportance.ordinal() < existing.getImportance().ordinal()) {
			existing.setImportance(incomingImportance);
			changed = true;
		}
		if (incomingConfidence != null
				&& (existing.getConfidence() == null || incomingConfidence.compareTo(existing.getConfidence()) > 0)) {
			existing.setConfidence(incomingConfidence);
			changed = true;
		}
		if (changed) {
			existing.setUpdateTime(LocalDateTime.now());
			scenarioRepository.update(existing);
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize project evidence", ex);
		}
	}

	private String canonical(Object value) {
		JsonNode node = objectMapper.valueToTree(value);
		if (node instanceof ObjectNode object) {
			object.remove(List.of("id", "projectId", "projectVersionId", "createTime", "updateTime", "evidence"));
		}
		return node.toString();
	}

	private String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
	}

	private String key(String left, String right) {
		return hasText(left) && hasText(right) ? left.trim() + ":" + right.trim() : null;
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (hasText(value)) {
				return value.trim();
			}
		}
		return "unknown";
	}

	private String trim(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record CaptureResult(int evidenceCount, int createdScenarioCount) {
	}

	private record ScenarioEvidence(String businessName, String description,
			cn.lgs.semevosql.semantic.domain.BusinessQueryRequirement requirement,
			Importance importance, String evidence) {
	}

}
