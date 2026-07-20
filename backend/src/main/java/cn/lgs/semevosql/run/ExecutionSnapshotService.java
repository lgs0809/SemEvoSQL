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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.entity.Datasource;
import cn.lgs.semevosql.entity.ModelConfig;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.prompt.PromptLoader;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectRuntimeProfileService;
import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.run.ExecutionSnapshot.EnvironmentSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshot.ModelSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshot.ProjectSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshot.PromptSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshot.RuntimeSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshot.SemanticSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.aimodelconfig.ModelConfigDataService;
import cn.lgs.semevosql.service.datasource.DatasourceService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Captures and verifies the immutable runtime contract bound to a durable query run. New
 * snapshots fail closed when any governed execution input changes. Legacy free-form JSON
 * is still readable, but is intentionally excluded from strict path comparison.
 */
@Service
public class ExecutionSnapshotService {

	private static final List<String> RUNTIME_PROMPTS = List.of("intent-recognition", "evidence-query-rewrite",
			"query-enhancement", "feasibility-assessment", "mix-selector", "planner", "new-sql-generate",
			"semantic-consistency", "sql-error-fixer");

	private static final String GRAPH_IMPLEMENTATION = "cn/lgs/semevosql/service/graph/GraphServiceImpl.class";

	private static final String SEMANTIC_RETRIEVER_IMPLEMENTATION = "cn/lgs/semevosql/workflow/node/SchemaRecallNode.class";

	private static final String SEMANTIC_PLANNER_IMPLEMENTATION = "cn/lgs/semevosql/workflow/node/SemanticBlueprintNode.class";

	private static final String PLANNER_IMPLEMENTATION = "cn/lgs/semevosql/workflow/node/PlannerNode.class";

	private static final String SQL_GENERATOR_IMPLEMENTATION = "cn/lgs/semevosql/workflow/node/SqlGenerateNode.class";

	private static final String SQL_GUARD_IMPLEMENTATION = "cn/lgs/semevosql/sql/application/SqlExecutionAdmissionControl.class";

	private static final String SQL_EXECUTOR_IMPLEMENTATION = "cn/lgs/semevosql/workflow/node/SqlExecuteNode.class";

	private final ProjectRuntimeGate projectRuntimeGate;

	private final ProjectRuntimeProfileService runtimeProfileService;

	private final SemanticProjectRepository projectRepository;

	private final MultiSourcePolicyService multiSourcePolicyService;

	private final ModelConfigDataService modelConfigDataService;

	private final DatasourceService datasourceService;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final VersionedJson versionedJson = new VersionedJson();

	private final Map<String, String> implementationHashes = new ConcurrentHashMap<>();

	public ExecutionSnapshotService(ProjectRuntimeGate projectRuntimeGate,
			ProjectRuntimeProfileService runtimeProfileService, SemanticProjectRepository projectRepository,
			MultiSourcePolicyService multiSourcePolicyService, ModelConfigDataService modelConfigDataService,
			DatasourceService datasourceService) {
		this.projectRuntimeGate = projectRuntimeGate;
		this.runtimeProfileService = runtimeProfileService;
		this.projectRepository = projectRepository;
		this.multiSourcePolicyService = multiSourcePolicyService;
		this.modelConfigDataService = modelConfigDataService;
		this.datasourceService = datasourceService;
	}

