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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatch.Operation;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatch.OperationType;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatch.PolicyAssetType;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.AuthorityRule;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.CrossSourceRelationship;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.FreshnessPolicy;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.LogicalColumnBinding;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Preflights and applies MultiSourcePolicyPatch to a cloned Draft. Every policy operation
 * is high risk: it is never accepted without a reviewed candidate and it always returns
 * to the durable Replay gate after application.
 */
@Service
public class MultiSourcePolicyPatchService {

	private static final String TRUE_AMBIGUITY_MESSAGE = "TRUE_AMBIGUITY requires explicit semantic resolution and cannot be auto-approved or applied";

	private static final Map<PolicyAssetType, Set<String>> ALLOWED_VALUES = Map.of(
			PolicyAssetType.LOGICAL_COLUMN_BINDING, Set.of("logicalEntityCode", "logicalAttributeCode", "datasourceId",
					"modelCode", "columnName", "expression", "transformRule", "grainCode", "evidence"),
			PolicyAssetType.AUTHORITY_RULE,
			Set.of("logicalAssetType", "logicalAssetCode", "datasourceId", "sourceRole", "priority", "allowFallback",
					"conditionExpression", "evidence"),
			PolicyAssetType.FRESHNESS_POLICY,
			Set.of("datasourceId", "businessDateField", "timeZone", "freshnessType", "latencyMinutes",
					"availableUntilRule", "evidence"),
			PolicyAssetType.CROSS_SOURCE_RELATIONSHIP,
			Set.of("relationshipCode", "leftDatasourceId", "leftModelCode", "leftKey", "rightDatasourceId",
					"rightModelCode", "rightKey", "cardinality", "transformRule", "nullPolicy", "uniquenessRule",
					"confidence", "evidence"),
			PolicyAssetType.MERGE_POLICY,
			Set.of("policyCode", "mergeType", "relationshipCode", "leftInputKey", "rightInputKey", "outputKey",
					"inputGrain", "nullPolicy", "duplicatePolicy", "maxRows", "partialFailurePolicy",
					"calculationExpression", "evidence"));

	private static final Map<PolicyAssetType, Set<String>> REQUIRED_VALUES = Map.of(
			PolicyAssetType.LOGICAL_COLUMN_BINDING, Set.of("modelCode", "columnName"), PolicyAssetType.AUTHORITY_RULE,
			Set.of("sourceRole", "priority"), PolicyAssetType.FRESHNESS_POLICY,
			Set.of("businessDateField", "timeZone", "freshnessType"), PolicyAssetType.CROSS_SOURCE_RELATIONSHIP,
			Set.of("leftDatasourceId", "leftModelCode", "leftKey", "rightDatasourceId", "rightModelCode", "rightKey",
					"cardinality"),
			PolicyAssetType.MERGE_POLICY, Set.of("mergeType", "leftInputKey", "rightInputKey", "outputKey",
					"inputGrain", "nullPolicy", "duplicatePolicy", "maxRows", "partialFailurePolicy"));

	private final JdbcTemplate jdbc;

	private final MultiSourcePolicyService policyService;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final VersionedJson versionedJson = new VersionedJson();

	private final SemanticEvolutionStateMachine stateMachine;

	public MultiSourcePolicyPatchService(JdbcTemplate jdbc, MultiSourcePolicyService policyService,
			SemanticEvolutionStateMachine stateMachine) {
		this.jdbc = jdbc;
		this.policyService = policyService;
		this.stateMachine = stateMachine;
	}

