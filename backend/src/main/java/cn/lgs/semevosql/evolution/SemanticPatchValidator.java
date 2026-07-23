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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Side-effect-free, structured validation for the allow-listed Semantic Patch DSL. It
 * intentionally rejects governance/security fields and stale evidence before a candidate
 * can be approved or applied.
 */
@Service
@RequiredArgsConstructor
public class SemanticPatchValidator {

	private static final String TRUE_AMBIGUITY_MESSAGE = "TRUE_AMBIGUITY requires explicit semantic resolution and cannot be auto-approved or applied";

	private static final Pattern ASSET_KEY = Pattern.compile("[A-Za-z0-9_.$:-]{1,500}");

	private static final Pattern PROJECT_ALIAS_KEY = Pattern.compile("[\\p{L}\\p{N}_]{1,500}");

	private static final Pattern QUALIFIED_COLUMN = Pattern
		.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");

	private static final Pattern UNSAFE_EXPRESSION = Pattern
		.compile("(?is)(;|--|/\\*|\\b(insert|update|delete|drop|alter|create|grant|revoke|call|load_file|outfile)\\b)");

	private static final Set<String> PROTECTED_FIELDS = Set.of("sensitivityLevel", "maskingPolicy", "allowAggregation",
			"allowFilter", "allowProjection", "allowExport", "allowSendToLlm", "datasourceId", "physicalTable",
			"permissions");

	private static final Map<OperationType, Rule> RULES = rules();

