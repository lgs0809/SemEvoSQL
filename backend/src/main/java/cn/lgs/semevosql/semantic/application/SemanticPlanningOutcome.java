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
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import java.util.List;

/** Explicit semantic-planner protocol. */
public sealed interface SemanticPlanningOutcome permits SemanticPlanningOutcome.Resolved,
		SemanticPlanningOutcome.ClarificationRequired, SemanticPlanningOutcome.Rejected {

	Status status();

	enum Status {
		RESOLVED,
		NEEDS_CLARIFICATION,
		UNRESOLVABLE
	}

	record Resolved(QueryCaseHints binding, ComputationIntent computationIntent) implements SemanticPlanningOutcome {
		public Resolved(QueryCaseHints binding) {
			this(binding, ComputationIntent.empty());
		}

		public Resolved {
			computationIntent = computationIntent == null ? ComputationIntent.empty() : computationIntent;
		}

		@Override
		public Status status() {
			return Status.RESOLVED;
		}
	}

	record ClarificationRequired(String issueType, String question, List<Option> options,
			String reason) implements SemanticPlanningOutcome {
		public ClarificationRequired {
			options = List.copyOf(options == null ? List.of() : options);
		}

		@Override
		public Status status() {
			return Status.NEEDS_CLARIFICATION;
		}
	}

	record Rejected(String errorCode, String reason) implements SemanticPlanningOutcome {
		@Override
		public Status status() {
			return Status.UNRESOLVABLE;
		}
	}

	record Option(String code, String label, String assetType, String assetKey) {
	}
}