	public String capture(ProjectRuntimeContext context, ProjectRuntimeProfile runtimeProfile,
			SemanticBlueprint semanticPlan, boolean humanReviewEnabled) {
		Objects.requireNonNull(context, "project runtime context is required");
		ProjectRuntimeProfile resolvedProfile = runtimeProfile == null
				? runtimeProfileService.require(context.projectId()) : runtimeProfile;
		if (!Objects.equals(resolvedProfile.getProjectId(), context.projectId())) {
			throw new IllegalArgumentException("Runtime profile does not belong to project " + context.projectId());
		}

		List<ProjectDatasourceBinding> bindings = projectRepository.findDatasourceBindings(context.projectVersionId());
		MultiSourcePolicySnapshot policies = multiSourcePolicyService.get(context.projectId(),
				context.projectVersionId());
		ProjectSnapshot project = new ProjectSnapshot(context.projectId(), context.projectVersionId(),
				context.catalogHash(), hashDatasourceExposure(bindings));
		ModelSnapshot model = captureModel();
		PromptSnapshot prompts = capturePrompts();
		SemanticSnapshot semantic = captureSemantic(policies);
		RuntimeSnapshot runtime = captureRuntime(resolvedProfile);
		EnvironmentSnapshot environment = captureEnvironment(bindings, policies);
		String semanticPlanHash = semanticPlan == null ? null : hashJson(semanticPlan);
		String compatibilityHash = compatibilityHash(project, model, prompts, semantic, runtime, environment);
		ExecutionSnapshot snapshot = new ExecutionSnapshot(ExecutionSnapshot.CURRENT_SCHEMA_VERSION, project, model,
				prompts, semantic, runtime, environment, semanticPlan, semanticPlanHash, humanReviewEnabled, true,
				compatibilityHash);
		return write(snapshot);
	}

	public String captureForProject(Long projectId, Long projectVersionId, SemanticBlueprint semanticPlan,
			boolean humanReviewEnabled) {
		ProjectRuntimeContext context = projectRuntimeGate.requireReadyByProject(projectId);
		if (!Objects.equals(projectVersionId, context.projectVersionId())) {
			throw new IllegalStateException("Requested project version is not the active runtime version");
		}
		return capture(context, runtimeProfileService.require(projectId), semanticPlan, humanReviewEnabled);
	}

	/**
	 * Verifies that a non-terminal run can still execute under exactly the runtime it was
	 * created with. Completed durable results remain readable even after configuration
	 * changes and do not need this check.
	 */
	public void assertCompatible(QueryRun run) {
		Objects.requireNonNull(run, "run is required");
		Optional<ExecutionSnapshot> persisted = readTyped(run.executionSnapshot());
		if (persisted.isEmpty()) {
			return;
		}
		ExecutionSnapshot expected = persisted.orElseThrow();
		validateBoundSnapshot(run, expected);
		ProjectRuntimeContext currentContext = projectRuntimeGate.requireReadyVersion(run.projectId(),
				run.projectVersionId());
		ExecutionSnapshot current = readTyped(capture(currentContext, runtimeProfileService.require(run.projectId()),
				expected.semanticPlan(), expected.humanReviewEnabled()))
			.orElseThrow();

		List<String> changed = new ArrayList<>();
		compare(changed, "project/catalog", expected.project().projectId(), current.project().projectId());
		compare(changed, "project/version", expected.project().projectVersionId(),
				current.project().projectVersionId());
		compare(changed, "catalogHash", expected.project().catalogHash(), current.project().catalogHash());
		compare(changed, "datasourceExposureHash", expected.project().datasourceExposureHash(),
				current.project().datasourceExposureHash());
		compare(changed, "modelConfigHash", expected.model().configHash(), current.model().configHash());
		compare(changed, "promptBundleHash", expected.prompts().bundleHash(), current.prompts().bundleHash());
		compare(changed, "authorityPolicyHash", expected.semantic().authorityPolicyHash(),
				current.semantic().authorityPolicyHash());
		compare(changed, "freshnessPolicyHash", expected.semantic().freshnessPolicyHash(),
				current.semantic().freshnessPolicyHash());
		compare(changed, "mergePolicyHash", expected.semantic().mergePolicyHash(),
				current.semantic().mergePolicyHash());
		compare(changed, "semanticRetrieverHash", expected.semantic().semanticRetrieverHash(),
				current.semantic().semanticRetrieverHash());
		compare(changed, "plannerHash", expected.semantic().plannerHash(), current.semantic().plannerHash());
		compare(changed, "sqlCompilerHash", expected.semantic().sqlCompilerHash(),
				current.semantic().sqlCompilerHash());
		compare(changed, "runtimeProfileRevision", expected.runtime().runtimeProfileRevision(),
				current.runtime().runtimeProfileRevision());
		compare(changed, "graphDefinitionHash", expected.runtime().graphDefinitionHash(),
				current.runtime().graphDefinitionHash());
		compare(changed, "sqlGuardPolicyHash", expected.runtime().sqlGuardPolicyHash(),
				current.runtime().sqlGuardPolicyHash());
		compare(changed, "costPolicyHash", expected.runtime().costPolicyHash(), current.runtime().costPolicyHash());
		compare(changed, "environment", expected.environment(), current.environment());
		compare(changed, "compatibilityHash", expected.compatibilityHash(), current.compatibilityHash());
		if (!changed.isEmpty()) {
			throw new ExecutionSnapshotMismatchException(run.runId(), changed);
		}
	}

