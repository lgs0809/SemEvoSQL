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
package cn.lgs.semevosql.semantic.compiler;

import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/** Capability-based routing boundary for deterministic SQL generation. */
public final class LoweringCapabilityProbe {

	private static final Set<Capability> DETERMINISTIC_CAPABILITIES = Set.of(Capability.PROJECTION,
			Capability.FILTER, Capability.AGGREGATION, Capability.GROUPING, Capability.ORDERING, Capability.LIMIT,
			Capability.JOIN, Capability.TIME_FILTER, Capability.TIME_BUCKET, Capability.CONDITIONAL_AGGREGATION,
			Capability.MULTI_SOURCE, Capability.CROSS_SOURCE_MERGE, Capability.SCALAR_COMPOSITION);

	private LoweringCapabilityProbe() {
	}

	/** Intent-only probe retained for planning, metrics and backwards-compatible tests. */
	public static Decision probe(SemanticBlueprint plan) {
		if (plan == null) {
			return Decision.invalid("Semantic Blueprint is required");
		}
		if (!plan.isExecutable() || (plan.getValidationErrors() != null && !plan.getValidationErrors().isEmpty())) {
			return Decision.invalid("Semantic Blueprint is not executable");
		}
		Set<Capability> required = effectiveCapabilities(plan);
		Set<Capability> unsupported = new LinkedHashSet<>(required);
		unsupported.removeAll(DETERMINISTIC_CAPABILITIES);
		if (!unsupported.isEmpty()) {
			return Decision.requiresGeneration(required, unsupported,
					"Deterministic SQL generator does not implement: " + unsupported.stream().map(Enum::name).sorted()
						.collect(Collectors.joining(", ")));
		}
		if (!"DETERMINISTIC".equalsIgnoreCase(plan.getCompilerMode())) {
			return Decision.requiresGeneration(required, Set.of(),
					"Semantic Blueprint has no complete governed deterministic projection");
		}
		return Decision.supported(required);
	}

