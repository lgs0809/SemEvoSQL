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
package cn.lgs.semevosql.evolution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticVersionPolicyTest {

    @Test
    void incrementsSemanticVersionWithSemverResetRules() {
        SemanticVersionNumber current = SemanticVersionNumber.parse("2.7.9");

        assertThat(current.next(SemanticVersionLevel.PATCH)).isEqualTo(new SemanticVersionNumber(2, 7, 10));
        assertThat(current.next(SemanticVersionLevel.MINOR)).isEqualTo(new SemanticVersionNumber(2, 8, 0));
        assertThat(current.next(SemanticVersionLevel.MAJOR)).isEqualTo(new SemanticVersionNumber(3, 0, 0));
    }

    @Test
    void episodeAndManualSemanticDiffCreatePatch() {
        assertVersion(SemanticVersionPolicy.Trigger.EPISODE_LEARNING, true, SemanticVersionLevel.PATCH);
        assertVersion(SemanticVersionPolicy.Trigger.MANUAL_SEMANTIC_FIX, true, SemanticVersionLevel.PATCH);
    }

    @Test
    void corpusSemanticDiffCreatesMinorButNoDiffDoesNotVersion() {
        assertVersion(SemanticVersionPolicy.Trigger.CORPUS_UPDATE, true, SemanticVersionLevel.MINOR);
        assertNoVersion(SemanticVersionPolicy.Trigger.CORPUS_UPDATE, false);
    }

    @Test
    void onlyExplicitBusinessBaselinePromotionCreatesMajor() {
        assertVersion(SemanticVersionPolicy.Trigger.PROMOTE_BUSINESS_BASELINE, false, SemanticVersionLevel.MAJOR);
        assertNoVersion(SemanticVersionPolicy.Trigger.QUERY_CASE_CAPTURE, true);
        assertNoVersion(SemanticVersionPolicy.Trigger.CHAT_CONFIG_CHANGE, true);
        assertNoVersion(SemanticVersionPolicy.Trigger.RERANK_CONFIG_CHANGE, true);
        assertNoVersion(SemanticVersionPolicy.Trigger.EMBEDDING_CONFIG_CHANGE, true);
        assertNoVersion(SemanticVersionPolicy.Trigger.ROLLBACK, true);
    }

    private void assertVersion(SemanticVersionPolicy.Trigger trigger, boolean semanticDiff,
            SemanticVersionLevel expectedLevel) {
        SemanticVersionPolicy.Decision decision = SemanticVersionPolicy.decide(trigger, semanticDiff);
        assertThat(decision.createVersion()).isTrue();
        assertThat(decision.level()).isEqualTo(expectedLevel);
    }

    private void assertNoVersion(SemanticVersionPolicy.Trigger trigger, boolean semanticDiff) {
        SemanticVersionPolicy.Decision decision = SemanticVersionPolicy.decide(trigger, semanticDiff);
        assertThat(decision.createVersion()).isFalse();
        assertThat(decision.level()).isNull();
    }

}