	public Optional<ExecutionSnapshot> readTyped(String snapshotJson) {
		if (!StringUtils.hasText(snapshotJson)) {
			return Optional.empty();
		}
		try {
			var payload = versionedJson.payload(snapshotJson, JsonPayloadRegistry.EXECUTION_SNAPSHOT);
			if (!payload.has("schemaVersion") || !payload.has("project")) {
				return Optional.empty();
			}
			ExecutionSnapshot snapshot = versionedJson.read(snapshotJson, JsonPayloadRegistry.EXECUTION_SNAPSHOT,
					ExecutionSnapshot.class);
			if (snapshot.schemaVersion() != ExecutionSnapshot.CURRENT_SCHEMA_VERSION) {
				throw new IllegalStateException(
						"Unsupported execution snapshot schemaVersion: " + snapshot.schemaVersion());
			}
			validateRequired(snapshot);
			return Optional.of(snapshot);
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to read typed execution snapshot", ex);
		}
	}

	public boolean strictlyComparable(QueryRun run) {
		return readTyped(run.executionSnapshot()).map(ExecutionSnapshot::strictComparable).orElse(false);
	}

	private ModelSnapshot captureModel() {
		ModelConfigDTO active = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		if (active == null || active.getId() == null) {
			throw new IllegalStateException("An active CHAT model configuration is required for query execution");
		}
		ModelConfig entity = modelConfigDataService.findById(active.getId());
		if (entity == null) {
			throw new IllegalStateException("Active CHAT model configuration cannot be loaded: " + active.getId());
		}
		String updatedAt = Objects.toString(entity.getUpdatedTime(), "");
		Map<String, Object> endpoint = new TreeMap<>();
		endpoint.put("baseUrl", sha256(Objects.toString(entity.getBaseUrl(), "")));
		endpoint.put("completionsPath", Objects.toString(entity.getCompletionsPath(), ""));
		endpoint.put("proxyEnabled", Boolean.TRUE.equals(entity.getProxyEnabled()));
		endpoint.put("proxyHost", Objects.toString(entity.getProxyHost(), ""));
		endpoint.put("proxyPort", Objects.toString(entity.getProxyPort(), ""));
		String endpointHash = hashJson(endpoint);
		Map<String, Object> config = new TreeMap<>();
		config.put("id", entity.getId());
		config.put("provider", Objects.toString(entity.getProvider(), ""));
		config.put("modelName", Objects.toString(entity.getModelName(), ""));
		config.put("temperature", entity.getTemperature());
		config.put("maxTokens", entity.getMaxTokens());
		config.put("endpointHash", endpointHash);
		config.put("updatedAt", updatedAt);
		return new ModelSnapshot(entity.getId(), entity.getProvider(), entity.getModelName(), entity.getTemperature(),
				entity.getMaxTokens(), updatedAt, endpointHash, hashJson(config));
	}

