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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record RuntimeClarification(String clarificationId, String runId, String question,
		List<ClarificationOption> options, String recommendedOption, String reason, String evidence,
		SemanticIssueType issueType, String assetType, String assetKey, String rawExpression, String resolvedValue,
		String resolutionSource, ClarificationStatus status, String selectedOption, String customAnswer,
		SemanticBindingScope selectedScope, String answeredBy, long revision, LocalDateTime createTime,
		LocalDateTime updateTime) {

    public RuntimeClarification {
        var choices = new java.util.ArrayList<>(options == null ? List.<ClarificationOption>of() : options);
        if (choices.stream().noneMatch(option -> "OTHER".equals(option.code())))
            choices.add(new ClarificationOption("OTHER", "其他，我补充实际含义", "OTHER",
                "候选都不符合时，可用自己的话说明业务口径。", null));
        // This domain invariant also applies when reading old persisted questions. It
        // changes neither their stored answer, revision nor frozen business candidates.
        options = List.copyOf(choices);
    }

    public List<ClarificationOption> businessOptions() {
        return options.stream().filter(option -> isBusinessChoice(option.code())).toList();
    }

    static boolean isBusinessChoice(String code) {
        return !"OTHER".equals(code) && !"CANCEL".equals(code);
    }

	public enum ClarificationStatus {

		PENDING, ANSWERED, SUPERSEDED, EXPIRED

	}

	public record ClarificationOption(String code, String label, String value, String reason, String evidence) {
	}

}
