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

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.semantic.application.ScenarioResolutionService.ResolvedBinding;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.BusinessQueryRequirement;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.semevosql.semantic.domain.ScenarioResolution;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Release-time compile preflight for declared CORE business query scenarios. It never
 * executes production SQL; it proves that the resolved business requirements can still be
 * rebound into an executable Semantic Blueprint and deterministically compiled for
 * the current datasource dialects.
 */
@Service
@RequiredArgsConstructor
public class ScenarioPreflightService {

	private final BusinessQueryScenarioRepository scenarioRepository;

	private final ScenarioResolutionService scenarioResolutionService;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticCatalogApplicationService catalogApplicationService;

	private final SemanticSqlCompiler sqlCompiler;

	private final DatabaseUtil databaseUtil;

	private final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

	public PreflightReport preflightCore(Long projectId, Long projectVersionId) {
		List<BusinessQueryScenario> scenarios = scenarioRepository.findActiveByVersion(projectVersionId)
			.stream()
			.filter(scenario -> scenario.getImportance() == BusinessQueryScenario.Importance.CORE)
			.toList();
		List<ScenarioPreflightResult> results = scenarios.stream().map(this::preflight).toList();
		long passed = results.stream().filter(ScenarioPreflightResult::passed).count();
		return new PreflightReport(results.size(), (int) passed, results.size() - (int) passed, List.copyOf(results));
	}

	private ScenarioPreflightResult preflight(BusinessQueryScenario scenario) {
		try {
			ScenarioResolution resolution = scenarioResolutionService.get(scenario.getId());
			if (resolution.getStatus() != ScenarioResolution.Status.RESOLVED) {
				return failed(scenario, "Scenario resolution is " + resolution.getStatus());
			}
			List<ResolvedBinding> bindings = readBindings(resolution.getResolvedBindingsJson());
			BusinessQueryRequirement requirement = readRequirement(scenario.getRequirementJson());
			SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(scenario.getProjectId(),
					scenario.getProjectVersionId());
			QueryCaseHints hints = strictHints(scenario, bindings);
			List<String> selectedTables = selectedPhysicalTables(catalog, bindings);
			if (selectedTables.isEmpty()) {
				return failed(scenario, "Resolved scenario has no physical model binding");
			}
			String canonicalQuery = canonicalQuery(scenario, requirement);
			SemanticBlueprint plan = catalogApplicationService.buildBlueprint(scenario.getProjectId(),
					scenario.getProjectVersionId(), canonicalQuery, selectedTables, hints);
			List<String> contractViolations = validateResolvedContract(bindings, plan);
			if (!contractViolations.isEmpty()) {
				return failed(scenario, String.join("; ", contractViolations));
			}
			if (!plan.isExecutable()) {
				return failed(scenario,
						"Semantic Blueprint is not executable: " + String.join("; ", plan.getValidationErrors()));
			}
			if (!"DETERMINISTIC".equalsIgnoreCase(plan.getCompilerMode())) {
				return failed(scenario, "Semantic Blueprint requires constrained generation: " + plan.getCompilerMode());
			}
			CompiledSemanticQuery compiled = sqlCompiler.compile(plan, catalog, dialects(plan), Clock.systemUTC(),
					ZoneId.of("UTC"));
			if (compiled.sources().isEmpty()) {
				return failed(scenario, "Deterministic compiler produced no source query");
			}
			return new ScenarioPreflightResult(scenario.getId(), scenario.getScenarioCode(), scenario.getBusinessName(),
					true, plan.getCompilerMode(), compiled.sources().size(), null);
		}
		catch (Exception ex) {
			return failed(scenario, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		}
	}

	private QueryCaseHints strictHints(BusinessQueryScenario scenario, List<ResolvedBinding> bindings) {
		Set<String> models = new LinkedHashSet<>();
		Set<String> metrics = new LinkedHashSet<>();
		Set<String> dimensions = new LinkedHashSet<>();
		Set<String> rules = new LinkedHashSet<>();
		Set<String> relationships = new LinkedHashSet<>();
		List<EnumBindingHint> enums = new ArrayList<>();
		TimeBindingHint time = null;
		for (ResolvedBinding binding : bindings) {
			if (hasText(binding.modelCode())) {
				models.add(binding.modelCode());
			}
			switch (Objects.toString(binding.assetType(), "")) {
				case "METRIC" -> metrics.add(binding.assetKey());
				case "DIMENSION" -> dimensions.add(binding.assetKey());
				case "RULE" -> rules.add(binding.assetKey());
				case "RELATIONSHIP" -> relationships.add(binding.assetKey());
				case "ENUM_VALUE" -> {
					String[] parts = binding.assetKey().split(":", 3);
					if (parts.length != 3) {
						throw new IllegalArgumentException("Invalid ENUM_VALUE binding: " + binding.assetKey());
					}
					enums.add(new EnumBindingHint(binding.requirementText(), parts[0], parts[1], parts[2],
							"scenario:" + scenario.getId(), 1));
				}
				case "TIME_COLUMN" -> {
					String[] parts = binding.assetKey().split(":", 2);
					if (parts.length != 2) {
						throw new IllegalArgumentException("Invalid TIME_COLUMN binding: " + binding.assetKey());
					}
					time = new TimeBindingHint(binding.requirementText(), parts[0], parts[1],
							"scenario:" + scenario.getId(), 1);
				}
				default -> {
				}
			}
		}
		return new QueryCaseHints(Set.copyOf(models), Set.copyOf(metrics), Set.copyOf(dimensions), Set.of(),
				Set.copyOf(relationships), Set.copyOf(rules), List.copyOf(enums), time, true, "SCENARIO_PREFLIGHT",
				List.of("scenario:" + scenario.getId()), 1, Map.of());
	}

	private List<String> selectedPhysicalTables(SemanticCatalogSnapshot catalog, List<ResolvedBinding> bindings) {
		Set<String> modelCodes = bindings.stream()
			.map(ResolvedBinding::modelCode)
			.filter(this::hasText)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> modelCodes.contains(model.getModelCode()))
			.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
			.filter(this::hasText)
			.distinct()
			.toList();
	}