	private PromptSnapshot capturePrompts() {
		Map<String, String> hashes = new TreeMap<>();
		for (String prompt : RUNTIME_PROMPTS) {
			hashes.put(prompt, sha256(PromptLoader.loadPrompt(prompt)));
		}
		return new PromptSnapshot(Map.copyOf(hashes), hashJson(hashes));
	}

	private SemanticSnapshot captureSemantic(MultiSourcePolicySnapshot policies) {
		Map<String, Object> authority = new LinkedHashMap<>();
		authority.put("logicalBindings",
				policies.getLogicalBindings()
					.stream()
					.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
					.map(item -> new LogicalBindingValue(item.getLogicalEntityCode(), item.getLogicalAttributeCode(),
							item.getDatasourceId(), item.getModelCode(), item.getColumnName(), item.getExpression(),
							item.getTransformRule(), item.getGrainCode()))
					.sorted(Comparator.comparing(LogicalBindingValue::sortKey))
					.toList());
		authority.put("authorityRules", policies.getAuthorityRules()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.map(item -> new AuthorityValue(Objects.toString(item.getLogicalAssetType(), ""),
					item.getLogicalAssetCode(), item.getDatasourceId(), Objects.toString(item.getSourceRole(), ""),
					item.getPriority(), item.getAllowFallback(), item.getConditionExpression()))
			.sorted(Comparator.comparing(AuthorityValue::sortKey))
			.toList());
		List<FreshnessValue> freshness = policies.getFreshnessPolicies()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.map(item -> new FreshnessValue(item.getDatasourceId(), item.getBusinessDateField(), item.getTimeZone(),
					Objects.toString(item.getFreshnessType(), ""), item.getLatencyMinutes(),
					item.getAvailableUntilRule()))
			.sorted(Comparator.comparing(FreshnessValue::sortKey))
			.toList();
		Map<String, Object> merge = new LinkedHashMap<>();
		merge.put("relationships", policies.getCrossSourceRelationships()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.map(item -> new RelationshipValue(item.getRelationshipCode(), item.getLeftDatasourceId(),
					item.getLeftModelCode(), item.getLeftKey(), item.getRightDatasourceId(), item.getRightModelCode(),
					item.getRightKey(), Objects.toString(item.getCardinality(), ""), item.getTransformRule(),
					item.getNullPolicy(), item.getUniquenessRule(), item.getConfidence()))
			.sorted(Comparator.comparing(RelationshipValue::sortKey))
			.toList());
		merge.put("policies",
				policies.getMergePolicies()
					.stream()
					.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
					.map(item -> new MergeValue(item.getPolicyCode(), Objects.toString(item.getMergeType(), ""),
							item.getRelationshipCode(), item.getLeftInputKey(), item.getRightInputKey(),
							item.getOutputKey(), item.getInputGrain(), item.getNullPolicy(), item.getDuplicatePolicy(),
							item.getMaxRows(), item.getPartialFailurePolicy(), item.getCalculationExpression()))
					.sorted(Comparator.comparing(MergeValue::sortKey))
					.toList());
		return new SemanticSnapshot(hashJson(authority), hashJson(freshness), hashJson(merge),
				implementationHash(SEMANTIC_RETRIEVER_IMPLEMENTATION),
				hashJson(List.of(implementationHash(SEMANTIC_PLANNER_IMPLEMENTATION),
						implementationHash(PLANNER_IMPLEMENTATION))),
				implementationHash(SQL_GENERATOR_IMPLEMENTATION));
	}

