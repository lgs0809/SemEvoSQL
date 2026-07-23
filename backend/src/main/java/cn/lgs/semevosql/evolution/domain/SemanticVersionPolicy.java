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

import java.util.Objects;
import java.util.Optional;

/**
 * Single domain policy for deciding whether an event is allowed to create a new Semantic Version.
 * Retrieval/configuration lifecycle changes deliberately stay outside Semantic Version numbering.
 */
public final class SemanticVersionPolicy {

    private SemanticVersionPolicy() {
    }

    public static Decision decide(Trigger trigger, boolean semanticDiffDetected) {
        Objects.requireNonNull(trigger, "Semantic Version trigger is required");
        return switch (trigger) {
            case INITIALIZATION -> Decision.version(SemanticVersionLevel.INITIAL, SemanticVersionCause.INITIALIZATION);
            case EPISODE_LEARNING -> semanticDiffDetected
                    ? Decision.version(SemanticVersionLevel.PATCH, SemanticVersionCause.EPISODE_LEARNING)
                    : Decision.noVersion("Episode produced no Semantic Diff");
            case MANUAL_SEMANTIC_FIX -> semanticDiffDetected
                    ? Decision.version(SemanticVersionLevel.PATCH, SemanticVersionCause.MANUAL_SEMANTIC_FIX)
                    : Decision.noVersion("Manual fix produced no Semantic Diff");
            case CORPUS_UPDATE -> semanticDiffDetected
                    ? Decision.version(SemanticVersionLevel.MINOR, SemanticVersionCause.CORPUS_SEMANTIC_DIFF)
                    : Decision.noVersion("Corpus Revision advances without Semantic Version when no Semantic Diff exists");
            case PROMOTE_BUSINESS_BASELINE -> Decision.version(SemanticVersionLevel.MAJOR,
                    SemanticVersionCause.BUSINESS_BASELINE_PROMOTION);
            case QUERY_CASE_CAPTURE -> Decision.noVersion("Query Case capture does not version the Semantic Layer");
            case CHAT_CONFIG_CHANGE -> Decision.noVersion("Chat configuration is operational state");
            case RERANK_CONFIG_CHANGE -> Decision.noVersion("Rerank configuration is retrieval state");
            case EMBEDDING_CONFIG_CHANGE -> Decision.noVersion("Embedding configuration rebuilds retrieval index only");
            case ROLLBACK -> Decision.noVersion("Rollback switches the Active Semantic Version pointer");
        };
    }

    public enum Trigger {
        INITIALIZATION,
        EPISODE_LEARNING,
        MANUAL_SEMANTIC_FIX,
        CORPUS_UPDATE,
        PROMOTE_BUSINESS_BASELINE,
        QUERY_CASE_CAPTURE,
        CHAT_CONFIG_CHANGE,
        RERANK_CONFIG_CHANGE,
        EMBEDDING_CONFIG_CHANGE,
        ROLLBACK
    }

    public record Decision(boolean createVersion, SemanticVersionLevel level, SemanticVersionCause cause, String reason) {

        public static Decision version(SemanticVersionLevel level, SemanticVersionCause cause) {
            return new Decision(true, Objects.requireNonNull(level), Objects.requireNonNull(cause), null);
        }

        public static Decision noVersion(String reason) {
            return new Decision(false, null, null, reason);
        }

        public Optional<SemanticVersionLevel> versionLevel() {
            return Optional.ofNullable(level);
        }
    }

}
