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

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyEvolutionChangeSetBridgeTest {

    private final VersionedJson versionedJson = new VersionedJson();

    private final LegacyEvolutionChangeSetBridge bridge = new LegacyEvolutionChangeSetBridge(null, null, null,
            versionedJson);

    @Test
    void proposalOnlyPayloadDoesNotCreateSemanticOperations() {
        String proposalOnly = """
                {"schemaVersion":1,"sourceVersionId":5,"sourceCatalogHash":"hash","proposalOnly":true,"operations":[]}
                """;

        assertThat(bridge.hasSemanticOperations(proposalOnly)).isFalse();
    }

    @Test
    void versionedSemanticPatchEnvelopeExposesOperations() {
        SemanticPatch patch = new SemanticPatch(1, 5L, "hash",
                List.of(new Operation(OperationType.UPDATE_RULE, "RULE", "rule_acceptance", "fingerprint",
                        Map.of("description", "updated"), List.of())));
        String envelope = versionedJson.write(JsonPayloadRegistry.SEMANTIC_PATCH, patch);

        assertThat(bridge.hasSemanticOperations(envelope)).isTrue();
    }
}
