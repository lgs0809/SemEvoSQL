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
package cn.lgs.semevosql.multisource;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.AuthorityRule;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.CrossSourceRelationship;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.FreshnessPolicy;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.LogicalColumnBinding;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergeType;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.SourceRole;
import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiSourcePolicyService {

	private final SemEvoSQLMultiSourcePolicyMapper mapper;

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogRepository catalogRepository;

	private final LocalOperatorService authorization;

	public MultiSourcePolicySnapshot get(Long projectId, Long versionId) {
		requireVersion(projectId, versionId);
		return load(projectId, versionId);
	}

	@Transactional
	public MultiSourcePolicySnapshot replace(Long projectId, Long versionId, MultiSourcePolicySnapshot requested) {
		return replace(projectId, versionId, requested, OperatorContext.system("multi-source-policy-replace"));
	}

	@Transactional
	public MultiSourcePolicySnapshot replace(Long projectId, Long versionId, MultiSourcePolicySnapshot requested,
			OperatorContext operator) {
		authorization.require(operator, "replace Multi-Source Policy");
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Multi-source policy can only be changed in a DRAFT project version");
		}
		MultiSourcePolicySnapshot normalized = normalize(projectId, versionId, requested);
		List<String> violations = validate(projectId, versionId, catalogRepository.loadCatalog(projectId, versionId),
				normalized);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Invalid multi-source policy: " + String.join("; ", violations));
		}
		replaceInternal(versionId, normalized);
		return load(projectId, versionId);
	}

	@Transactional
	public void clonePolicy(Long projectId, Long sourceVersionId, Long targetVersionId) {
		requireVersion(projectId, sourceVersionId);
		requireVersion(projectId, targetVersionId);
		MultiSourcePolicySnapshot source = load(projectId, sourceVersionId);
		replaceInternal(targetVersionId, normalize(projectId, targetVersionId, source));
	}

	public List<String> validateForRelease(Long projectId, Long versionId, SemanticCatalogSnapshot catalog) {
		return validate(projectId, versionId, catalog, load(projectId, versionId));
	}

	/**
	 * Read-only validation entry used by governed Policy Patch preflight. It applies the
	 * same invariants as a Draft replacement without mutating the version.
	 */
	public List<String> validateSnapshot(Long projectId, Long versionId, MultiSourcePolicySnapshot requested) {
		requireVersion(projectId, versionId);
		MultiSourcePolicySnapshot normalized = normalize(projectId, versionId, requested);
		return validate(projectId, versionId, catalogRepository.loadCatalog(projectId, versionId), normalized);
	}

	public List<SourceCandidate> sourceCandidates(Long projectId, Long versionId, Set<String> modelCodes) {
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, versionId);
		MultiSourcePolicySnapshot policy = load(projectId, versionId);
		Map<Integer, ProjectDatasourceBinding> bindings = projectRepository.findDatasourceBindings(versionId)
			.stream()
			.collect(Collectors.toMap(ProjectDatasourceBinding::getDatasourceId, Function.identity()));
		Map<Integer, List<String>> modelsByDatasource = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> modelCodes == null || modelCodes.isEmpty() || modelCodes.contains(model.getModelCode()))
			.collect(Collectors.groupingBy(SemanticCatalogSnapshot.Model::getDatasourceId, LinkedHashMap::new,
					Collectors.mapping(SemanticCatalogSnapshot.Model::getModelCode, Collectors.toList())));
		Map<Integer, List<AuthorityRule>> authorities = policy.getAuthorityRules()
			.stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.groupingBy(AuthorityRule::getDatasourceId));
		Map<Integer, FreshnessPolicy> freshness = policy.getFreshnessPolicies()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(FreshnessPolicy::getDatasourceId, Function.identity(), (left, right) -> left));

		return modelsByDatasource.entrySet().stream().map(entry -> {
			ProjectDatasourceBinding binding = bindings.get(entry.getKey());
			int authorityRank = authorities.getOrDefault(entry.getKey(), List.of())
				.stream()
				.mapToInt(rule -> sourceRank(rule.getSourceRole()))
				.min()
				.orElse(100);
			return new SourceCandidate(entry.getKey(), List.copyOf(entry.getValue()),
					binding == null ? null : binding.getDomainCode(),
					binding == null ? null : binding.getResponsibility(), binding == null ? 100 : binding.getPriority(),
					authorityRank, freshness.get(entry.getKey()));
		})
			.sorted(Comparator.comparingInt(SourceCandidate::authorityRank)
				.thenComparingInt(SourceCandidate::priority)
				.thenComparingInt(SourceCandidate::datasourceId))
			.toList();
	}

	public PlanningDecision plan(Long projectId, Long versionId, Set<String> selectedModelCodes) {
		return plan(projectId, versionId, selectedModelCodes, null, false, null);
	}

	public PlanningDecision plan(Long projectId, Long versionId, Set<String> selectedModelCodes,
			Set<String> selectedRelationshipCodes, boolean scalarComposition, String scalarCalculationExpression) {
		MultiSourcePolicySnapshot policy = load(projectId, versionId);
		List<SourceCandidate> sources = sourceCandidates(projectId, versionId, selectedModelCodes);
		if (sources.size() <= 1) {
			return new PlanningDecision(sources, null, List.of(), List.of());
		}
		List<String> warnings = sources.stream()
			.filter(source -> source.freshnessPolicy() != null)
			.map(source -> freshnessWarning(source.datasourceId(), source.freshnessPolicy()))
			.toList();
		if (scalarComposition) {
			MergePolicy runtimePolicy = MergePolicy.builder()
				.policyCode("runtime_scalar_composition")
				.mergeType(MergeType.SCALAR_COMPOSITION)
				.nullPolicy("KEEP")
				.duplicatePolicy("ERROR")
				.maxRows(1)
				.partialFailurePolicy("FAIL_ALL")
				.calculationExpression(scalarCalculationExpression)
				.evidence("Built-in execution composition for independent governed scalar aggregates")
				.status(SemanticAssetStatus.ENABLED)
				.build();
			return new PlanningDecision(sources, runtimePolicy, List.of(), List.of(), warnings);
		}
		Set<Integer> selectedDatasourceIds = sources.stream()
			.map(SourceCandidate::datasourceId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<CrossSourceRelationship> relationships = policy.getCrossSourceRelationships()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(item -> selectedDatasourceIds.contains(item.getLeftDatasourceId())
					&& selectedDatasourceIds.contains(item.getRightDatasourceId()))
			.filter(item -> selectedRelationshipCodes == null
					|| selectedRelationshipCodes.contains(item.getRelationshipCode()))
			.toList();
		List<String> errors = new ArrayList<>();
		if (!datasourcesConnected(selectedDatasourceIds, relationships)) {
			errors.add("Selected datasources are not connected by a confirmed cross-source relationship: "
					+ selectedDatasourceIds);
		}
		Set<String> relationshipCodes = relationships.stream()
			.map(CrossSourceRelationship::getRelationshipCode)
			.collect(Collectors.toSet());
		MergePolicy mergePolicy = policy.getMergePolicies()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(item -> item.getRelationshipCode() == null
					|| relationshipCodes.contains(item.getRelationshipCode()))
			.sorted(Comparator.comparing(MergePolicy::getPolicyCode))
			.findFirst()
			.orElse(null);
		if (mergePolicy == null) {
			errors.add("A published merge policy is required for a multi-source query");
		}
		return new PlanningDecision(sources, mergePolicy, relationships, List.copyOf(errors), warnings);
	}

	private List<String> validate(Long projectId, Long versionId, SemanticCatalogSnapshot catalog,
			MultiSourcePolicySnapshot policy) {
		List<String> violations = new ArrayList<>();
		Map<String, SemanticCatalogSnapshot.Model> models = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode, Function.identity()));
		Set<Integer> datasourceIds = models.values()
			.stream()
			.map(SemanticCatalogSnapshot.Model::getDatasourceId)
			.collect(Collectors.toSet());
		Set<String> columnKeys = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.map(column -> key(column.getModelCode(), column.getColumnName()))
			.collect(Collectors.toSet());

		validateUnique(policy.getLogicalBindings(),
				item -> key(key(item.getLogicalEntityCode(), item.getLogicalAttributeCode()), item.getDatasourceId()),
				"duplicate logical column binding", violations);
		for (LogicalColumnBinding item : policy.getLogicalBindings()) {
			if (item.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			SemanticCatalogSnapshot.Model model = models.get(item.getModelCode());
			if (!hasText(item.getLogicalEntityCode()) || !hasText(item.getLogicalAttributeCode())) {
				violations.add("logical binding requires logicalEntityCode and logicalAttributeCode");
			}
			if (model == null) {
				violations.add("logical binding references missing model: " + item.getModelCode());
			}
			else if (!Objects.equals(model.getDatasourceId(), item.getDatasourceId())) {
				violations.add("logical binding datasource does not match model: " + item.getLogicalAttributeCode());
			}
			if (!hasText(item.getColumnName()) && !hasText(item.getExpression())) {
				violations.add("logical binding requires columnName or expression: " + item.getLogicalAttributeCode());
			}
			if (hasText(item.getColumnName()) && !columnKeys.contains(key(item.getModelCode(), item.getColumnName()))) {
				violations.add("logical binding references missing column: " + item.getModelCode() + "."
						+ item.getColumnName());
			}
		}

		validateUnique(policy.getAuthorityRules(),
				item -> key(key(item.getLogicalAssetType(), item.getLogicalAssetCode()), item.getDatasourceId()),
				"duplicate authority rule", violations);
		Map<String, List<AuthorityRule>> authorityByAsset = policy.getAuthorityRules()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.groupingBy(item -> key(item.getLogicalAssetType(), item.getLogicalAssetCode())));
		for (Map.Entry<String, List<AuthorityRule>> entry : authorityByAsset.entrySet()) {
			long authoritative = entry.getValue()
				.stream()
				.filter(item -> item.getSourceRole() == SourceRole.AUTHORITATIVE)
				.count();
			if (authoritative > 1) {
				violations.add("logical asset has more than one AUTHORITATIVE source: " + entry.getKey());
			}
			for (AuthorityRule item : entry.getValue()) {
				if (!datasourceIds.contains(item.getDatasourceId())) {
					violations
						.add("authority rule references datasource not used by catalog: " + item.getDatasourceId());
				}
				if (item.getSourceRole() == null) {
					violations.add("authority rule requires sourceRole: " + entry.getKey());
				}
			}
		}
		Map<String, Set<Integer>> bindingSources = policy.getLogicalBindings()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.groupingBy(LogicalColumnBinding::getLogicalAttributeCode,
					Collectors.mapping(LogicalColumnBinding::getDatasourceId, Collectors.toSet())));
		for (Map.Entry<String, Set<Integer>> entry : bindingSources.entrySet()) {
			if (entry.getValue().size() > 1 && policy.getAuthorityRules()
				.stream()
				.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(item -> item.getLogicalAssetType() == MultiSourcePolicySnapshot.LogicalAssetType.ATTRIBUTE)
				.filter(item -> entry.getKey().equals(item.getLogicalAssetCode()))
				.noneMatch(item -> item.getSourceRole() == SourceRole.AUTHORITATIVE)) {
				violations.add("multi-source logical attribute has no AUTHORITATIVE source: " + entry.getKey());
			}
		}

		validateUnique(policy.getFreshnessPolicies(), FreshnessPolicy::getDatasourceId, "duplicate freshness policy",
				violations);
		Map<Integer, FreshnessPolicy> freshnessByDatasource = policy.getFreshnessPolicies()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(FreshnessPolicy::getDatasourceId, Function.identity(), (left, right) -> left));
		for (FreshnessPolicy item : freshnessByDatasource.values()) {
			if (!datasourceIds.contains(item.getDatasourceId())) {
				violations.add("freshness policy references datasource not used by catalog: " + item.getDatasourceId());
			}
			if (!hasText(item.getBusinessDateField()) || item.getFreshnessType() == null) {
				violations
					.add("freshness policy requires businessDateField and freshnessType: " + item.getDatasourceId());
			}
			if (item.getLatencyMinutes() == null || item.getLatencyMinutes() < 0) {
				violations.add("freshness latencyMinutes must be non-negative: " + item.getDatasourceId());
			}
			try {
				ZoneId.of(item.getTimeZone());
			}
			catch (DateTimeException | NullPointerException ex) {
				violations.add("freshness policy has invalid timeZone: " + item.getDatasourceId());
			}
		}
		validateUnique(policy.getCrossSourceRelationships(), CrossSourceRelationship::getRelationshipCode,
				"duplicate cross-source relationship", violations);
		Map<String, CrossSourceRelationship> relationships = new HashMap<>();
		for (CrossSourceRelationship item : policy.getCrossSourceRelationships()) {
			if (item.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			relationships.put(item.getRelationshipCode(), item);
			SemanticCatalogSnapshot.Model left = models.get(item.getLeftModelCode());
			SemanticCatalogSnapshot.Model right = models.get(item.getRightModelCode());
			if (left == null || right == null) {
				violations.add("cross-source relationship references missing model: " + item.getRelationshipCode());
				continue;
			}
			if (!Objects.equals(left.getDatasourceId(), item.getLeftDatasourceId())
					|| !Objects.equals(right.getDatasourceId(), item.getRightDatasourceId())) {
				violations.add("cross-source relationship datasource/model mismatch: " + item.getRelationshipCode());
			}
			if (Objects.equals(item.getLeftDatasourceId(), item.getRightDatasourceId())) {
				violations
					.add("cross-source relationship must connect different datasources: " + item.getRelationshipCode());
			}
			if (!columnKeys.contains(key(item.getLeftModelCode(), item.getLeftKey()))
					|| !columnKeys.contains(key(item.getRightModelCode(), item.getRightKey()))) {
				violations
					.add("cross-source relationship references missing key column: " + item.getRelationshipCode());
			}
			if (item.getCardinality() == null || !hasText(item.getUniquenessRule()) || !hasText(item.getNullPolicy())) {
				violations.add("cross-source relationship requires cardinality, uniquenessRule and nullPolicy: "
						+ item.getRelationshipCode());
			}
			if (item.getConfidence() == null || item.getConfidence() < 0 || item.getConfidence() > 100) {
				violations.add("cross-source relationship confidence must be between 0 and 100: "
						+ item.getRelationshipCode());
			}
		}

		validateUnique(policy.getMergePolicies(), MergePolicy::getPolicyCode, "duplicate merge policy", violations);
		for (MergePolicy item : policy.getMergePolicies()) {
			if (item.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			if (!hasText(item.getPolicyCode()) || item.getMergeType() == null) {
				violations.add("merge policy requires policyCode and mergeType");
			}
			if (hasText(item.getRelationshipCode()) && !relationships.containsKey(item.getRelationshipCode())) {
				violations.add("merge policy references missing cross-source relationship: " + item.getPolicyCode());
			}
			if (item.getMaxRows() == null || item.getMaxRows() <= 0) {
				violations.add("merge policy maxRows must be positive: " + item.getPolicyCode());
			}
			if (!hasText(item.getNullPolicy()) || !hasText(item.getDuplicatePolicy())
					|| !hasText(item.getPartialFailurePolicy())) {
				violations
					.add("merge policy requires null, duplicate and partial failure policies: " + item.getPolicyCode());
			}
		}
		return List.copyOf(new LinkedHashSet<>(violations));
	}

	private MultiSourcePolicySnapshot load(Long projectId, Long versionId) {
		return MultiSourcePolicySnapshot.builder()
			.projectId(projectId)
			.projectVersionId(versionId)
			.logicalBindings(mapper.findLogicalBindings(projectId, versionId))
			.authorityRules(mapper.findAuthorityRules(projectId, versionId))
			.freshnessPolicies(mapper.findFreshnessPolicies(projectId, versionId))
			.crossSourceRelationships(mapper.findCrossSourceRelationships(projectId, versionId))
			.mergePolicies(mapper.findMergePolicies(projectId, versionId))
			.build();
	}

	private MultiSourcePolicySnapshot normalize(Long projectId, Long versionId, MultiSourcePolicySnapshot requested) {
		if (requested == null) {
			throw new IllegalArgumentException("Multi-source policy payload is required");
		}
		LocalDateTime now = LocalDateTime.now();
		MultiSourcePolicySnapshot normalized = MultiSourcePolicySnapshot.builder()
			.projectId(projectId)
			.projectVersionId(versionId)
			.logicalBindings(copy(requested.getLogicalBindings()))
			.authorityRules(copy(requested.getAuthorityRules()))
			.freshnessPolicies(copy(requested.getFreshnessPolicies()))
			.crossSourceRelationships(copy(requested.getCrossSourceRelationships()))
			.mergePolicies(copy(requested.getMergePolicies()))
			.build();
		normalized.getLogicalBindings().forEach(item -> normalize(item, projectId, versionId, now));
		normalized.getAuthorityRules().forEach(item -> normalize(item, projectId, versionId, now));
		normalized.getFreshnessPolicies().forEach(item -> normalize(item, projectId, versionId, now));
		normalized.getCrossSourceRelationships().forEach(item -> normalize(item, projectId, versionId, now));
		normalized.getMergePolicies().forEach(item -> normalize(item, projectId, versionId, now));
		return normalized;
	}

	private void normalize(Object item, Long projectId, Long versionId, LocalDateTime now) {
		if (item instanceof LogicalColumnBinding value) {
			value.setId(null);
			value.setProjectId(projectId);
			value.setProjectVersionId(versionId);
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
		else if (item instanceof AuthorityRule value) {
			value.setId(null);
			value.setProjectId(projectId);
			value.setProjectVersionId(versionId);
			value.setPriority(value.getPriority() == null ? 100 : value.getPriority());
			value.setAllowFallback(Boolean.TRUE.equals(value.getAllowFallback()));
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
		else if (item instanceof FreshnessPolicy value) {
			value.setId(null);
			value.setProjectId(projectId);
			value.setProjectVersionId(versionId);
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
		else if (item instanceof CrossSourceRelationship value) {
			value.setId(null);
			value.setProjectId(projectId);
			value.setProjectVersionId(versionId);
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
		else if (item instanceof MergePolicy value) {
			value.setId(null);
			value.setProjectId(projectId);
			value.setProjectVersionId(versionId);
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
	}

	private void replaceInternal(Long versionId, MultiSourcePolicySnapshot snapshot) {
		mapper.deleteMergePolicies(versionId);
		mapper.deleteCrossSourceRelationships(versionId);
		mapper.deleteFreshnessPolicies(versionId);
		mapper.deleteAuthorityRules(versionId);
		mapper.deleteLogicalBindings(versionId);
		snapshot.getLogicalBindings().forEach(mapper::insertLogicalBinding);
		snapshot.getAuthorityRules().forEach(mapper::insertAuthorityRule);
		snapshot.getFreshnessPolicies().forEach(mapper::insertFreshnessPolicy);
		snapshot.getCrossSourceRelationships().forEach(mapper::insertCrossSourceRelationship);
		snapshot.getMergePolicies().forEach(mapper::insertMergePolicy);
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long versionId) {
		SemanticProjectVersion version = projectRepository.findVersion(versionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + versionId));
		if (!Objects.equals(projectId, version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	private boolean datasourcesConnected(Set<Integer> datasourceIds, List<CrossSourceRelationship> relationships) {
		if (datasourceIds.size() <= 1) {
			return true;
		}
		Map<Integer, Set<Integer>> adjacency = new HashMap<>();
		datasourceIds.forEach(id -> adjacency.put(id, new HashSet<>()));
		for (CrossSourceRelationship relationship : relationships) {
			adjacency.computeIfAbsent(relationship.getLeftDatasourceId(), ignored -> new HashSet<>())
				.add(relationship.getRightDatasourceId());
			adjacency.computeIfAbsent(relationship.getRightDatasourceId(), ignored -> new HashSet<>())
				.add(relationship.getLeftDatasourceId());
		}
		Set<Integer> visited = new HashSet<>();
		List<Integer> pending = new ArrayList<>();
		pending.add(datasourceIds.iterator().next());
		while (!pending.isEmpty()) {
			Integer current = pending.remove(0);
			if (visited.add(current)) {
				pending.addAll(adjacency.getOrDefault(current, Set.of()));
			}
		}
		return visited.containsAll(datasourceIds);
	}

	private String freshnessWarning(Integer datasourceId, FreshnessPolicy policy) {
		return "datasource " + datasourceId + " freshness=" + policy.getFreshnessType() + ", latency="
				+ policy.getLatencyMinutes() + " minutes, timezone=" + policy.getTimeZone() + ", availableUntil="
				+ policy.getAvailableUntilRule();
	}

	private int sourceRank(SourceRole role) {
		if (role == null) {
			return 100;
		}
		return switch (role) {
			case AUTHORITATIVE -> 0;
			case REPLICA -> 10;
			case DERIVED -> 20;
			case SNAPSHOT -> 30;
			case FALLBACK -> 40;
		};
	}

	private <T> void validateUnique(List<T> values, Function<T, Object> keyExtractor, String message,
			List<String> violations) {
		Set<Object> keys = new HashSet<>();
		for (T value : safe(values)) {
			Object key = keyExtractor.apply(value);
			if (key != null && !keys.add(key)) {
				violations.add(message + ": " + key);
			}
		}
	}

	private <T> List<T> copy(List<T> values) {
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	private SemanticAssetStatus defaultStatus(SemanticAssetStatus status) {
		return status == null ? SemanticAssetStatus.ENABLED : status;
	}

	private String key(Object left, Object right) {
		return String.valueOf(left) + "::" + String.valueOf(right);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record SourceCandidate(Integer datasourceId, List<String> modelCodes, String domainCode,
			String responsibility, int priority, int authorityRank, FreshnessPolicy freshnessPolicy) {
	}

	public record PlanningDecision(List<SourceCandidate> sources, MergePolicy mergePolicy,
			List<CrossSourceRelationship> relationships, List<String> errors, List<String> warnings) {

		public PlanningDecision(List<SourceCandidate> sources, MergePolicy mergePolicy,
				List<CrossSourceRelationship> relationships, List<String> errors) {
			this(sources, mergePolicy, relationships, errors, List.of());
		}

		public boolean executable() {
			return errors.isEmpty();
		}
	}

}