	private List<String> validateResolvedContract(List<ResolvedBinding> bindings, SemanticBlueprint plan) {
		List<String> violations = new ArrayList<>();
		Set<String> planMetrics = plan.getMetrics()
			.stream()
			.map(SemanticBlueprint.MetricSelection::getMetricCode)
			.collect(Collectors.toSet());
		Set<String> planDimensions = plan.getDimensions()
			.stream()
			.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
			.collect(Collectors.toSet());
		Set<String> planRules = plan.getRules()
			.stream()
			.map(SemanticBlueprint.RuleSelection::getRuleCode)
			.collect(Collectors.toSet());
		Set<String> planRelationships = plan.getRelationships()
			.stream()
			.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
			.collect(Collectors.toSet());
		Set<String> planEnums = plan.getEnumResolutions()
			.stream()
			.map(value -> value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode())
			.collect(Collectors.toSet());
		for (ResolvedBinding binding : bindings) {
			switch (Objects.toString(binding.assetType(), "")) {
				case "METRIC" ->
					require(planMetrics.contains(binding.assetKey()), "metric", binding.assetKey(), violations);
				case "DIMENSION" ->
					require(planDimensions.contains(binding.assetKey()), "dimension", binding.assetKey(), violations);
				case "RULE" -> require(planRules.contains(binding.assetKey()), "rule", binding.assetKey(), violations);
				case "RELATIONSHIP" -> require(planRelationships.contains(binding.assetKey()), "relationship",
						binding.assetKey(), violations);
				case "ENUM_VALUE" ->
					require(planEnums.contains(binding.assetKey()), "enum", binding.assetKey(), violations);
				case "TIME_COLUMN" -> {
					String actual = plan.getTimeRange() == null ? null
							: plan.getTimeRange().getModelCode() + ":" + plan.getTimeRange().getTimeColumn();
					boolean preservedByMetric = plan.getMetrics()
						.stream()
						.anyMatch(metric -> Objects.equals(binding.assetKey(),
								metric.getModelCode() + ":" + Objects.toString(metric.getTimeColumn(), "")));
					boolean preservedByDimension = plan.getDimensions()
						.stream()
						.anyMatch(dimension -> Objects.equals(binding.assetKey(),
								dimension.getModelCode() + ":" + Objects.toString(dimension.getColumnName(), "")));
					require(Objects.equals(actual, binding.assetKey()) || preservedByMetric || preservedByDimension, "time column",
							binding.assetKey(), violations);
				}
				default -> {
				}
			}
		}
		return List.copyOf(violations);
	}

	private void require(boolean valid, String type, String key, List<String> violations) {
		if (!valid) {
			violations.add("Resolved " + type + " was not preserved in Semantic Blueprint: " + key);
		}
	}

	private Map<Integer, SqlDialect> dialects(SemanticBlueprint plan) {
		Map<Integer, SqlDialect> values = new LinkedHashMap<>();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			if (source.getDatasourceId() != null) {
				values.put(source.getDatasourceId(),
						SqlDialect.from(databaseUtil.getDatasourceDbConfig(source.getDatasourceId()).getDialectType()));
			}
		}
		return Map.copyOf(values);
	}

	private String canonicalQuery(BusinessQueryScenario scenario, BusinessQueryRequirement requirement) {
		List<String> parts = new ArrayList<>();
		add(parts, scenario.getBusinessName());
		parts.addAll(requirement.measures());
		parts.addAll(requirement.attributes());
		parts.addAll(requirement.filters());
		parts.addAll(requirement.timeConstraints());
		parts.addAll(requirement.groupings());
		parts.addAll(requirement.sorting());
		add(parts, requirement.comparison());
		if (requirement.limit() != null && requirement.limit() > 0) {
			parts.add("前" + requirement.limit());
		}
		return parts.stream().filter(this::hasText).distinct().collect(Collectors.joining(" "));
	}

	private void add(List<String> values, String value) {
		if (hasText(value)) {
			values.add(value.trim());
		}
	}

	private List<ResolvedBinding> readBindings(String value) {
		try {
			return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<List<ResolvedBinding>>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid scenario resolved bindings", ex);
		}
	}

	private BusinessQueryRequirement readRequirement(String value) {
		try {
			return objectMapper.readValue(value == null ? "{}" : value, BusinessQueryRequirement.class);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid business query requirement", ex);
		}
	}

	private ScenarioPreflightResult failed(BusinessQueryScenario scenario, String reason) {
		return new ScenarioPreflightResult(scenario.getId(), scenario.getScenarioCode(), scenario.getBusinessName(),
				false, null, 0, reason);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record PreflightReport(int total, int passed, int failed, List<ScenarioPreflightResult> results) {

		public boolean passedAll() {
			return failed == 0;
		}

		public List<String> failures() {
			return results.stream()
				.filter(result -> !result.passed())
				.map(result -> result.businessName() + ": " + result.failureReason())
				.toList();
		}
	}

	public record ScenarioPreflightResult(Long scenarioId, String scenarioCode, String businessName, boolean passed,
			String compilerMode, int compiledSourceCount, String failureReason) {
	}

}
