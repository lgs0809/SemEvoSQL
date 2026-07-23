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
package cn.lgs.semevosql.evolution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticEvolutionReleaseOrchestratorTest {

    @Test
    void replayDecisionPreservesNullablePreMaterializationSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("targetVersionId", null);
        summary.put("semanticChangeSetId", "change-set-1");

        var decision = new SemanticEvolutionReleaseOrchestrator.ReplayDecision(true, "replay-1", 10, 10, true,
                summary);

        assertThat(decision.summary()).containsEntry("semanticChangeSetId", "change-set-1");
        assertThat(decision.summary()).containsKey("targetVersionId");
        assertThat(decision.summary().get("targetVersionId")).isNull();
        assertThatThrownBy(() -> decision.summary().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
    }
}