	private RuntimeSnapshot captureRuntime(ProjectRuntimeProfile profile) {
		String updatedAt = Objects.toString(profile.getUpdateTime(), "");
		String revision = hashJson(
				Map.of("projectId", profile.getProjectId(), "runtimeProfileId", profile.getRuntimeProfileId(), "status",
						profile.getStatus(), "revision", profile.getRevision(), "updatedAt", updatedAt));
		return new RuntimeSnapshot(revision, updatedAt, implementationHash(GRAPH_IMPLEMENTATION),
				implementationHash(SQL_GUARD_IMPLEMENTATION), implementationHash(SQL_EXECUTOR_IMPLEMENTATION));
	}

	private EnvironmentSnapshot captureEnvironment(List<ProjectDatasourceBinding> bindings,
			MultiSourcePolicySnapshot policies) {
		Map<Integer, String> dialects = new TreeMap<>();
		for (ProjectDatasourceBinding binding : bindings) {
			Datasource datasource = datasourceService.getDatasourceById(binding.getDatasourceId());
			if (datasource == null || !StringUtils.hasText(datasource.getType())) {
				throw new IllegalStateException("Datasource dialect is unavailable: " + binding.getDatasourceId());
			}
			dialects.put(binding.getDatasourceId(), datasource.getType());
		}
		Set<String> policyZones = policies.getFreshnessPolicies()
			.stream()
			.filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
			.map(MultiSourcePolicySnapshot.FreshnessPolicy::getTimeZone)
			.filter(StringUtils::hasText)
			.collect(Collectors.toCollection(java.util.TreeSet::new));
		String timezone = "system=" + ZoneId.systemDefault().getId() + ";policy=" + String.join(",", policyZones);
		return new EnvironmentSnapshot(Map.copyOf(dialects), timezone);
	}

	private String hashDatasourceExposure(List<ProjectDatasourceBinding> bindings) {
		List<DatasourceExposureValue> values = bindings.stream()
			.map(binding -> new DatasourceExposureValue(binding.getDatasourceId(), binding.getDomainCode(),
					binding.getDomainName(), binding.getResponsibility(), binding.getPriority(),
					binding.getExposedTables() == null ? List.of()
							: binding.getExposedTables().stream().sorted().toList()))
			.sorted(Comparator.comparing(DatasourceExposureValue::sortKey))
			.toList();
		return hashJson(values);
	}

	private String compatibilityHash(ProjectSnapshot project, ModelSnapshot model, PromptSnapshot prompts,
			SemanticSnapshot semantic, RuntimeSnapshot runtime, EnvironmentSnapshot environment) {
		Map<String, Object> values = new TreeMap<>();
		values.put("project", project);
		values.put("model", model);
		values.put("prompts", prompts);
		values.put("semantic", semantic);
		values.put("runtime", runtime);
		values.put("environment", environment);
		return hashJson(values);
	}

	private void validateBoundSnapshot(QueryRun run, ExecutionSnapshot snapshot) {
		if (!Objects.equals(run.projectId(), snapshot.project().projectId())
				|| !Objects.equals(run.projectVersionId(), snapshot.project().projectVersionId())) {
			throw new ExecutionSnapshotMismatchException(run.runId(), List.of("runProjectBinding"));
		}
		String recalculatedCompatibility = compatibilityHash(snapshot.project(), snapshot.model(), snapshot.prompts(),
				snapshot.semantic(), snapshot.runtime(), snapshot.environment());
		if (!Objects.equals(snapshot.compatibilityHash(), recalculatedCompatibility)) {
			throw new ExecutionSnapshotMismatchException(run.runId(), List.of("persistedCompatibilityHash"));
		}
		String recalculatedPlan = snapshot.semanticPlan() == null ? null : hashJson(snapshot.semanticPlan());
		if (!Objects.equals(snapshot.semanticPlanHash(), recalculatedPlan)) {
			throw new ExecutionSnapshotMismatchException(run.runId(), List.of("semanticPlanHash"));
		}
	}

