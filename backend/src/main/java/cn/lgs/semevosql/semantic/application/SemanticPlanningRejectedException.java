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

/** Typed terminal semantic-planning failure with a stable attribution code. */
public class SemanticPlanningRejectedException extends IllegalStateException {

	private final String errorCode;

    private final java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls;

	public SemanticPlanningRejectedException(String errorCode, String message) {
		this(errorCode,message,java.util.List.of());
	}

    public SemanticPlanningRejectedException(String errorCode,String message,
            java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls) {
		super(message);
		this.errorCode = errorCode;
        this.modelCalls=java.util.List.copyOf(modelCalls);
	}

    public java.util.List<cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult> modelCalls() {
        return modelCalls;
    }

	public String errorCode() {
		return errorCode;
	}
}
