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

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.evolution.SemanticReplayService;
import cn.lgs.semevosql.evolution.SemanticReplayService.ChangeSetReplaySummary;
import cn.lgs.semevosql.evolution.application.CorpusRevisionApplicationService.CorpusRevision;
import cn.lgs.semevosql.evolution.application.CorpusRevisionApplicationService.RecordCommand;
import cn.lgs.semevosql.evolution.application.SemanticCatalogDiffService.BlockedChange;
import cn.lgs.semevosql.evolution.application.SemanticCatalogDiffService.DiffResult;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeItemCommand;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.TransitionMetadata;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator.ReleaseResult;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator.ReplayDecision;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator.ValidationDecision;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.CorpusEvolutionView;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.IngestionResult;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.MaterialRegistration;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.ParsedCorpusMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Published/Active corpus update path. Unlike initialization ingestion, it never mutates the Active
 * Semantic Catalog. Source changes first become CorpusRevision; only a validated/replayed semantic
 * diff can materialize and activate a MINOR Semantic Version.
 */
@Service
@RequiredArgsConstructor
public class CorpusEvolutionApplicationService {

    private final SemanticProjectRepository projectRepository;

    private final SemanticMaterialIngestionService materialIngestionService;

    private final SemanticCatalogApplicationService catalogService;

    private final SemanticCatalogDiffService diffService;

    private final CorpusRevisionApplicationService corpusRevisionService;

    private final SemanticChangeSetApplicationService changeSetService;

    private final SemanticEvolutionReleaseOrchestrator releaseOrchestrator;

    private final SemanticReplayService replayService;

    private final CanonicalJson canonicalJson = new CanonicalJson();

    public IngestionResult ingest(Long projectId, Long activeVersionId, MaterialRegistration registration,
            String actor) {
        requireActivePublished(projectId, activeVersionId);
        String principal = StringUtils.hasText(actor) ? actor.trim() : "corpus-evolution";
        ParsedCorpusMaterial parsed = materialIngestionService.parseForCorpus(projectId, activeVersionId, registration);
        SemanticCatalogSnapshot current = catalogService.getCatalog(projectId, activeVersionId);
        SemanticCatalogSnapshot incoming = parsed.parsed().catalogPatch() == null ? SemanticCatalogSnapshot.builder().build()
                : parsed.parsed().catalogPatch();
        DiffResult catalogDiff = diffService.diff(current, incoming);
        List<BlockedChange> blocked = new ArrayList<>(catalogDiff.blockedChanges());
        if (parsed.parsed().reviewRequired() || !parsed.parsed().gaps().isEmpty()) {
            blocked.add(new BlockedChange("CORPUS", sourceRef(registration), "PARSER_REVIEW_REQUIRED",
                    parsed.parsed().summary() == null ? "Corpus parser requires semantic review"
                            : parsed.parsed().summary()));
        }
        boolean semanticDiff = catalogDiff.semanticDiffDetected();
        String riskLevel = riskLevel(catalogDiff.operations());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentType", registration.documentType().name());
        metadata.put("materialType", registration.materialType().name());
        metadata.put("sourceName", Objects.toString(registration.sourceName(), ""));
        metadata.put("sourceLocation", Objects.toString(registration.sourceLocation(), ""));
        metadata.put("filePath", Objects.toString(registration.filePath(), ""));
        metadata.put("parseSummary", Objects.toString(parsed.parsed().summary(), ""));
        metadata.put("operationCount", catalogDiff.operations().size());
        metadata.put("blockedChangeCount", blocked.size());
        String revisionKey = "corpus:" + parsed.contentHash();
        boolean duplicate = corpusRevisionService.findByIdempotency(projectId, revisionKey).isPresent();
        CorpusRevision revision = corpusRevisionService.record(new RecordCommand(projectId,
                registration.sourceType().name(), sourceRef(registration), parsed.contentHash(), revisionKey,
                semanticDiff, riskLevel, Map.copyOf(metadata), principal));

        if (!revision.semanticDiffDetected()) {
            String state = duplicate ? "NO_SEMANTIC_CHANGE"
                    : blocked.isEmpty() ? "NO_SEMANTIC_CHANGE" : "REVIEW_REQUIRED_NO_DIFF";
            return result(activeVersionId, parsed, revision, null,
                    blocked.isEmpty() || duplicate ? SemanticMaterialStatus.APPLIED : SemanticMaterialStatus.REVIEW_REQUIRED,
                    state, null, duplicate ? List.of() : blocked, duplicate);
        }

        ChangeSet changeSet = changeSetService.get(revision.semanticChangeSetId());
        if (List.of(Status.INDEXING, Status.READY, Status.ACTIVATING, Status.ACTIVE).contains(changeSet.status())) {
            ReleaseResult resumed = releaseOrchestrator.resumeRelease(changeSet.changeSetId(), principal,
                    "corpus-resume:" + projectId + ":" + revision.revisionNo());
            return result(activeVersionId, parsed, revision, changeSet.changeSetId(), SemanticMaterialStatus.APPLIED,
                    resumed.status().name(), resumed, List.of(), true);
        }
        if (List.of(Status.REJECTED, Status.FAILED, Status.STALE).contains(changeSet.status())) {
            return result(activeVersionId, parsed, revision, changeSet.changeSetId(),
                    SemanticMaterialStatus.REVIEW_REQUIRED, changeSet.status().name(), null,
                    duplicate ? List.of() : blocked, duplicate);
        }
        Map<String, Object> evidence = Map.of("corpusRevisionId", revision.id(), "corpusRevisionNo",
                revision.revisionNo(), "contentHash", parsed.contentHash(), "sourceRef", sourceRef(registration));
        if (changeSet.status() == Status.DRAFT) {
            for (Operation operation : catalogDiff.operations()) {
                changeSetService.putItem(changeSet.changeSetId(), new ChangeItemCommand(operation.assetType(),
                        operation.assetKey(), operation.operation().name().startsWith("ADD_") ? "ADD" : "UPDATE",
                        operation.expectedCurrentFingerprint(), canonicalJson.hash(operation), operation, evidence));
            }
            if (!blocked.isEmpty()) {
                rejectBlocked(changeSet.changeSetId(), catalogDiff.operations(), blocked);
                return result(activeVersionId, parsed, revision, changeSet.changeSetId(),
                        SemanticMaterialStatus.REVIEW_REQUIRED, "REJECTED", null, blocked, duplicate);
            }
        }

        if (changeSet.status() == Status.DRAFT || changeSet.status() == Status.VALIDATING) {
            ValidationDecision validation = releaseOrchestrator.validateAndQueueReplay(changeSet.changeSetId());
            if (!validation.accepted()) {
                return result(activeVersionId, parsed, revision, changeSet.changeSetId(),
                        SemanticMaterialStatus.REVIEW_REQUIRED, validation.changeSet().status().name(), null,
                        duplicate ? List.of() : blocked, duplicate);
            }
            changeSet = validation.changeSet();
        }
        if (changeSet.status() != Status.REPLAYING) {
            throw new IllegalStateException("Corpus SemanticChangeSet cannot resume from status " + changeSet.status());
        }

        ChangeSetReplaySummary replay = replayService.replayChangeSet(changeSet.changeSetId());
        if (!replay.allPassed()) {
            changeSetService.transition(changeSet.changeSetId(), Status.REPLAYING, Status.REJECTED,
                    new TransitionMetadata(null, replay.replayExecutionId(), replay.summary(), replay.summary(), null));
            return result(activeVersionId, parsed, revision, changeSet.changeSetId(), SemanticMaterialStatus.REVIEW_REQUIRED,
                    "REJECTED", null, duplicate ? List.of() : blocked, duplicate);
        }

        ReleaseResult release = releaseOrchestrator.releaseAfterReplay(changeSet.changeSetId(),
                new ReplayDecision(true, replay.replayExecutionId(), replay.total(), replay.independentEvidenceCount(),
                        replay.broadReplay(), replay.summary()), principal,
                "corpus-release:" + projectId + ":" + revision.revisionNo());
        return result(activeVersionId, parsed, revision, changeSet.changeSetId(), SemanticMaterialStatus.APPLIED,
                release.status().name(), release, duplicate ? List.of() : blocked, duplicate);
    }

