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
package cn.lgs.semevosql.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPatchValidatorRiskTest {

    private final SemanticPatchValidator validator = new SemanticPatchValidator(null, null, null);

    @Test
    void highRiskOperationRaisesMediumCandidateToHigh() {
        SemanticPatch patch = patch(OperationType.UPDATE_RULE, "RULE", "rule_acceptance");

        assertThat(validator.effectiveRiskLevel(patch, "MEDIUM")).isEqualTo("HIGH");
        assertThat(validator.effectiveRiskLevel(patch, "LOW")).isEqualTo("HIGH");
        assertThat(validator.effectiveRiskLevel(patch, "CRITICAL")).isEqualTo("CRITICAL");
    }

    @Test
    void lowerRiskOperationDoesNotArtificiallyRaiseRisk() {
        SemanticPatch patch = patch(OperationType.UPDATE_DIMENSION, "DIMENSION", "region");

        assertThat(validator.effectiveRiskLevel(patch, "MEDIUM")).isEqualTo("MEDIUM");
        assertThat(validator.effectiveRiskLevel(patch, "HIGH")).isEqualTo("HIGH");
    }

    @Test
    void projectAliasUsesNaturalLanguageIdentityButKeepsStableTargetCode() {
        Operation alias = new Operation(OperationType.ADD_PROJECT_ALIAS, "PROJECT_ALIAS", "按业务时间统计实付金额趋势", null,
                Map.of("phrase", "按业务时间统计实付金额趋势", "targetAssetType", "DIMENSION", "targetAssetKey", "payment_time",
                        "businessLabel", "支付时间"), List.of());

        assertThat(validator.validAssetKey(alias)).isTrue();
        assertThat(validator.validProjectAliasTarget(alias)).isTrue();
    }

    @Test
    void projectAliasRejectsPunctuationOnlyIdentityAndSqlLikeTarget() {
        Operation alias = new Operation(OperationType.ADD_PROJECT_ALIAS, "PROJECT_ALIAS", "--", null,
                Map.of("targetAssetKey", "payment time;drop"), List.of());

        assertThat(validator.validAssetKey(alias)).isFalse();
        assertThat(validator.validProjectAliasTarget(alias)).isFalse();
    }

    private SemanticPatch patch(OperationType operation, String assetType, String assetKey) {
        return new SemanticPatch(1, 1L, "hash",
                List.of(new Operation(operation, assetType, assetKey, "fingerprint", Map.of(), List.of())));
    }
}