	private void validateRequired(ExecutionSnapshot snapshot) {
		if (snapshot == null || snapshot.project() == null || snapshot.model() == null || snapshot.prompts() == null
				|| snapshot.semantic() == null || snapshot.runtime() == null || snapshot.environment() == null
				|| !StringUtils.hasText(snapshot.compatibilityHash())) {
			throw new IllegalStateException("Typed execution snapshot is incomplete");
		}
	}

	private void compare(List<String> changed, String component, Object expected, Object current) {
		if (!Objects.equals(expected, current)) {
			changed.add(component);
		}
	}

	private String implementationHash(String resourcePath) {
		return implementationHashes.computeIfAbsent(resourcePath, path -> {
			try (InputStream input = ExecutionSnapshotService.class.getClassLoader().getResourceAsStream(path)) {
				if (input == null) {
					throw new IllegalStateException("Runtime implementation resource is unavailable: " + path);
				}
				return sha256(input.readAllBytes());
			}
			catch (Exception ex) {
				throw new IllegalStateException("Unable to fingerprint runtime implementation: " + path, ex);
			}
		});
	}

	private String hashJson(Object value) {
		return canonicalJson.hash(value);
	}

	private String write(ExecutionSnapshot snapshot) {
		return versionedJson.write(JsonPayloadRegistry.EXECUTION_SNAPSHOT, snapshot);
	}

	private String sha256(String value) {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private String sha256(byte[] value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte item : digest) {
				hex.append(String.format("%02x", item));
			}
			return hex.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private record DatasourceExposureValue(Integer datasourceId, String domainCode, String domainName,
			String responsibility, Integer priority, List<String> exposedTables) {
		String sortKey() {
			return Objects.toString(datasourceId, "") + "::" + Objects.toString(domainCode, "");
		}
	}

	private record LogicalBindingValue(String logicalEntityCode, String logicalAttributeCode, Integer datasourceId,
			String modelCode, String columnName, String expression, String transformRule, String grainCode) {
		String sortKey() {
			return Objects.toString(logicalEntityCode, "") + "::" + Objects.toString(logicalAttributeCode, "") + "::"
					+ Objects.toString(datasourceId, "");
		}
	}

	private record AuthorityValue(String logicalAssetType, String logicalAssetCode, Integer datasourceId,
			String sourceRole, Integer priority, Boolean allowFallback, String conditionExpression) {
		String sortKey() {
			return logicalAssetType + "::" + Objects.toString(logicalAssetCode, "") + "::"
					+ Objects.toString(datasourceId, "");
		}
	}

	private record FreshnessValue(Integer datasourceId, String businessDateField, String timeZone, String freshnessType,
			Integer latencyMinutes, String availableUntilRule) {
		String sortKey() {
			return Objects.toString(datasourceId, "");
		}
	}

	private record RelationshipValue(String relationshipCode, Integer leftDatasourceId, String leftModelCode,
			String leftKey, Integer rightDatasourceId, String rightModelCode, String rightKey, String cardinality,
			String transformRule, String nullPolicy, String uniquenessRule, Integer confidence) {
		String sortKey() {
			return Objects.toString(relationshipCode, "");
		}
	}

	private record MergeValue(String policyCode, String mergeType, String relationshipCode, String leftInputKey,
			String rightInputKey, String outputKey, String inputGrain, String nullPolicy, String duplicatePolicy,
			Integer maxRows, String partialFailurePolicy, String calculationExpression) {
		String sortKey() {
			return Objects.toString(policyCode, "");
		}
	}

	public static final class ExecutionSnapshotMismatchException extends IllegalStateException {

		private final String runId;

		private final List<String> changedComponents;

		public ExecutionSnapshotMismatchException(String runId, List<String> changedComponents) {
			super("Durable run execution snapshot no longer matches the current runtime; runId=" + runId + ", changed="
					+ String.join(",", changedComponents));
			this.runId = runId;
			this.changedComponents = List.copyOf(changedComponents);
		}

		public String runId() {
			return runId;
		}

		public List<String> changedComponents() {
			return changedComponents;
		}

	}

}
