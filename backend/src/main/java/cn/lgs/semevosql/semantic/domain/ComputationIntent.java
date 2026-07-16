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
package cn.lgs.semevosql.semantic.domain;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A deliberately thin description of the computation the answer requires.
 *
 * <p>This is not a SQL AST or executable DSL. It describes required capabilities and small semantic parameters only;
 * the deterministic SQL generator or constrained Semantic SQL generator remains free to choose the physical SQL
 * structure.</p>
 */
public record ComputationIntent(Set<Capability> capabilities, List<Requirement> requirements) {

	public ComputationIntent {
		requirements = List.copyOf(new LinkedHashSet<>(requirements == null ? List.of() : requirements));
		LinkedHashSet<Capability> normalizedCapabilities = new LinkedHashSet<>(capabilities == null ? Set.of() : capabilities);
		requirements.stream().map(Requirement::capability).forEach(normalizedCapabilities::add);
		capabilities = Set.copyOf(normalizedCapabilities);
	}

	public ComputationIntent(Set<Capability> capabilities) {
		this(capabilities, List.of());
	}

	public static ComputationIntent empty() {
		return new ComputationIntent(Set.of(), List.of());
	}

	public boolean requires(Capability capability) {
		return capability != null && capabilities.contains(capability);
	}

	public boolean requiresExplicitTimeAxis() {
		return capabilities.stream().anyMatch(Capability::requiresExplicitTimeAxis);
	}

	public List<Requirement> canonicalRequirements() {
		List<Requirement> normalized = requirements.stream().filter(value -> value.capability() != Capability.ORDERING)
			.sorted(Comparator.comparing((Requirement value) -> value.capability().name())
				.thenComparing(value -> Objects.toString(value.metricCode(), ""))
				.thenComparing(value -> Objects.toString(value.grain(), ""))
				.thenComparing(value -> Objects.toString(value.mode(), ""))
				.thenComparing(value -> value.limit() == null ? -1 : value.limit())
				.thenComparing(value -> Objects.toString(value.scope(), ""))
				.thenComparing(value -> Objects.toString(value.basis(), ""))
                .thenComparing(value -> Objects.toString(value.dimensionCode(), ""))
                .thenComparing(value -> Objects.toString(value.nullReplacement(), ""))
                .thenComparing(value -> value.offset()==null ? 0L : value.offset()))
			.toList();
        // Ordering is a sequence (primary key, tie breaker), unlike the unordered capability refinements.
        return java.util.stream.Stream.concat(normalized.stream(), requirements.stream()
            .filter(value -> value.capability() == Capability.ORDERING)).toList();
	}

	/** Thin non-executable parameters that refine WHAT must be computed, never SQL structure. */
	public record Requirement(Capability capability, String metricCode, String grain, String mode, Integer limit,
			String scope, String basis,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long offset,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String dimensionCode,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String nullReplacement) {

        public Requirement(Capability capability,String metricCode,String grain,String mode,Integer limit,String scope,String basis,Long offset,String dimensionCode) {
            this(capability,metricCode,grain,mode,limit,scope,basis,offset,dimensionCode,null);
        }

        public Requirement(Capability capability,String metricCode,String grain,String mode,Integer limit,String scope,String basis,Long offset) {
            this(capability,metricCode,grain,mode,limit,scope,basis,offset,null);
        }

        public Requirement(Capability capability,String metricCode,String grain,String mode,Integer limit,String scope,String basis) {
            this(capability,metricCode,grain,mode,limit,scope,basis,null);
        }
		public Requirement {
			if (capability == null) {
				throw new IllegalArgumentException("Computation requirement capability is required");
			}
            if (offset!=null && (offset<0 || capability!=Capability.OFFSET))
                throw new IllegalArgumentException("Offset must be nonnegative and belong to OFFSET capability");
            if (capability==Capability.OFFSET && offset==null)
                throw new IllegalArgumentException("OFFSET requires an explicit offset");
			metricCode = trim(metricCode);
            dimensionCode = trim(dimensionCode);
            if (dimensionCode != null && ((capability != Capability.ORDERING && capability != Capability.NULL_REPLACEMENT) || metricCode != null))
                throw new IllegalArgumentException("Requirement must target one selected dimension");
            if(capability==Capability.NULL_REPLACEMENT) {
                if(dimensionCode==null || nullReplacement==null || nullReplacement.isBlank() || nullReplacement.length()>128
                        || nullReplacement.indexOf('\\')>=0 || nullReplacement.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("NULL_REPLACEMENT requires a selected dimension and a bounded display label");
            } else if(nullReplacement!=null)throw new IllegalArgumentException("Null replacement belongs only to NULL_REPLACEMENT");
			grain = upper(grain);
			mode = upper(mode);
			scope = upper(scope);
			basis = upper(basis);
			if (limit != null && limit <= 0) {
				throw new IllegalArgumentException("Computation requirement limit must be positive");
			}
		}

		private static String trim(String value) {
			if (value == null) {
				return null;
			}
			String normalized = value.trim();
			return normalized.isEmpty() ? null : normalized;
		}

		private static String upper(String value) {
			String normalized = trim(value);
			return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
		}
	}

	public enum Capability {
		PROJECTION(false),
		FILTER(false),
		AGGREGATION(false),
		GROUPING(false),
		ORDERING(false),
		LIMIT(false),
        OFFSET(false),
		JOIN(false),
		TIME_FILTER(true),
		TIME_BUCKET(true),
		CONDITIONAL_AGGREGATION(false),
		PERIOD_COMPARISON(true),
		WINDOW_ANALYTICS(true),
		PARTITION_RANKING(false),
		MULTI_STAGE_AGGREGATION(false),
		SET_OPERATION(false),
		RECURSIVE_QUERY(false),
		COHORT_ANALYSIS(true),
		MULTI_SOURCE(false),
		CROSS_SOURCE_MERGE(false),
		SCALAR_COMPOSITION(false),
        NULL_REPLACEMENT(false);

		private final boolean requiresExplicitTimeAxis;

		Capability(boolean requiresExplicitTimeAxis) {
			this.requiresExplicitTimeAxis = requiresExplicitTimeAxis;
		}

		public boolean requiresExplicitTimeAxis() {
			return requiresExplicitTimeAxis;
		}
	}
}