    private void rejectBlocked(String changeSetId, List<Operation> operations, List<BlockedChange> blocked) {
        String diffHash = canonicalJson.hash(Map.of("operations", operations, "blockedChanges", blocked));
        Map<String, Object> summary = Map.of("valid", false, "blockedChanges", List.copyOf(blocked),
                "operationCount", operations.size());
        changeSetService.transition(changeSetId, Status.DRAFT, Status.VALIDATING,
                new TransitionMetadata(diffHash, null, null, summary, null));
        changeSetService.transition(changeSetId, Status.VALIDATING, Status.REJECTED,
                new TransitionMetadata(null, null, null, summary, null));
    }

    private IngestionResult result(Long activeVersionId, ParsedCorpusMaterial parsed, CorpusRevision revision,
            String changeSetId, SemanticMaterialStatus status, String evolutionStatus, ReleaseResult release,
            List<BlockedChange> blocked, boolean duplicate) {
        Long semanticVersionId = release == null ? null : release.semanticVersionId();
        String semanticVersion = release == null ? null : release.semanticVersion();
        CatalogReadiness readiness = catalogService.assess(revision.projectId(), activeVersionId);
        CorpusEvolutionView evolution = new CorpusEvolutionView(revision.id(), revision.revisionNo(), changeSetId,
                revision.semanticDiffDetected(), evolutionStatus, semanticVersionId, semanticVersion, List.copyOf(blocked));
        return new IngestionResult(null, status, parsed.parsed().summary(), parsed.parsed().gaps().size(), 0,
                parsed.parsed().scenarios().size(), readiness, duplicate, evolution);
    }

    private void requireActivePublished(Long projectId, Long versionId) {
        SemanticProject project = projectRepository.findProject(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
        if (!Objects.equals(project.getActiveVersionId(), versionId)) {
            throw new IllegalStateException("Corpus evolution must target the current Active Semantic Version");
        }
        SemanticProjectVersion version = projectRepository.findVersion(versionId)
            .filter(value -> Objects.equals(value.getProjectId(), projectId))
            .orElseThrow(() -> new IllegalArgumentException("Semantic Version not found: " + versionId));
        if (version.getStatus() != ProjectVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Active Semantic Version must be PUBLISHED");
        }
    }

    private String riskLevel(List<Operation> operations) {
        boolean high = operations.stream().map(Operation::operation).anyMatch(type -> List.of(OperationType.ADD_METRIC,
                OperationType.UPDATE_METRIC, OperationType.ADD_RELATIONSHIP, OperationType.UPDATE_RELATIONSHIP,
                OperationType.ADD_GRAIN, OperationType.UPDATE_GRAIN, OperationType.ADD_RULE, OperationType.UPDATE_RULE)
            .contains(type));
        return high ? "HIGH" : "MEDIUM";
    }

    private String sourceRef(MaterialRegistration registration) {
        if (StringUtils.hasText(registration.sourceLocation())) {
            return registration.sourceLocation().trim();
        }
        if (StringUtils.hasText(registration.sourceName())) {
            return registration.sourceName().trim();
        }
        if (StringUtils.hasText(registration.originalFilename())) {
            return registration.originalFilename().trim();
        }
        return registration.sourceType().name();
    }
}
