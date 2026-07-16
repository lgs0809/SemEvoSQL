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

import cn.lgs.semevosql.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.semevosql.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialRepository;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialSourceType;
import cn.lgs.semevosql.service.file.ByteArrayMultipartFile;
import cn.lgs.semevosql.service.file.FileStorageService;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-level document snapshot cloner shared by initialization and immutable Semantic Version
 * materialization. It deliberately has no dependency on ProjectDocumentService or evolution
 * orchestration so Semantic Version publication cannot form an application-service cycle.
 */
@Service
@RequiredArgsConstructor
public class ProjectDocumentCloningService {

    private final SemanticMaterialRepository materialRepository;

    private final ProjectEvidenceRepository evidenceRepository;

    private final BusinessQueryScenarioRepository scenarioRepository;

    private final FileStorageService fileStorageService;

    @Transactional
    public void cloneDocuments(Long projectId, Long sourceVersionId, Long targetVersionId) {
        List<String> copiedFilePaths = new ArrayList<>();
        try {
            for (SemanticMaterial source : materialRepository.findByVersionWithContent(sourceVersionId)) {
                String targetFilePath = copyFile(source, projectId, targetVersionId);
                if (targetFilePath != null) {
                    copiedFilePaths.add(targetFilePath);
                }
                SemanticMaterial clone = cloneMaterial(source, projectId, targetVersionId, targetFilePath);
                materialRepository.insert(clone);
                cloneAttempts(source.getId(), clone.getId(), projectId, targetVersionId);
            }
            registerRollbackCleanup(copiedFilePaths);
        }
        catch (RuntimeException ex) {
            copiedFilePaths.forEach(fileStorageService::deleteFile);
            throw ex;
        }
    }

    private void registerRollbackCleanup(List<String> copiedFilePaths) {
        if (copiedFilePaths.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<String> rollbackPaths = List.copyOf(copiedFilePaths);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    rollbackPaths.forEach(fileStorageService::deleteFile);
                }
            }
        });
    }

    private SemanticMaterial cloneMaterial(SemanticMaterial source, Long projectId, Long targetVersionId,
            String targetFilePath) {
        LocalDateTime now = LocalDateTime.now();
        return SemanticMaterial.builder()
            .projectId(projectId)
            .projectVersionId(targetVersionId)
            .documentType(source.getDocumentType())
            .materialCategory(source.getMaterialCategory())
            .lifecycle(source.getLifecycle())
            .materialType(source.getMaterialType())
            .sourceType(source.getSourceType() == SemanticMaterialSourceType.DATABASE_SCAN
                    ? SemanticMaterialSourceType.DATABASE_SCAN : SemanticMaterialSourceType.CLONE)
            .sourceMaterialId(source.getId())
            .sourceName(source.getSourceName())
            .originalFilename(source.getOriginalFilename())
            .mediaType(source.getMediaType())
            .filePath(targetFilePath)
            .fileSize(source.getFileSize())
            .sourceLocation(source.getSourceLocation())
            .datasourceId(source.getDatasourceId())
            .contentHash(source.getContentHash())
            .content(source.getContent())
            .status(source.getStatus())
            .parseSummary(source.getParseSummary())
            .errorMessage(source.getErrorMessage())
            .createTime(now)
            .updateTime(now)
            .build();
    }

    private void cloneAttempts(Long sourceMaterialId, Long targetMaterialId, Long projectId, Long targetVersionId) {
        List<SemanticMaterialAttempt> attempts = materialRepository.findAttempts(sourceMaterialId);
        if (attempts.isEmpty()) {
            SemanticMaterial source = materialRepository.findById(sourceMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + sourceMaterialId));
            LocalDateTime now = LocalDateTime.now();
            materialRepository.insertAttempt(SemanticMaterialAttempt.builder()
                .materialId(targetMaterialId)
                .attemptNo(1)
                .status(source.getStatus())
                .contentHash(source.getContentHash())
                .sourceLocation(source.getSourceLocation())
                .extractionModel("clone")
                .parseSummary(source.getParseSummary())
                .errorMessage(source.getErrorMessage())
                .startTime(now)
                .finishTime(now)
                .createTime(now)
                .build());
            return;
        }
        for (SemanticMaterialAttempt sourceAttempt : attempts) {
            SemanticMaterialAttempt targetAttempt = SemanticMaterialAttempt.builder()
                .materialId(targetMaterialId)
                .attemptNo(sourceAttempt.getAttemptNo())
                .status(sourceAttempt.getStatus())
                .contentHash(sourceAttempt.getContentHash())
                .sourceLocation(sourceAttempt.getSourceLocation())
                .extractionModel(sourceAttempt.getExtractionModel())
                .parseSummary(sourceAttempt.getParseSummary())
                .errorMessage(sourceAttempt.getErrorMessage())
                .startTime(sourceAttempt.getStartTime())
                .finishTime(sourceAttempt.getFinishTime())
                .createTime(sourceAttempt.getCreateTime())
                .build();
            materialRepository.insertAttempt(targetAttempt);
            materialRepository.cloneProvenance(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
                    projectId, targetVersionId);
            evidenceRepository.cloneAttemptEvidence(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
                    projectId, targetVersionId);
            scenarioRepository.cloneAttemptScenarios(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
                    projectId, targetVersionId);
        }
    }

    private String copyFile(SemanticMaterial source, Long projectId, Long targetVersionId) {
        if (source.getFilePath() == null || source.getFilePath().isBlank()) {
            return null;
        }
        try {
            Resource resource = fileStorageService.getFileResource(source.getFilePath());
            byte[] bytes = resource.getInputStream().readAllBytes();
            ByteArrayMultipartFile file = new ByteArrayMultipartFile(bytes,
                    source.getOriginalFilename() == null ? "document" : source.getOriginalFilename(),
                    source.getMediaType());
            return fileStorageService.storeFile(file, documentSubPath(projectId, targetVersionId));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to clone project document file", ex);
        }
    }

    private String documentSubPath(Long projectId, Long versionId) {
        return "semevosql/projects/" + projectId + "/versions/" + versionId + "/documents";
    }
}
