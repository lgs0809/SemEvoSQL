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

/** Typed boundary used when semantic planning requires user clarification. */
public class SemanticPlanningClarificationRequiredException extends IllegalStateException {

	private final SemanticPlanningOutcome.ClarificationRequired clarification;
    private final java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls;

	public SemanticPlanningClarificationRequiredException(
			SemanticPlanningOutcome.ClarificationRequired clarification) {
        this(clarification,java.util.List.of());
    }

    public SemanticPlanningClarificationRequiredException(SemanticPlanningOutcome.ClarificationRequired clarification,
            java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls) {
		super(clarification == null ? "Semantic planning requires clarification" : clarification.question());
		this.clarification = clarification;
        this.modelCalls=java.util.List.copyOf(modelCalls);
	}

    public java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls() {
        return modelCalls;
    }

	public SemanticPlanningOutcome.ClarificationRequired clarification() {
		return clarification;
	}
}