	public ValidationReport validateCandidate(String candidateId, MultiSourcePolicyPatch patch) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		if ("TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")).toUpperCase(java.util.Locale.ROOT))) {
			return new ValidationReport(false, List.of(new ValidationIssue("ERROR",
					"TRUE_AMBIGUITY_REQUIRES_RESOLUTION", null, null, TRUE_AMBIGUITY_MESSAGE)), List.of(), 0, true);
		}
		return validate(number(candidate.get("project_id")), number(candidate.get("source_version_id")),
				text(candidate.get("source_catalog_hash")), patch);
	}

	public ValidationReport validate(Long projectId, Long sourceVersionId, String sourceCatalogHash,
			MultiSourcePolicyPatch patch) {
		List<ValidationIssue> issues = new ArrayList<>();
		if (patch == null) {
			return new ValidationReport(false,
					List.of(new ValidationIssue("ERROR", "PATCH_REQUIRED", null, null, "Policy Patch is required")),
					List.of(), 0, true);
		}
		if (!Objects.equals(sourceVersionId, patch.sourceVersionId())
				|| !Objects.equals(sourceCatalogHash, patch.sourceCatalogHash())) {
			issues.add(new ValidationIssue("ERROR", "SOURCE_PIN_MISMATCH", null, null,
					"Policy Patch must keep the candidate sourceVersionId and sourceCatalogHash"));
		}
		MultiSourcePolicySnapshot proposed = copy(policyService.get(projectId, sourceVersionId));
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < patch.operations().size(); index++) {
			int operationIndex = index;
			Operation operation = patch.operations().get(index);
			validateOperation(projectId, sourceVersionId, proposed, operation, index, issues, seen);
			if (issues.stream()
				.noneMatch(issue -> "ERROR".equals(issue.severity())
						&& Objects.equals(issue.operationIndex(), operationIndex))) {
				apply(proposed, operation, projectId, sourceVersionId);
			}
		}
		if (issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()))) {
			for (String violation : policyService.validateSnapshot(projectId, sourceVersionId, proposed)) {
				issues.add(new ValidationIssue("ERROR", "POLICY_INVARIANT", null, null, violation));
			}
		}
		List<ValidationIssue> errors = issues.stream().filter(issue -> "ERROR".equals(issue.severity())).toList();
		List<ValidationIssue> warnings = issues.stream().filter(issue -> "WARNING".equals(issue.severity())).toList();
		return new ValidationReport(errors.isEmpty(), errors, warnings, patch.operations().size(), true);
	}

	public void requireValid(String candidateId, MultiSourcePolicyPatch patch) {
		ValidationReport report = validateCandidate(candidateId, patch);
		if (!report.valid()) {
			throw new InvalidMultiSourcePolicyPatchException(report);
		}
	}

	@Transactional
	public PolicyPatchApplicationResult applyCandidate(String candidateId) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE",
				candidateId);
		if ("TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")).toUpperCase(java.util.Locale.ROOT))) {
			throw new IllegalStateException(TRUE_AMBIGUITY_MESSAGE);
		}
		String status = text(candidate.get("status"));
		if (Set
			.of("PATCH_APPLIED", "REPLAY_RUNNING", "REPLAY_PASSED", "REPLAY_FAILED", "READY_FOR_PUBLISH", "PUBLISHED")
			.contains(status)) {
			return new PolicyPatchApplicationResult(candidateId, number(candidate.get("target_draft_version_id")), 0,
					text(candidate.get("patch_hash")), true);
		}
		if (!"DRAFT_CREATED".equals(status)) {
			throw new IllegalStateException("Policy Patch requires DRAFT_CREATED candidate; current=" + status);
		}
		Long projectId = number(candidate.get("project_id"));
		Long sourceVersionId = number(candidate.get("source_version_id"));
		Long targetVersionId = number(candidate.get("target_draft_version_id"));
		MultiSourcePolicyPatch patch = parse(text(candidate.get("patch_json")));
		requireValid(candidateId, patch);
		MultiSourcePolicySnapshot target = copy(policyService.get(projectId, targetVersionId));
		for (Operation operation : patch.operations()) {
			apply(target, operation, projectId, targetVersionId);
		}
		policyService.replace(projectId, targetVersionId, target,
				OperatorContext.system("multi-source-policy-patch-apply:" + candidateId));
		String patchHash = canonicalJson.hash(patch);
		stateMachine.transition(candidateId, CandidateStatus.DRAFT_CREATED, number(candidate.get("revision")),
				CandidateStatus.PATCH_APPLIED, Mutation.patchApplied(patchHash));
		return new PolicyPatchApplicationResult(candidateId, targetVersionId, patch.operations().size(), patchHash,
				false);
	}

	public String fingerprint(PolicyAssetType assetType, Object asset) {
		Map<String, Object> value = mapper.convertValue(asset, new com.fasterxml.jackson.core.type.TypeReference<>() {
		});
		for (String volatileField : List.of("id", "projectId", "projectVersionId", "createTime", "updateTime")) {
			value.remove(volatileField);
		}
		return canonicalJson.hash(new java.util.TreeMap<>(value));
	}

	public Object findAsset(MultiSourcePolicySnapshot policy, PolicyAssetType type, String key) {
		return assets(policy, type).stream()
			.filter(item -> Objects.equals(assetKey(type, item), key))
			.findFirst()
			.orElse(null);
	}

	private void validateOperation(Long projectId, Long sourceVersionId, MultiSourcePolicySnapshot proposed,
			Operation operation, int index, List<ValidationIssue> issues, Set<String> seen) {
		String assetKey = operation.assetKey() == null ? "" : operation.assetKey().trim();
		String duplicateKey = operation.assetType() + ":" + assetKey;
		if (!seen.add(duplicateKey)) {
			error(issues, "DUPLICATE_OPERATION", index, assetKey,
					"A Policy Patch cannot contain duplicate or conflicting operations for the same asset");
		}
		if (!assetKey.matches("[A-Za-z0-9_.:-]{1,255}")) {
			error(issues, "INVALID_ASSET_KEY", index, assetKey, "assetKey has an invalid format");
		}
		Object current = findAsset(proposed, operation.assetType(), assetKey);
		if (operation.operation() == OperationType.ADD) {
			if (StringUtils.hasText(operation.expectedCurrentFingerprint())) {
				error(issues, "ADD_HAS_FINGERPRINT", index, assetKey, "ADD must not carry expectedCurrentFingerprint");
			}
			if (current != null) {
				error(issues, "ASSET_ALREADY_EXISTS", index, assetKey, "ADD targets an existing policy asset");
			}
		}
		else {
			if (!StringUtils.hasText(operation.expectedCurrentFingerprint())) {
				error(issues, "UPDATE_FINGERPRINT_REQUIRED", index, assetKey,
						"UPDATE requires expectedCurrentFingerprint");
			}
			else if (current == null) {
				error(issues, "ASSET_NOT_FOUND", index, assetKey, "UPDATE targets a missing policy asset");
			}
			else if (!Objects.equals(operation.expectedCurrentFingerprint(),
					fingerprint(operation.assetType(), current))) {
				error(issues, "STALE_FINGERPRINT", index, assetKey, "Policy asset fingerprint is stale");
			}
		}
		Set<String> unknown = new LinkedHashSet<>(operation.values().keySet());
		unknown.removeAll(ALLOWED_VALUES.get(operation.assetType()));
		if (!unknown.isEmpty()) {
			error(issues, "UNKNOWN_VALUES", index, assetKey, "Unknown or protected values: " + unknown);
		}
		if (operation.values().isEmpty()) {
			error(issues, "VALUES_REQUIRED", index, assetKey, "values must not be empty");
		}
		Map<String, Object> effectiveValues = current == null ? new LinkedHashMap<>()
				: mapper.convertValue(current, new com.fasterxml.jackson.core.type.TypeReference<>() {
				});
		effectiveValues.putAll(operation.values());
		putKeyFields(operation.assetType(), assetKey, effectiveValues);
		for (String required : REQUIRED_VALUES.get(operation.assetType())) {
			Object value = effectiveValues.get(required);
			if (value == null || value instanceof String text && !StringUtils.hasText(text)) {
				error(issues, "REQUIRED_VALUE_MISSING", index, assetKey, required + " is required");
			}
		}
		validateEvidence(projectId, sourceVersionId, operation, index, issues);
		validateSafety(current, operation, index, issues);
		issues.add(new ValidationIssue("WARNING", "MANUAL_REPLAY_REQUIRED", index, assetKey,
				"Authority, freshness, relationship and merge changes require human approval and durable Replay"));
	}

	private void validateEvidence(Long projectId, Long sourceVersionId, Operation operation, int index,
			List<ValidationIssue> issues) {
		for (String caseId : operation.evidenceCaseIds()) {
			List<Map<String, Object>> rows = jdbc.queryForList("""
					SELECT project_id, project_version_id, status FROM qw_query_example WHERE id = ?
					""", caseId);
			if (rows.size() != 1 || !Objects.equals(projectId, number(rows.get(0).get("project_id")))
					|| !Objects.equals(sourceVersionId, number(rows.get(0).get("project_version_id")))
					|| !"APPROVED".equals(text(rows.get(0).get("status")))) {
				error(issues, "INVALID_EVIDENCE_CASE", index, operation.assetKey(),
						"evidenceCaseId must be an APPROVED case from the same project and source version: " + caseId);
			}
		}
		if (operation.evidenceCaseIds().isEmpty()) {
			issues.add(new ValidationIssue("WARNING", "EVIDENCE_RECOMMENDED", index, operation.assetKey(),
					"At least one reviewed Query Case should support a Policy Patch"));
		}
	}

	private void validateSafety(Object current, Operation operation, int index, List<ValidationIssue> issues) {
		if (operation.assetType() == PolicyAssetType.AUTHORITY_RULE
				&& Boolean.TRUE.equals(operation.values().get("allowFallback"))
				&& (!(current instanceof AuthorityRule rule) || !Boolean.TRUE.equals(rule.getAllowFallback()))) {
			error(issues, "SAFETY_DOWNGRADE_FORBIDDEN", index, operation.assetKey(),
					"Policy Patch cannot enable authority fallback; create an explicit reviewed policy instead");
		}
		if (operation.assetType() == PolicyAssetType.MERGE_POLICY
				&& "ALLOW_PARTIAL".equalsIgnoreCase(text(operation.values().get("partialFailurePolicy")))
				&& (!(current instanceof MergePolicy policy)
						|| !"ALLOW_PARTIAL".equalsIgnoreCase(policy.getPartialFailurePolicy()))) {
			error(issues, "SAFETY_DOWNGRADE_FORBIDDEN", index, operation.assetKey(),
					"Policy Patch cannot weaken partial failure semantics to ALLOW_PARTIAL");
		}
	}

	private void apply(MultiSourcePolicySnapshot policy, Operation operation, Long projectId, Long versionId) {
		List<Object> assets = assets(policy, operation.assetType());
		Object current = findAsset(policy, operation.assetType(), operation.assetKey());
		if (operation.operation() == OperationType.UPDATE) {
			assets.remove(current);
		}
		Map<String, Object> values = current == null ? new LinkedHashMap<>()
				: mapper.convertValue(current, new com.fasterxml.jackson.core.type.TypeReference<>() {
				});
		values.putAll(operation.values());
		putKeyFields(operation.assetType(), operation.assetKey(), values);
		Class<?> type = assetClass(operation.assetType());
		Object replacement = mapper.convertValue(values, type);
		setOwnership(replacement, projectId, versionId);
		assets.add(replacement);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<Object> assets(MultiSourcePolicySnapshot policy, PolicyAssetType type) {
		return switch (type) {
			case LOGICAL_COLUMN_BINDING -> (List) policy.getLogicalBindings();
			case AUTHORITY_RULE -> (List) policy.getAuthorityRules();
			case FRESHNESS_POLICY -> (List) policy.getFreshnessPolicies();
			case CROSS_SOURCE_RELATIONSHIP -> (List) policy.getCrossSourceRelationships();
			case MERGE_POLICY -> (List) policy.getMergePolicies();
		};
	}

	private Class<?> assetClass(PolicyAssetType type) {
		return switch (type) {
			case LOGICAL_COLUMN_BINDING -> LogicalColumnBinding.class;
			case AUTHORITY_RULE -> AuthorityRule.class;
			case FRESHNESS_POLICY -> FreshnessPolicy.class;
			case CROSS_SOURCE_RELATIONSHIP -> CrossSourceRelationship.class;
			case MERGE_POLICY -> MergePolicy.class;
		};
	}

	private String assetKey(PolicyAssetType type, Object asset) {
		return switch (type) {
			case LOGICAL_COLUMN_BINDING -> {
				LogicalColumnBinding item = (LogicalColumnBinding) asset;
				yield item.getLogicalEntityCode() + ":" + item.getLogicalAttributeCode() + ":" + item.getDatasourceId();
			}
			case AUTHORITY_RULE -> {
				AuthorityRule item = (AuthorityRule) asset;
				yield item.getLogicalAssetType() + ":" + item.getLogicalAssetCode() + ":" + item.getDatasourceId();
			}
			case FRESHNESS_POLICY -> String.valueOf(((FreshnessPolicy) asset).getDatasourceId());
			case CROSS_SOURCE_RELATIONSHIP -> ((CrossSourceRelationship) asset).getRelationshipCode();
			case MERGE_POLICY -> ((MergePolicy) asset).getPolicyCode();
		};
	}

	private void putKeyFields(PolicyAssetType type, String assetKey, Map<String, Object> values) {
		String[] parts = assetKey.split(":");
		switch (type) {
			case LOGICAL_COLUMN_BINDING -> {
				if (parts.length == 3) {
					values.put("logicalEntityCode", parts[0]);
					values.put("logicalAttributeCode", parts[1]);
					values.put("datasourceId", integer(parts[2]));
				}
			}
			case AUTHORITY_RULE -> {
				if (parts.length == 3) {
					values.put("logicalAssetType", parts[0]);
					values.put("logicalAssetCode", parts[1]);
					values.put("datasourceId", integer(parts[2]));
				}
			}
			case FRESHNESS_POLICY -> values.put("datasourceId", integer(assetKey));
			case CROSS_SOURCE_RELATIONSHIP -> values.put("relationshipCode", assetKey);
			case MERGE_POLICY -> values.put("policyCode", assetKey);
		}
	}

	private void setOwnership(Object value, Long projectId, Long versionId) {
		try {
			value.getClass().getMethod("setProjectId", Long.class).invoke(value, projectId);
			value.getClass().getMethod("setProjectVersionId", Long.class).invoke(value, versionId);
			value.getClass()
				.getMethod("setStatus",
						cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.class)
				.invoke(value, cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalArgumentException("Unsupported policy asset " + value.getClass().getSimpleName(), ex);
		}
	}

	private MultiSourcePolicySnapshot copy(MultiSourcePolicySnapshot value) {
		return mapper.convertValue(value, MultiSourcePolicySnapshot.class);
	}

	private MultiSourcePolicyPatch parse(String value) {
		return versionedJson.read(value, JsonPayloadRegistry.MULTI_SOURCE_POLICY_PATCH, MultiSourcePolicyPatch.class);
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> values = jdbc.queryForList(sql, args);
		if (values.size() != 1) {
			throw new IllegalArgumentException("Expected one row for MultiSourcePolicyPatch");
		}
		return values.get(0);
	}

	private void error(List<ValidationIssue> issues, String code, Integer index, String key, String message) {
		issues.add(new ValidationIssue("ERROR", code, index, key, message));
	}

	private int integer(String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Datasource ID must be an integer: " + value, ex);
		}
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	public record ValidationIssue(String severity, String code, Integer operationIndex, String assetKey,
			String message) {
	}

	public record ValidationReport(boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings,
			int checkedOperations, boolean manualReplayRequired) {
	}

	public record PolicyPatchApplicationResult(String candidateId, Long targetDraftVersionId, int operationCount,
			String patchHash, boolean alreadyApplied) {
	}

	public static class InvalidMultiSourcePolicyPatchException extends IllegalArgumentException {

		private final transient ValidationReport report;

		public InvalidMultiSourcePolicyPatchException(ValidationReport report) {
			super("MultiSourcePolicyPatch preflight failed: "
					+ String.join("; ", report.errors().stream().map(ValidationIssue::message).toList()));
			this.report = report;
		}

		public ValidationReport report() {
			return report;
		}

	}

}