	private final JdbcTemplate jdbc;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	public ValidationReport validateCandidate(String candidateId, SemanticPatch patch) {
		Map<String, Object> candidate = candidate(candidateId);
		List<Violation> violations = new ArrayList<>();
		Long projectId = number(candidate.get("project_id"));
		Long sourceVersionId = number(candidate.get("source_version_id"));
		String sourceHash = text(candidate.get("source_catalog_hash"));
		if ("TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")).toUpperCase(Locale.ROOT))) {
			return new ValidationReport(false,
					List.of(error("TRUE_AMBIGUITY_REQUIRES_RESOLUTION", "$", TRUE_AMBIGUITY_MESSAGE)), List.of(), 0, 0);
		}
		if (patch == null) {
			return new ValidationReport(false, List.of(error("PATCH_REQUIRED", "$", "Semantic Patch is required")),
					List.of(), 0, 0);
		}
		if (!Objects.equals(sourceVersionId, patch.sourceVersionId())
				|| !Objects.equals(sourceHash, patch.sourceCatalogHash())) {
			violations.add(error("SOURCE_PIN_MISMATCH", "$",
					"sourceVersionId and sourceCatalogHash are immutable and must match the candidate"));
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, sourceVersionId);
		validateOperations(projectId, sourceVersionId, patch, catalog, violations);
		List<Violation> errors = violations.stream().filter(item -> item.severity() == Severity.ERROR).toList();
		List<Violation> warnings = violations.stream().filter(item -> item.severity() == Severity.WARNING).toList();
		return new ValidationReport(errors.isEmpty(), errors, warnings, patch.operations().size(),
				(int) patch.operations().stream().filter(this::highRisk).count());
	}

	public ValidationReport validatePatch(Long projectId, Long sourceVersionId, SemanticPatch patch) {
		List<Violation> violations = new ArrayList<>();
		if (projectId == null || sourceVersionId == null || patch == null) {
			return new ValidationReport(false,
					List.of(error("PATCH_CONTEXT_REQUIRED", "$", "projectId, sourceVersionId and Semantic Patch are required")),
					List.of(), 0, 0);
		}
		String sourceHash = jdbc.query("""
				SELECT COALESCE(semantic_state_hash, catalog_hash)
				FROM qw_project_version WHERE id = ? AND project_id = ? AND status = 'PUBLISHED'
				""", (rs, rowNum) -> rs.getString(1), sourceVersionId, projectId).stream().findFirst().orElse(null);
		if (!Objects.equals(sourceVersionId, patch.sourceVersionId())
				|| !Objects.equals(sourceHash, patch.sourceCatalogHash())) {
			violations.add(error("SOURCE_PIN_MISMATCH", "$",
					"sourceVersionId and sourceCatalogHash must match the published base Semantic Version"));
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, sourceVersionId);
		validateOperations(projectId, sourceVersionId, patch, catalog, violations);
		List<Violation> errors = violations.stream().filter(item -> item.severity() == Severity.ERROR).toList();
		List<Violation> warnings = violations.stream().filter(item -> item.severity() == Severity.WARNING).toList();
		return new ValidationReport(errors.isEmpty(), errors, warnings, patch.operations().size(),
				(int) patch.operations().stream().filter(this::highRisk).count());
	}

	public void requireValid(String candidateId, SemanticPatch patch) {
		ValidationReport report = validateCandidate(candidateId, patch);
		if (!report.valid()) {
			throw new SemanticPatchValidationException(report);
		}
	}

	public void requireValid(Long projectId, Long sourceVersionId, SemanticPatch patch) {
		ValidationReport report = validatePatch(projectId, sourceVersionId, patch);
		if (!report.valid()) {
			throw new SemanticPatchValidationException(report);
		}
	}

	public String effectiveRiskLevel(SemanticPatch patch, String baselineRiskLevel) {
		String baseline = StringUtils.hasText(baselineRiskLevel)
				? baselineRiskLevel.trim().toUpperCase(Locale.ROOT) : "MEDIUM";
		if (!Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(baseline)) {
			throw new IllegalArgumentException("Unsupported semantic risk level: " + baselineRiskLevel);
		}
		boolean patchHighRisk = patch != null && patch.operations().stream().anyMatch(this::highRisk);
		if (patchHighRisk && ("LOW".equals(baseline) || "MEDIUM".equals(baseline))) {
			return "HIGH";
		}
		return baseline;
	}

	public List<AssetDiff> assetDiff(String candidateId, SemanticPatch patch) {
		Map<String, Object> candidate = candidate(candidateId);
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(number(candidate.get("project_id")),
				number(candidate.get("source_version_id")));
		List<AssetDiff> result = new ArrayList<>();
		for (Operation operation : patch.operations()) {
			Object current = currentAsset(operation, catalog);
			Map<String, Object> before = current == null ? Map.of() : JsonUtil.getObjectMapper()
				.convertValue(current, new com.fasterxml.jackson.core.type.TypeReference<>() {
				});
			Map<String, Object> after = new LinkedHashMap<>(before);
			after.putAll(operation.values());
			result.add(new AssetDiff(operation.operation().name(), operation.assetType(), operation.assetKey(),
					operation.expectedCurrentFingerprint(), java.util.Collections.unmodifiableMap(before),
					java.util.Collections.unmodifiableMap(after), highRisk(operation)));
		}
		return List.copyOf(result);
	}

	private void validateOperations(Long projectId, Long sourceVersionId, SemanticPatch patch,
			SemanticCatalogSnapshot catalog, List<Violation> violations) {
		Map<String, Operation> seen = new LinkedHashMap<>();
		Set<String> models = catalog.getModels()
			.stream()
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Map<String, Set<String>> columns = new LinkedHashMap<>();
		catalog.getColumns()
			.forEach(column -> columns.computeIfAbsent(column.getModelCode(), ignored -> new LinkedHashSet<>())
				.add(column.getColumnName()));
		for (int index = 0; index < patch.operations().size(); index++) {
			Operation operation = patch.operations().get(index);
			String path = "$.operations[" + index + "]";
			Rule rule = RULES.get(operation.operation());
			if (rule == null) {
				violations.add(error("UNSUPPORTED_OPERATION", path + ".operation", "Operation is not supported"));
				continue;
			}
			if (!rule.assetType().equalsIgnoreCase(operation.assetType())) {
				violations.add(error("ASSET_TYPE_MISMATCH", path + ".assetType",
						"Operation " + operation.operation() + " requires assetType " + rule.assetType()));
			}
			if (!validAssetKey(operation)) {
				violations.add(error("INVALID_ASSET_KEY", path + ".assetKey",
						operation.operation() == OperationType.ADD_PROJECT_ALIAS
								? "Project Alias assetKey must be a normalized natural-language phrase of at most 500 characters"
								: "assetKey must use stable code characters and be at most 500 characters"));
			}
			boolean add = operation.operation().name().startsWith("ADD_")
					&& operation.operation() != OperationType.ADD_COLUMN_SYNONYM
					&& operation.operation() != OperationType.ADD_ENUM_ALIAS;
			if (add && StringUtils.hasText(operation.expectedCurrentFingerprint())) {
				violations.add(error("ADD_WITH_FINGERPRINT", path + ".expectedCurrentFingerprint",
						"ADD operations must not carry an existing asset fingerprint"));
			}
			if (!add && !StringUtils.hasText(operation.expectedCurrentFingerprint())) {
				violations.add(error("FINGERPRINT_REQUIRED", path + ".expectedCurrentFingerprint",
						"UPDATE and ALIAS operations require the current asset fingerprint"));
			}
			for (String required : rule.requiredFields()) {
				if (!hasValue(operation.values().get(required))) {
					violations
						.add(error("REQUIRED_VALUE_MISSING", path + ".values." + required, required + " is required"));
				}
			}
			for (String field : operation.values().keySet()) {
				if (PROTECTED_FIELDS.contains(field)) {
					violations.add(error("GOVERNANCE_FIELD_FORBIDDEN", path + ".values." + field,
							"Semantic Patch cannot change permissions, sensitivity, masking, export or LLM exposure"));
				}
				else if (!rule.allowedFields().contains(field)) {
					violations.add(error("UNKNOWN_VALUE_FIELD", path + ".values." + field,
							"Field is not allowed for " + operation.operation()));
				}
			}
			String identity = rule.assetType().toUpperCase(Locale.ROOT) + ":" + operation.assetKey();
			Operation previous = seen.putIfAbsent(identity, operation);
			if (previous != null) {
				violations.add(error("DUPLICATE_ASSET_OPERATION", path,
						"Patch contains duplicate or conflicting operations for " + identity));
			}
			validateEvidence(projectId, sourceVersionId, operation, path, violations);
			validateReferences(operation, path, catalog, models, columns, violations);
			validateCurrentFingerprint(operation, path, catalog, violations);
		}
	}

	private void validateReferences(Operation operation, String path, SemanticCatalogSnapshot catalog,
			Set<String> models, Map<String, Set<String>> columns, List<Violation> violations) {
		if (operation.operation() == OperationType.ADD_PROJECT_ALIAS && !validProjectAliasTarget(operation)) {
			violations.add(error("INVALID_TARGET_ASSET_KEY", path + ".values.targetAssetKey",
					"Project Alias targetAssetKey must use stable code characters and be at most 500 characters"));
		}
		String model = value(operation, "modelCode");
		if (StringUtils.hasText(model) && !models.contains(model)) {
			violations.add(error("MODEL_NOT_FOUND", path + ".values.modelCode", "Referenced model does not exist"));
		}
		if (operation.operation() == OperationType.ADD_RELATIONSHIP
				|| operation.operation() == OperationType.UPDATE_RELATIONSHIP) {
			String source = value(operation, "sourceModelCode");
			String target = value(operation, "targetModelCode");
			if (StringUtils.hasText(source) && !models.contains(source)) {
				violations.add(error("SOURCE_MODEL_NOT_FOUND", path + ".values.sourceModelCode",
						"Relationship source model does not exist"));
			}
			if (StringUtils.hasText(target) && !models.contains(target)) {
				violations.add(error("TARGET_MODEL_NOT_FOUND", path + ".values.targetModelCode",
						"Relationship target model does not exist"));
			}
			String join = value(operation, "joinCondition");
			validateExpression(join, path + ".values.joinCondition", violations);
			validateQualifiedColumns(join, path + ".values.joinCondition", columns, "JOIN_COLUMN_NOT_FOUND",
					violations);
		}
		if (operation.operation() == OperationType.ADD_METRIC || operation.operation() == OperationType.UPDATE_METRIC) {
			String expression = value(operation, "expression");
			String filter = value(operation, "filterExpression");
			validateExpression(expression, path + ".values.expression", violations);
			validateExpression(filter, path + ".values.filterExpression", violations);
			validateQualifiedColumns(expression, path + ".values.expression", columns, "METRIC_COLUMN_NOT_FOUND",
					violations);
			validateQualifiedColumns(filter, path + ".values.filterExpression", columns, "METRIC_COLUMN_NOT_FOUND",
					violations);
		}
		if (operation.operation() == OperationType.ADD_RULE || operation.operation() == OperationType.UPDATE_RULE) {
			String expression = value(operation, "expression");
			String ruleType = value(operation, "ruleType");
			if (!StringUtils.hasText(ruleType) && operation.operation() == OperationType.UPDATE_RULE) {
				ruleType = catalog.getRules().stream()
					.filter(rule -> Objects.equals(operation.assetKey(), rule.getRuleCode()))
					.map(SemanticCatalogSnapshot.Rule::getRuleType)
					.findFirst()
					.orElse("");
			}
			if ("PLANNING_POLICY".equalsIgnoreCase(ruleType)) {
				validatePlanningPolicy(expression, path + ".values.expression", violations);
			}
			else {
				validateExpression(expression, path + ".values.expression", violations);
				validateQualifiedColumns(expression, path + ".values.expression", columns, "RULE_COLUMN_NOT_FOUND",
						violations);
			}
		}
		if (operation.operation() == OperationType.ADD_GRAIN || operation.operation() == OperationType.UPDATE_GRAIN) {
			String grainModel = StringUtils.hasText(model) ? model : modelForGrain(catalog, operation.assetKey());
			String keys = value(operation, "keyColumns");
			if (StringUtils.hasText(keys) && StringUtils.hasText(grainModel)) {
				for (String key : keys.split(",")) {
					if (!columns.getOrDefault(grainModel, Set.of()).contains(key.trim())) {
						violations.add(error("GRAIN_COLUMN_NOT_FOUND", path + ".values.keyColumns",
								"Grain key column does not exist on model " + grainModel + ": " + key.trim()));
					}
				}
			}
		}
	}

	boolean validAssetKey(Operation operation) {
		if (operation == null || operation.assetKey() == null) {
			return false;
		}
		Pattern pattern = operation.operation() == OperationType.ADD_PROJECT_ALIAS ? PROJECT_ALIAS_KEY : ASSET_KEY;
		return pattern.matcher(operation.assetKey()).matches();
	}

	boolean validProjectAliasTarget(Operation operation) {
		return operation != null && operation.operation() == OperationType.ADD_PROJECT_ALIAS
				&& ASSET_KEY.matcher(value(operation, "targetAssetKey")).matches();
	}

	private void validateCurrentFingerprint(Operation operation, String path, SemanticCatalogSnapshot catalog,
			List<Violation> violations) {
		if (!StringUtils.hasText(operation.expectedCurrentFingerprint())) {
			return;
		}
		Object current = currentAsset(operation, catalog);
		if (current == null) {
			violations.add(error("ASSET_NOT_FOUND", path + ".assetKey", "Current asset does not exist"));
			return;
		}
		AssetType type;
		try {
			type = AssetType.valueOf(RULES.get(operation.operation()).assetType());
		}
		catch (IllegalArgumentException ex) {
			type = operation.operation() == OperationType.ADD_COLUMN_SYNONYM ? AssetType.COLUMN : AssetType.ENUM_VALUE;
		}
		String actual = patchAnalyzer.fingerprintAsset(type, current);
		if (!Objects.equals(actual, operation.expectedCurrentFingerprint())) {
			violations.add(error("STALE_ASSET_FINGERPRINT", path + ".expectedCurrentFingerprint",
					"Asset changed after the Patch was proposed"));
		}
	}

	private Object currentAsset(Operation operation, SemanticCatalogSnapshot catalog) {
		return switch (operation.operation()) {
			case UPDATE_MODEL -> catalog.getModels()
				.stream()
				.filter(value -> Objects.equals(value.getModelCode(), operation.assetKey()))
				.findFirst()
				.orElse(null);
			case ADD_COLUMN_SYNONYM, UPDATE_COLUMN -> catalog.getColumns()
				.stream()
				.filter(value -> (value.getModelCode() + ":" + value.getColumnName()).equals(operation.assetKey()))
				.findFirst()
				.orElse(null);
			case ADD_ENUM_ALIAS, UPDATE_ENUM_VALUE -> catalog.getEnumValues()
				.stream()
				.filter(value -> (value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode())
					.equals(operation.assetKey()))
				.findFirst()
				.orElse(null);
			case UPDATE_METRIC -> catalog.getMetrics()
				.stream()
				.filter(value -> Objects.equals(value.getMetricCode(), operation.assetKey()))
				.findFirst()
				.orElse(null);
			case UPDATE_DIMENSION -> catalog.getDimensions()
				.stream()
				.filter(value -> Objects.equals(value.getDimensionCode(), operation.assetKey()))
				.findFirst()
				.orElse(null);
			case UPDATE_RELATIONSHIP -> catalog.getRelationships()
				.stream()
				.filter(value -> Objects.equals(value.getRelationshipCode(), operation.assetKey()))
				.findFirst()
				.orElse(null);
			case UPDATE_GRAIN -> catalog.getGrains()
				.stream()
				.filter(value -> (value.getModelCode() + "." + value.getGrainCode()).equals(operation.assetKey()))
				.findFirst()
				.orElse(null);
			case UPDATE_RULE -> catalog.getRules()
				.stream()
				.filter(value -> Objects.equals(value.getRuleCode(), operation.assetKey()))
				.findFirst()
				.orElse(null);
			default -> null;
		};
	}

	private void validateEvidence(Long projectId, Long sourceVersionId, Operation operation, String path,
			List<Violation> violations) {
		if (operation.evidenceCaseIds().isEmpty()) {
			violations.add(warning("EVIDENCE_EMPTY", path + ".evidenceCaseIds",
					"No reviewed Query Case is attached to this operation"));
			return;
		}
		for (String caseId : operation.evidenceCaseIds()) {
			Integer count = jdbc.queryForObject("""
					SELECT COUNT(*) FROM qw_query_example
					WHERE id = ? AND project_id = ? AND project_version_id = ? AND status = 'APPROVED'
					""", Integer.class, caseId, projectId, sourceVersionId);
			if (count == null || count != 1) {
				violations.add(error("INVALID_EVIDENCE_CASE", path + ".evidenceCaseIds",
						"Evidence case must be approved and belong to the same project/source version: " + caseId));
			}
		}
	}

	private void validatePlanningPolicy(String policy, String path, List<Violation> violations) {
		if (!StringUtils.hasText(policy)) {
			violations.add(error("PLANNING_POLICY_EMPTY", path, "Planning policy text is required"));
			return;
		}
		if (policy.length() > 2000) {
			violations.add(error("PLANNING_POLICY_TOO_LONG", path, "Planning policy text must be at most 2000 characters"));
		}
	}

	private void validateExpression(String expression, String path, List<Violation> violations) {
		if (StringUtils.hasText(expression) && UNSAFE_EXPRESSION.matcher(expression).find()) {
			violations.add(error("UNSAFE_EXPRESSION", path,
					"Expression contains statement separators, comments, DML, DDL or a dangerous function"));
		}
	}

	private void validateQualifiedColumns(String expression, String path, Map<String, Set<String>> columns, String code,
			List<Violation> violations) {
		if (!StringUtils.hasText(expression)) {
			return;
		}
		Matcher matcher = QUALIFIED_COLUMN.matcher(expression);
		while (matcher.find()) {
			if (!columns.getOrDefault(matcher.group(1), Set.of()).contains(matcher.group(2))) {
				violations.add(
						error(code, path, "Expression references an unknown or disallowed column: " + matcher.group()));
			}
		}
	}

	private boolean highRisk(Operation operation) {
		return Set
			.of(OperationType.ADD_METRIC, OperationType.UPDATE_METRIC, OperationType.ADD_RELATIONSHIP,
					OperationType.UPDATE_RELATIONSHIP, OperationType.ADD_GRAIN, OperationType.UPDATE_GRAIN,
					OperationType.ADD_RULE, OperationType.UPDATE_RULE)
			.contains(operation.operation());
	}

	private Map<String, Object> candidate(String candidateId) {
		List<Map<String, Object>> values = jdbc
			.queryForList("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Semantic evolution candidate not found: " + candidateId);
		}
		return values.get(0);
	}

	private String modelForGrain(SemanticCatalogSnapshot catalog, String assetKey) {
		return catalog.getGrains()
			.stream()
			.filter(value -> (value.getModelCode() + ":" + value.getGrainCode()).equals(assetKey))
			.map(SemanticCatalogSnapshot.Grain::getModelCode)
			.findFirst()
			.orElse(null);
	}

	private String value(Operation operation, String key) {
		Object value = operation.values().get(key);
		return value == null ? null : Objects.toString(value).trim();
	}

	private boolean hasValue(Object value) {
		return value != null && (!(value instanceof String text) || StringUtils.hasText(text));
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private static Violation error(String code, String path, String message) {
		return new Violation(Severity.ERROR, code, path, message);
	}

	private static Violation warning(String code, String path, String message) {
		return new Violation(Severity.WARNING, code, path, message);
	}

	private static Map<OperationType, Rule> rules() {
		Map<OperationType, Rule> values = new LinkedHashMap<>();
		values.put(OperationType.ADD_COLUMN_SYNONYM, rule("COLUMN", Set.of("synonym"), Set.of("synonym")));
		values.put(OperationType.UPDATE_MODEL,
				rule("MODEL", Set.of(), Set.of("businessName", "modelType", "description")));
		values.put(OperationType.UPDATE_COLUMN,
				rule("COLUMN", Set.of(), Set.of("businessName", "role", "expression", "synonyms", "description")));
		values.put(OperationType.ADD_ENUM_ALIAS, rule("ENUM_VALUE", Set.of("alias"), Set.of("alias")));
		values.put(OperationType.ADD_PROJECT_ALIAS,
				rule("PROJECT_ALIAS", Set.of("phrase", "targetAssetType", "targetAssetKey", "businessLabel"),
						Set.of("phrase", "targetAssetType", "targetAssetKey", "businessLabel")));
		values.put(OperationType.ADD_ENUM_VALUE,
				rule("ENUM_VALUE", Set.of("modelCode", "columnName", "valueCode", "businessName"), Set.of("modelCode",
						"columnName", "valueCode", "businessName", "aliases", "description", "sortOrder", "evidence")));
		values.put(OperationType.UPDATE_ENUM_VALUE,
				rule("ENUM_VALUE", Set.of(), Set.of("businessName", "aliases", "description", "sortOrder")));
		values.put(OperationType.ADD_METRIC,
				rule("METRIC", Set.of("modelCode", "metricCode", "businessName", "expression"),
						Set.of("modelCode", "metricCode", "businessName", "expression", "aggregation", "unit",
								"timeColumn", "filterExpression", "additiveType", "description", "evidence")));
		values.put(OperationType.UPDATE_METRIC, rule("METRIC", Set.of(), Set.of("businessName", "expression",
				"aggregation", "unit", "timeColumn", "filterExpression", "additiveType", "description")));
		values.put(OperationType.ADD_DIMENSION,
				rule("DIMENSION", Set.of("modelCode", "dimensionCode", "businessName"),
						Set.of("modelCode", "dimensionCode", "businessName", "columnName", "expression",
								"dimensionType", "hierarchy", "description", "evidence")));
		values.put(OperationType.UPDATE_DIMENSION, rule("DIMENSION", Set.of(),
				Set.of("businessName", "columnName", "expression", "dimensionType", "hierarchy", "description")));
		values.put(OperationType.ADD_RELATIONSHIP,
				rule("RELATIONSHIP",
						Set.of("relationshipCode", "sourceModelCode", "targetModelCode", "cardinality",
								"joinCondition"),
						Set.of("relationshipCode", "sourceModelCode", "targetModelCode", "cardinality", "joinType",
								"joinCondition", "description", "evidence")));
		values.put(OperationType.UPDATE_RELATIONSHIP, rule("RELATIONSHIP", Set.of(), Set.of("sourceModelCode",
				"targetModelCode", "cardinality", "joinType", "joinCondition", "description")));
		values.put(OperationType.ADD_GRAIN, rule("GRAIN", Set.of("modelCode", "grainCode", "keyColumns"), Set
			.of("modelCode", "grainCode", "keyColumns", "timeColumn", "uniquenessRule", "description", "evidence")));
		values.put(OperationType.UPDATE_GRAIN,
				rule("GRAIN", Set.of(), Set.of("keyColumns", "timeColumn", "uniquenessRule", "description")));
		values.put(OperationType.ADD_RULE,
				rule("RULE", Set.of("ruleCode", "ruleType", "businessName", "expression"), Set.of("modelCode",
						"ruleCode", "ruleType", "businessName", "expression", "severity", "description", "evidence")));
		values.put(OperationType.UPDATE_RULE,
				rule("RULE", Set.of(), Set.of("ruleType", "businessName", "expression", "severity", "description")));
		return Map.copyOf(values);
	}

	private static Rule rule(String assetType, Set<String> requiredFields, Set<String> allowedFields) {
		return new Rule(assetType, requiredFields, allowedFields);
	}

	private record Rule(String assetType, Set<String> requiredFields, Set<String> allowedFields) {
	}

	public enum Severity {

		ERROR, WARNING

	}

	public record Violation(Severity severity, String code, String path, String message) {
	}

	public record ValidationReport(boolean valid, List<Violation> errors, List<Violation> warnings, int operationCount,
			int highRiskOperationCount) {
	}

	public record AssetDiff(String operation, String assetType, String assetKey, String sourceFingerprint,
			Map<String, Object> before, Map<String, Object> after, boolean highRisk) {
	}

	public static final class SemanticPatchValidationException extends IllegalArgumentException {

		private final ValidationReport report;

		public SemanticPatchValidationException(ValidationReport report) {
			super("Semantic Patch preflight failed with " + report.errors().size() + " error(s)");
			this.report = report;
		}

		public ValidationReport report() {
			return report;
		}

	}

}
