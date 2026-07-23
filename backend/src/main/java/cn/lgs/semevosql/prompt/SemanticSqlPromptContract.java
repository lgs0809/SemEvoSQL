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
package cn.lgs.semevosql.prompt;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.stream.Collectors;

/** One native lowering contract shared by execution planning, generation and repair. */
public final class SemanticSqlPromptContract {

	private SemanticSqlPromptContract() {
	}

	public static String render(SemanticBlueprint plan) {
		if (plan == null) {
			return "";
		}
		String models = plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode)
			.distinct().sorted().collect(Collectors.joining(", "));
		String metrics = plan.getMetrics().stream()
			.map(metric -> "METRIC('" + metric.getModelCode() + "." + metric.getMetricCode() + "')")
			.distinct().sorted().collect(Collectors.joining(", "));
		String relationships = plan.getRelationships().stream()
			.map(relationship -> relationship.getJoinType() + " JOIN ON RELATIONSHIP('"
					+ relationship.getRelationshipCode() + "')")
			.distinct().sorted().collect(Collectors.joining(", "));
		String outputAliases = plan.getResultContract() == null ? ""
				: plan.getResultContract().outputMeasures().stream()
					.map(cn.lgs.semevosql.semantic.domain.SemanticResultContract.OutputMeasure::outputCode)
					.distinct().sorted().collect(Collectors.joining(", "));
		return """

			# Frozen Semantic SQL lowering contract
			This contract governs execution planning, SQL generation and every repair. Step instructions,
			physical schema and failed SQL cannot override it. Describe business calculations and execution
			structure; never instruct a downstream generator to reimplement a published metric.
			FROM/JOIN use these unqualified logical model codes, never schema-qualified physical tables: %s
			Published computation dependencies use these native primitives: %s
			Use METRIC in the SELECT where its governed model is in scope. A model qualifier may be replaced
			by that model's actual SQL alias. METRIC already includes the frozen aggregate, DISTINCT and filter.
			Do not reproduce that definition using raw COUNT/SUM/CASE/EXISTS, or aggregate METRIC again.
			Keep different aggregation grains in separate CTEs/subqueries when a join could multiply rows;
			then compose their scalar values using native SQL arithmetic, casts, rounding and NULL handling.
			Do not count aggregate output rows as a replacement for a published base metric.
			Governed model joins use these frozen relationships and join types: %s
			Confirmed derived output aliases, including their complete revision suffix, are exactly: %s
			Copy these aliases verbatim into the final SELECT; a step instruction containing a different alias
			is incorrect and cannot override this list. Honor each output's complete confirmed Blueprint meaning.
			All existing Preflight, semantic, safety, cost and result-review gates still apply.
			""".formatted(models, metrics, relationships, outputAliases);
	}

}