	/**
	 * Execution-time probe. In addition to computation intent, this validates the current Catalog and dialect
	 * context so a SUPPORTED decision means the fast path is actually lowerable in the active environment.
	 */
	public static Decision probe(SemanticBlueprint plan, SemanticCatalogSnapshot catalog,
			Map<Integer, SqlDialect> dialects) {
		Decision intent = probe(plan);
		if (intent.status() != Status.SUPPORTED) {
			return intent;
		}
		if (catalog == null) {
			return Decision.invalid("Semantic Catalog is required for deterministic lowering");
		}
		if (plan.getProjections() == null || plan.getProjections().isEmpty()) {
			return Decision.requiresGeneration(intent.requiredCapabilities(), Set.of(),
					"No governed projection is available for deterministic SQL");
		}

		Set<String> enabledModels = catalog.getModels().stream()
			.filter(SemanticCatalogSnapshot.Model::isEnabled)
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.filter(StringUtils::hasText)
			.collect(Collectors.toUnmodifiableSet());
		Set<String> referencedModels = referencedModels(plan);
		Set<String> missingModels = new LinkedHashSet<>(referencedModels);
		missingModels.removeAll(enabledModels);
		if (!missingModels.isEmpty()) {
			return Decision.invalid("Semantic Blueprint references models not enabled in the active Catalog: "
					+ missingModels.stream().sorted().collect(Collectors.joining(", ")));
		}

		Set<Integer> enabledDatasources = catalog.enabledDatasourceIds();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			Integer datasourceId = source.getDatasourceId();
			if (datasourceId == null || !enabledDatasources.contains(datasourceId)) {
				return Decision.invalid("Semantic Blueprint references a datasource not enabled in the active Catalog: "
						+ datasourceId);
			}
			SqlDialect dialect = dialects == null ? null : dialects.get(datasourceId);
			if (dialect == null) {
				return Decision.invalid("SQL dialect is unavailable for datasource " + datasourceId);
			}
			Decision dialectDecision = validateDialectRequirements(plan, source, dialect, intent.requiredCapabilities());
			if (dialectDecision != null) {
				return dialectDecision;
			}
		}
		return intent;
	}

	private static Decision validateDialectRequirements(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source,
			SqlDialect dialect, Set<Capability> required) {
		Set<String> sourceModels = Set.copyOf(source.getModelCodes());
		for (SemanticBlueprint.GroupSelection group : plan.getGroupBy()) {
			if (!StringUtils.hasText(group.getTimeBucketGranularity()) || !sourceModels.contains(group.getModelCode())) {
				continue;
			}
			try {
				dialect.timeBucket("probe_ts", group.getTimeBucketGranularity());
			}
			catch (IllegalArgumentException unsupported) {
				return Decision.requiresGeneration(required, Set.of(Capability.TIME_BUCKET),
						"Deterministic SQL generator cannot lower time bucket " + group.getTimeBucketGranularity()
								+ " for " + dialect.name());
			}
		}
		return null;
	}

	private static Set<String> referencedModels(SemanticBlueprint plan) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getProjections().stream().map(SemanticBlueprint.ProjectionSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getFilters().stream().map(SemanticBlueprint.FilterSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getGroupBy().stream().map(SemanticBlueprint.GroupSelection::getModelCode).filter(StringUtils::hasText)
			.forEach(result::add);
		plan.getRelationships().forEach(relationship -> {
			if (StringUtils.hasText(relationship.getSourceModelCode())) {
				result.add(relationship.getSourceModelCode());
			}
			if (StringUtils.hasText(relationship.getTargetModelCode())) {
				result.add(relationship.getTargetModelCode());
			}
		});
		plan.getSourceSubPlans().stream().flatMap(source -> source.getModelCodes().stream()).filter(StringUtils::hasText)
			.forEach(result::add);
		return Set.copyOf(result);
	}

	public static Set<Capability> effectiveCapabilities(SemanticBlueprint plan) {
		LinkedHashSet<Capability> capabilities = new LinkedHashSet<>();
		ComputationIntent declared = plan == null ? null : plan.getComputationIntent();
		if (declared != null) {
			capabilities.addAll(declared.capabilities());
		}
		if (plan == null) {
			return Set.copyOf(capabilities);
		}
		if (plan.getProjections() != null && !plan.getProjections().isEmpty()) {
			capabilities.add(Capability.PROJECTION);
		}
		if (plan.getFilters() != null && !plan.getFilters().isEmpty()) {
			capabilities.add(Capability.FILTER);
		}
		if (plan.getMetrics() != null && !plan.getMetrics().isEmpty()) {
			capabilities.add(Capability.AGGREGATION);
			if (plan.getMetrics().stream().anyMatch(metric -> StringUtils.hasText(metric.getFilterExpression()))) {
				capabilities.add(Capability.CONDITIONAL_AGGREGATION);
			}
		}
		if (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty()) {
			capabilities.add(Capability.GROUPING);
			if (plan.getGroupBy().stream().anyMatch(group -> StringUtils.hasText(group.getTimeBucketGranularity()))) {
				capabilities.add(Capability.TIME_BUCKET);
			}
		}
		if (plan.getOrderBy() != null && !plan.getOrderBy().isEmpty()) {
			capabilities.add(Capability.ORDERING);
		}
		if (plan.getLimit() != null) {
			capabilities.add(Capability.LIMIT);
		}
		if (plan.getTimeRange() != null) {
			capabilities.add(Capability.TIME_FILTER);
		}
		if (plan.getRelationships() != null && !plan.getRelationships().isEmpty()) {
			capabilities.add(Capability.JOIN);
		}
		if (plan.getSourceSubPlans() != null && plan.getSourceSubPlans().size() > 1) {
			capabilities.add(Capability.MULTI_SOURCE);
		}
		if (plan.getMergePlan() != null) {
			capabilities.add(Capability.CROSS_SOURCE_MERGE);
			if (StringUtils.hasText(plan.getMergePlan().getCalculationExpression())) {
				capabilities.add(Capability.SCALAR_COMPOSITION);
			}
		}
		return Set.copyOf(capabilities);
	}

	public enum Status {
		SUPPORTED,
		REQUIRES_GENERATION,
		INVALID
	}

	public record Decision(Status status, Set<Capability> requiredCapabilities, Set<Capability> unsupportedCapabilities,
			String reason) {
		public Decision {
			requiredCapabilities = Set.copyOf(requiredCapabilities == null ? Set.of() : requiredCapabilities);
			unsupportedCapabilities = Set.copyOf(unsupportedCapabilities == null ? Set.of() : unsupportedCapabilities);
		}

		static Decision supported(Set<Capability> required) {
			return new Decision(Status.SUPPORTED, required, Set.of(), "Deterministic SQL generation fully covers intent");
		}

		static Decision requiresGeneration(Set<Capability> required, Set<Capability> unsupported, String reason) {
			return new Decision(Status.REQUIRES_GENERATION, required, unsupported, reason);
		}

		static Decision invalid(String reason) {
			return new Decision(Status.INVALID, Set.of(), Set.of(), reason);
		}

		public boolean supported() {
			return status == Status.SUPPORTED;
		}
	}
}
