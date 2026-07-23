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

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeItemCommand;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.CreateCommand;
import cn.lgs.semevosql.evolution.domain.EvolutionRootCause;
import cn.lgs.semevosql.evolution.domain.SemanticVersionPolicy.Trigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Compatibility bridge while the legacy candidate/replay implementation is being retired.
 *
 * <p>New semantic mutations are governed by SemanticChangeSet. A legacy candidate may still be
 * retained temporarily as a patch/replay generator, but it is explicitly linked to the ChangeSet
 * and no longer represents the versioning workspace or activation authority.
 */
@Service
@RequiredArgsConstructor
public class LegacyEvolutionChangeSetBridge {

    private final SemanticChangeSetApplicationService changeSetService;

    private final EpisodeApplicationService episodeService;

    private final JdbcTemplate jdbc;

    private final VersionedJson versionedJson;

    @Transactional
    public BridgeResult linkCandidate(Long projectId, Long sourceSemanticVersionId, String candidateId,
            String candidateType, String assetType, String assetKey, String riskLevel, String patchJson,
            Map<String, Object> evidence, String episodeId, String principal) {
        if (projectId == null || sourceSemanticVersionId == null) {
            throw new IllegalArgumentException("projectId and sourceSemanticVersionId are required");
        }
        String candidate = required(candidateId, "candidateId");
        required(assetType, "assetType");
        required(assetKey, "assetKey");
        SemanticPatch patch = semanticPatch(patchJson);
        if (patch.operations().isEmpty()) {
            throw new IllegalArgumentException("Legacy SemanticPatch has no operation");
        }

        Map<String, Object> itemEvidence = new LinkedHashMap<>();
        if (evidence != null) {
            itemEvidence.putAll(evidence);
        }
        itemEvidence.put("legacyCandidateId", candidate);
        itemEvidence.put("candidateType", Objects.toString(candidateType, ""));

        String linked = jdbc.query("SELECT semantic_change_set_id FROM qw_semantic_evolution_candidate WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), candidate).stream().filter(StringUtils::hasText).findFirst().orElse(null);
        ChangeSet changeSet;
        boolean newlyLinked = !StringUtils.hasText(linked);
        if (newlyLinked) {
            Trigger trigger = StringUtils.hasText(episodeId) ? Trigger.EPISODE_LEARNING : Trigger.MANUAL_SEMANTIC_FIX;
            String originType = StringUtils.hasText(episodeId) ? "EPISODE" : "MANUAL";
            String originRef = StringUtils.hasText(episodeId) ? episodeId : candidate;
            String actor = StringUtils.hasText(principal) ? principal.trim() : "semevosql-system";
            changeSet = changeSetService.create(new CreateCommand(projectId, sourceSemanticVersionId, trigger,
                    true, originType, originRef, EvolutionRootCause.SEMANTIC_LAYER, defaultText(riskLevel, "MEDIUM"),
                    "legacy-candidate:" + candidate, actor));
            jdbc.update("""
                    UPDATE qw_semantic_evolution_candidate
                    SET semantic_change_set_id = ?, update_time = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, changeSet.changeSetId(), candidate);
        }
        else {
            changeSet = changeSetService.get(linked);
        }
        changeSet = changeSetService.raiseDraftRisk(changeSet.changeSetId(), defaultText(riskLevel, "MEDIUM"));

        List<ChangeItemCommand> items = patch.operations().stream()
            .map(operation -> new ChangeItemCommand(operation.assetType(), operation.assetKey(),
                    changeOperation(operation), null, null, operation, Map.copyOf(itemEvidence)))
            .toList();
        changeSetService.replaceDraftItems(changeSet.changeSetId(), items);

        if (newlyLinked && StringUtils.hasText(episodeId)) {
            Map<String, Object> signalEvidence = new LinkedHashMap<>(itemEvidence);
            signalEvidence.put("semanticChangeSetId", changeSet.changeSetId());
            episodeService.recordSignal(episodeId, null, "EXPLICIT_SEMANTIC_CORRECTION",
                    EvolutionRootCause.SEMANTIC_LAYER, 1.0d, Map.copyOf(signalEvidence));
        }
        return new BridgeResult(candidate, changeSet.changeSetId(), changeSet.status().name());
    }

    public boolean hasSemanticOperations(String patchJson) {
        var payload = versionedJson.payload(patchJson, JsonPayloadRegistry.SEMANTIC_PATCH);
        var operations = payload.path("operations");
        return operations.isArray() && operations.size() > 0;
    }

    private String changeOperation(Operation patchOperation) {
        String operation = patchOperation.operation().name();
        if (operation.startsWith("ADD_")) {
            return "ADD";
        }
        if (operation.startsWith("DELETE_")) {
            return "DELETE";
        }
        return "UPDATE";
    }

    private SemanticPatch semanticPatch(String value) {
        return versionedJson.read(value, JsonPayloadRegistry.SEMANTIC_PATCH, SemanticPatch.class);
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public record BridgeResult(String candidateId, String semanticChangeSetId, String changeSetStatus) {
    }
}
