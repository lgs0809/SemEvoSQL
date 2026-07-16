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

import java.util.List;
import cn.lgs.semevosql.learning.QueryCaseHints;
import org.springframework.util.StringUtils;

/**
 * The requested answer and accepted supporting constraints have different roles.
 * A formula mentioned in a clarification or definition is evidence, not another
 * requested output. Literal/time validation may use accepted constraints; output
 * coverage must use the question itself.
 */
public record SemanticPlanningInput(String question, String confirmedRequest, String currentUserMessage,
        List<DefinitionConfirmation> definitionConfirmations, QueryCaseHints previousTaskHints) {
    public SemanticPlanningInput(String question, String confirmedRequest, String currentUserMessage,
            List<DefinitionConfirmation> definitionConfirmations) {
        this(question, confirmedRequest, currentUserMessage, definitionConfirmations, QueryCaseHints.empty());
    }
    public SemanticPlanningInput(String question, String confirmedRequest) {
        this(question, confirmedRequest, question, List.of());
    }
    public SemanticPlanningInput {
        if (!StringUtils.hasText(question)) throw new IllegalArgumentException("Planning question is required");
        confirmedRequest = StringUtils.hasText(confirmedRequest) ? confirmedRequest : question;
        currentUserMessage = StringUtils.hasText(currentUserMessage) ? currentUserMessage : question;
        definitionConfirmations = List.copyOf(definitionConfirmations == null ? List.of() : definitionConfirmations);
        previousTaskHints = previousTaskHints == null ? QueryCaseHints.empty() : previousTaskHints;
    }

    /** Actual submitted HITL receipts, including the save scope; historical definitions are separate candidates. */
    public record DefinitionConfirmation(String phrase,String definitionText,String selectedScope,
            String clarificationId,String answeredBy,long sourceRevision) {
        public DefinitionConfirmation(String phrase,String definitionText,String selectedScope,
                String clarificationId,String answeredBy) {
            this(phrase,definitionText,selectedScope,clarificationId,answeredBy,0);
        }
    }

    public static SemanticPlanningInput plain(String question) {
        return new SemanticPlanningInput(question, question);
    }
}
