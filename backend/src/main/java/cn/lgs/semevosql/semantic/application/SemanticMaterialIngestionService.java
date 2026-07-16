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

import cn.lgs.semevosql.operations.UntrustedContentGuard;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.domain.MaterialCategory;
import cn.lgs.semevosql.semantic.domain.MaterialLifecycle;
import cn.lgs.semevosql.semantic.domain.ProjectDocumentType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.Disposition;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialRepository;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialSourceType;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialStatus;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional orchestration for Project Version documents and semantic material. */
@Service
@RequiredArgsConstructor
public class SemanticMaterialIngestionService {

	private static final int DETAIL_CONTENT_LIMIT = 1_000_000;

	private static final int ERROR_MESSAGE_LIMIT = 4000;

	private static final String CONFLICT_GAP_PREFIX = "semantic-conflict:";

	private final SemanticMaterialRepository materialRepository;

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogApplicationService catalogService;

	private final StructuredSemanticMaterialParser structuredParser;

	private final DdlSemanticMaterialParser ddlParser;

	private final HistoricalSqlSemanticMaterialParser historicalSqlParser;

	private final LlmSemanticMaterialParser llmParser;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final ProjectEvidenceScenarioService evidenceScenarioService;

	private final SemanticCatalogCoverageAnalyzer coverageAnalyzer;

	private final ScenarioResolutionService scenarioResolutionService;

	private final UntrustedContentGuard untrustedContentGuard;

	private final SourceCodeMaterialAnalyzer sourceCodeMaterialAnalyzer;

	private final SourceCallChainContextService sourceCallChainContextService;

	private final PlatformTransactionManager transactionManager;

	public IngestionResult ingest(Long projectId, Long projectVersionId, SemanticMaterialType materialType,
			String sourceName, Integer datasourceId, String content) {
		return ingest(projectId, projectVersionId, null, null, materialType, sourceName, datasourceId, content);
	}

	public IngestionResult ingest(Long projectId, Long projectVersionId, MaterialCategory materialCategory,
			MaterialLifecycle lifecycle, SemanticMaterialType materialType, String sourceName, Integer datasourceId,
			String content) {
		MaterialRegistration registration = new MaterialRegistration(defaultDocumentType(materialType),
				materialCategory, lifecycle, materialType, SemanticMaterialSourceType.INLINE, null, sourceName, null,
				"text/plain", null, content == null ? null : (long) content.getBytes(StandardCharsets.UTF_8).length,
				sourceName, datasourceId, content, defaultExtractionModel(materialType, content));
		return ingestDocument(projectId, projectVersionId, registration);
	}

	public ParsedCorpusMaterial parseForCorpus(Long projectId, Long projectVersionId, MaterialRegistration registration) {
		requireRequest(projectId, projectVersionId, registration);
		SemanticProjectVersion version = requireVersion(projectId, projectVersionId);
		if (version.getStatus() != ProjectVersionStatus.PUBLISHED) {
			throw new IllegalStateException("Corpus evolution must parse against a PUBLISHED Semantic Version");
		}
		String contentHash = sha256(registration.content());
		SemanticMaterial material = SemanticMaterial.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.documentType(registration.documentType())
			.materialCategory(resolveMaterialCategory(registration))
			.lifecycle(resolveLifecycle(registration))
			.materialType(registration.materialType())
			.sourceType(registration.sourceType())
			.sourceMaterialId(registration.sourceMaterialId())
			.sourceName(trim(registration.sourceName()))
			.originalFilename(trim(registration.originalFilename()))
			.mediaType(trim(registration.mediaType()))
			.filePath(trim(registration.filePath()))
			.fileSize(registration.fileSize())
			.sourceLocation(trim(registration.sourceLocation()))
			.datasourceId(registration.datasourceId())
			.contentHash(contentHash)
			.content(registration.content())
			.status(SemanticMaterialStatus.RECEIVED)
			.build();
		return new ParsedCorpusMaterial(contentHash, parse(material));
	}

	public IngestionResult ingestDocument(Long projectId, Long projectVersionId, MaterialRegistration registration) {
		requireRequest(projectId, projectVersionId, registration);
		requireMutableDraft(projectId, projectVersionId);
		String contentHash = sha256(registration.content());
		SemanticMaterial existing = materialRepository.findByHash(projectVersionId, contentHash).orElse(null);
		if (existing != null) {
			requireOwnership(existing, projectId, projectVersionId);
			return result(existing, 0, true);
		}

		ReceivedMaterial received;
		try {
			received = requiresNew()
				.execute(status -> createReceived(projectId, projectVersionId, registration, contentHash));
		}
		catch (DataIntegrityViolationException ex) {
			SemanticMaterial material = materialRepository.findByHash(projectVersionId, contentHash)
				.orElseThrow(() -> ex);
			requireOwnership(material, projectId, projectVersionId);
			return result(material, 0, true);
		}

		try {
			ProcessingOutcome outcome = required()
				.execute(status -> process(received.material().getId(), received.attempt().getId()));
			return result(outcome.material(), outcome.createdGapCount(), false, outcome.evidenceCount(),
					outcome.createdScenarioCount());
		}
		catch (RuntimeException ex) {
			SemanticMaterial failed = requiresNew()
				.execute(status -> markFailed(received.material().getId(), received.attempt().getId(), ex));
			return result(failed, 0, false);
		}
	}

	/**
	 * Persists and applies a deterministic semantic patch produced by an external
	 * analyzer such as a physical database metadata scan. Identical source content reuses
	 * the same material but creates a new immutable Attempt so the patch is re-evaluated
	 * against the current draft Catalog.
	 */
	public IngestionResult ingestGeneratedPatch(Long projectId, Long projectVersionId,
			MaterialRegistration registration, SemanticMaterialParseResult parsed) {
		requireRequest(projectId, projectVersionId, registration);
		if (parsed == null) {
			throw new IllegalArgumentException("Generated semantic parse result is required");
		}
		requireMutableDraft(projectId, projectVersionId);
		String contentHash = sha256(registration.content());
		SemanticMaterial existing = materialRepository.findByHash(projectVersionId, contentHash).orElse(null);
		ReceivedMaterial received;
		boolean duplicate;
		if (existing == null) {
			try {
				received = requiresNew()
					.execute(status -> createReceived(projectId, projectVersionId, registration, contentHash));
				duplicate = false;
			}
			catch (DataIntegrityViolationException ex) {
				SemanticMaterial concurrent = materialRepository.findByHash(projectVersionId, contentHash)
					.orElseThrow(() -> ex);
				requireOwnership(concurrent, projectId, projectVersionId);
				SemanticMaterialAttempt attempt = requiresNew()
					.execute(status -> startReparse(concurrent, registration.extractionModel()));
				received = new ReceivedMaterial(concurrent, attempt);
				duplicate = true;
			}
		}
		else {
			requireOwnership(existing, projectId, projectVersionId);
			SemanticMaterialAttempt attempt = requiresNew()
				.execute(status -> startReparse(existing, registration.extractionModel()));
			received = new ReceivedMaterial(existing, attempt);
			duplicate = true;
		}
		ReceivedMaterial processingTarget = received;
		try {
			ProcessingOutcome outcome = required().execute(status -> processParsed(processingTarget.material().getId(),
					processingTarget.attempt().getId(), parsed));
			return result(outcome.material(), outcome.createdGapCount(), duplicate, outcome.evidenceCount(),
					outcome.createdScenarioCount());
		}
		catch (RuntimeException ex) {
			SemanticMaterial failed = requiresNew().execute(
					status -> markFailed(processingTarget.material().getId(), processingTarget.attempt().getId(), ex));
			return result(failed, 0, duplicate);
		}
	}

	public IngestionResult reparse(Long projectId, Long projectVersionId, Long materialId, String extractionModel) {
		requireMutableDraft(projectId, projectVersionId);
		SemanticMaterial material = requireMaterial(projectId, projectVersionId, materialId);
		if (material.getSourceType() == SemanticMaterialSourceType.DATABASE_SCAN) {
			throw new IllegalStateException("Database scan evidence must be refreshed through the scan-database API");
		}
		SemanticMaterialAttempt attempt = requiresNew().execute(status -> startReparse(material,
				extractionModel == null || extractionModel.isBlank()
						? defaultExtractionModel(material.getMaterialType(), material.getContent())
						: extractionModel.trim()));
		try {
			ProcessingOutcome outcome = required().execute(status -> process(materialId, attempt.getId()));
			return result(outcome.material(), outcome.createdGapCount(), false, outcome.evidenceCount(),
					outcome.createdScenarioCount());
		}
		catch (RuntimeException ex) {
			SemanticMaterial failed = requiresNew().execute(status -> markFailed(materialId, attempt.getId(), ex));
			return result(failed, 0, false);
		}
	}

	public List<MaterialView> list(Long projectId, Long projectVersionId) {
		requireVersion(projectId, projectVersionId);
		return materialRepository.findByVersion(projectVersionId)
			.stream()
			.map(material -> view(material, false))
			.toList();
	}

	public MaterialView get(Long projectId, Long projectVersionId, Long materialId) {
		requireVersion(projectId, projectVersionId);
		return view(requireMaterial(projectId, projectVersionId, materialId), true);
	}

	public List<AttemptView> attempts(Long projectId, Long projectVersionId, Long materialId) {
		requireVersion(projectId, projectVersionId);
		requireMaterial(projectId, projectVersionId, materialId);
		return materialRepository.findAttempts(materialId).stream().map(this::attemptView).toList();
	}

	public List<ProvenanceView> provenance(Long projectId, Long projectVersionId, Long materialId) {
		requireVersion(projectId, projectVersionId);
		requireMaterial(projectId, projectVersionId, materialId);
		return materialRepository.findProvenanceByMaterial(materialId).stream().map(this::provenanceView).toList();
	}

	public DeletedMaterial delete(Long projectId, Long projectVersionId, Long materialId) {
		requireMutableDraft(projectId, projectVersionId);
		SemanticMaterial material = requireMaterial(projectId, projectVersionId, materialId);
		if (material.getSourceType() == SemanticMaterialSourceType.DATABASE_SCAN) {
			throw new IllegalStateException("Database scan evidence cannot be deleted; rescan the datasource instead");
		}
		materialRepository.delete(materialId);
		evidenceScenarioService.reconcileVersion(projectVersionId);
		reconcileConflictGaps(projectVersionId);
		return new DeletedMaterial(materialId, material.getFilePath());
	}

	private ReceivedMaterial createReceived(Long projectId, Long projectVersionId, MaterialRegistration registration,
			String contentHash) {
		requireMutableDraft(projectId, projectVersionId);
		LocalDateTime now = LocalDateTime.now();
		SemanticMaterial material = SemanticMaterial.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.documentType(registration.documentType())
			.materialCategory(resolveMaterialCategory(registration))
			.lifecycle(resolveLifecycle(registration))
			.materialType(registration.materialType())
			.sourceType(registration.sourceType())
			.sourceMaterialId(registration.sourceMaterialId())
			.sourceName(trim(registration.sourceName()))
			.originalFilename(trim(registration.originalFilename()))
			.mediaType(trim(registration.mediaType()))
			.filePath(trim(registration.filePath()))
			.fileSize(registration.fileSize())
			.sourceLocation(trim(registration.sourceLocation()))
			.datasourceId(registration.datasourceId())
			.contentHash(contentHash)
			.content(registration.content())
			.status(SemanticMaterialStatus.RECEIVED)
			.createTime(now)
			.updateTime(now)
			.build();
		materialRepository.insert(material);
		SemanticMaterialAttempt attempt = startAttempt(material, 1, registration.extractionModel());
		return new ReceivedMaterial(material, attempt);
	}

	private SemanticMaterialAttempt startReparse(SemanticMaterial material, String extractionModel) {
		requireMutableDraft(material.getProjectId(), material.getProjectVersionId());
		material.setStatus(SemanticMaterialStatus.RECEIVED);
		material.setParseSummary(null);
		material.setErrorMessage(null);
		material.setUpdateTime(LocalDateTime.now());
		materialRepository.update(material);
		return startAttempt(material, materialRepository.findNextAttemptNo(material.getId()), extractionModel);
	}

	private SemanticMaterialAttempt startAttempt(SemanticMaterial material, int attemptNo, String extractionModel) {
		LocalDateTime now = LocalDateTime.now();
		SemanticMaterialAttempt attempt = SemanticMaterialAttempt.builder()
			.materialId(material.getId())
			.attemptNo(attemptNo)
			.status(SemanticMaterialStatus.RECEIVED)
			.contentHash(material.getContentHash())
			.sourceLocation(material.getSourceLocation())
			.extractionModel(trim(extractionModel))
			.startTime(now)
			.createTime(now)
			.build();
		materialRepository.insertAttempt(attempt);
		return attempt;
	}

	private ProcessingOutcome process(Long materialId, Long attemptId) {
		SemanticMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + materialId));
		return processParsed(materialId, attemptId, parse(material));
	}

	private ProcessingOutcome processParsed(Long materialId, Long attemptId, SemanticMaterialParseResult parsed) {
		SemanticMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + materialId));
		SemanticMaterialAttempt attempt = materialRepository.findAttemptById(attemptId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic material attempt not found: " + attemptId));
		requireMutableDraft(material.getProjectId(), material.getProjectVersionId());
		boolean instructionLikeEvidence = untrustedContentGuard.containsInstructionLikeText(material.getContent());
		ProjectEvidenceScenarioService.CaptureResult evidenceCapture = evidenceScenarioService.capture(material,
				attempt, parsed);
		SourceCodeMaterialAnalyzer.Analysis sourceAnalysis = sourceCodeMaterialAnalyzer.analyze(material);
		int sourceEvidenceCount = evidenceScenarioService.captureObservations(material, attempt,
				sourceAnalysis.observations());

		SemanticCatalogSnapshot currentCatalog = catalogService.getCatalog(material.getProjectId(),
				material.getProjectVersionId());
		SemanticCatalogPatchAnalyzer.PatchAnalysis patchAnalysis = patchAnalyzer.analyze(currentCatalog,
				parsed.catalogPatch(), material, attempt);
		SemanticCatalogSnapshot catalogAfterMerge = currentCatalog;
		if (patchAnalysis.hasAcceptedAssets()) {
			catalogAfterMerge = catalogService.mergeDraftCatalog(material.getProjectId(),
					material.getProjectVersionId(), patchAnalysis.acceptedPatch());
		}
		for (SemanticAssetProvenance provenance : patchAnalysis.provenance()) {
			materialRepository.insertProvenance(provenance);
		}

		List<SemanticGap> gaps = new ArrayList<>();
		if (parsed.gaps() != null) {
			parsed.gaps().stream().filter(gap -> !evidenceOnlySignal(gap)).forEach(gaps::add);
		}
		gaps.addAll(patchAnalysis.conflicts());
		SemanticCatalogCoverageAnalyzer.CoverageAnalysis coverage = coverageAnalyzer.analyze(catalogAfterMerge);
		int createdGapCount = 0;
		for (SemanticGap gap : gaps) {
			boolean exists = gap.getGapKey() != null
					&& projectRepository.findGapByKey(material.getProjectVersionId(), gap.getGapKey()).isPresent();
			projectRepository.insertGap(gap);
			if (!exists) {
				createdGapCount++;
			}
		}
		// Structural coverage is diagnostics only. It must not create business-facing
		// Grill-Me gaps.
		reconcileCoverageGaps(material.getProjectVersionId(), List.of());

		SemanticMaterialStatus finalStatus = parsed.reviewRequired() || !gaps.isEmpty()
				? SemanticMaterialStatus.REVIEW_REQUIRED : SemanticMaterialStatus.APPLIED;
		String summary = (parsed.summary() == null ? "Semantic material processed" : parsed.summary()) + "; evidence="
				+ (evidenceCapture.evidenceCount() + sourceEvidenceCount) + "; scenariosCreated="
				+ evidenceCapture.createdScenarioCount() + "; assetsApplied=" + patchAnalysis.appliedCount()
				+ "; assetConflicts=" + patchAnalysis.conflictCount() + "; structuralCoverage="
				+ coverage.coveredModelCount() + "/" + coverage.totalModelCount() + "; structuralDiagnostics="
				+ coverage.gaps().size()
				+ (instructionLikeEvidence ? "; instruction-like text isolated as untrusted evidence" : "");
		material.setStatus(finalStatus);
		material.setParseSummary(summary);
		material.setErrorMessage(null);
		material.setUpdateTime(LocalDateTime.now());
		materialRepository.update(material);
		finishAttempt(attemptId, finalStatus, summary, null);
		evidenceScenarioService.reconcileVersion(material.getProjectVersionId());
		ScenarioResolutionService.ResolutionCoverage scenarioCoverage = scenarioResolutionService
			.refreshVersion(material.getProjectId(), material.getProjectVersionId());
		material.setParseSummary(summary + "; scenarios=" + scenarioCoverage.resolved() + " resolved/"
				+ scenarioCoverage.ambiguous() + " ambiguous/" + scenarioCoverage.unsupported() + " unsupported");
		material.setUpdateTime(LocalDateTime.now());
		materialRepository.update(material);
		reconcileConflictGaps(material.getProjectVersionId());
		return new ProcessingOutcome(material, createdGapCount, evidenceCapture.evidenceCount() + sourceEvidenceCount,
				evidenceCapture.createdScenarioCount());
	}

	private boolean evidenceOnlySignal(SemanticGap gap) {
		return gap != null && "HISTORICAL_SQL_RELATIONSHIP_CANDIDATE".equals(gap.getGapType());
	}

	private void reconcileCoverageGaps(Long projectVersionId, List<SemanticGap> activeGaps) {
		Set<String> activeKeys = activeGaps.stream()
			.map(SemanticGap::getGapKey)
			.filter(key -> key != null && !key.isBlank())
			.collect(Collectors.toSet());
		for (SemanticGap openGap : projectRepository.findOpenGapsByKeyPrefix(projectVersionId,
				coverageAnalyzer.gapPrefix())) {
			if (!activeKeys.contains(openGap.getGapKey())) {
				openGap.resolve("最新 Semantic Catalog 覆盖检查确认该问题已不存在。", "system");
				projectRepository.updateGap(openGap);
			}
		}
	}

	private void reconcileConflictGaps(Long projectVersionId) {
		Set<String> activeKeys = materialRepository.findActiveConflictGapKeys(projectVersionId);
		for (SemanticGap openGap : projectRepository.findOpenGapsByKeyPrefix(projectVersionId, CONFLICT_GAP_PREFIX)) {
			if (!activeKeys.contains(openGap.getGapKey())) {
				openGap.resolve("最新有效材料 Attempt 已不再提供该冲突证据。", "system");
				projectRepository.updateGap(openGap);
			}
		}
	}

	private SemanticMaterialParseResult parse(SemanticMaterial material) {
		try {
			return switch (material.getMaterialType()) {
				case JSON, YAML -> structuredParser.parse(material.getProjectId(), material.getProjectVersionId(),
						material.getContentHash(), material.getMaterialType(), material.getContent());
				case MARKDOWN -> structuredParser.hasEmbeddedCatalogBlock(material.getContent())
						? structuredParser.parse(material.getProjectId(), material.getProjectVersionId(),
								material.getContentHash(), material.getMaterialType(), material.getContent())
						: llmParser.parse(material.getProjectId(), material.getProjectVersionId(),
								material.getContentHash(), material.getDocumentType(), material.getMaterialCategory(),
								material.getDatasourceId(), material.getSourceName(), material.getSourceLocation(),
								llmContent(material),
								catalogService.getCatalog(material.getProjectId(), material.getProjectVersionId()));
				case DDL -> ddlParser.parse(material.getProjectId(), material.getProjectVersionId(),
						material.getContentHash(), material.getDatasourceId(), material.getContent());
				case HISTORICAL_SQL -> historicalSqlParser.parse(material.getProjectId(),
						material.getProjectVersionId(), material.getContentHash(), material.getContent(),
						catalogService.getCatalog(material.getProjectId(), material.getProjectVersionId()));
			};
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Failed to parse semantic material: " + ex.getMessage(), ex);
		}
	}

	private String llmContent(SemanticMaterial material) {
		SourceCodeMaterialAnalyzer.Analysis analysis = sourceCodeMaterialAnalyzer.analyze(material);
		String relevant = analysis.relevantContent();
		if (sourceCallChainContextService == null || material.getId() == null
				|| !sourceCodeMaterialAnalyzer.supported(material.getMaterialCategory())) {
			return relevant;
		}
		return relevant
				+ sourceCallChainContextService.render(material.getProjectVersionId(), material.getId(), analysis);
	}

	private SemanticMaterial markFailed(Long materialId, Long attemptId, RuntimeException failure) {
		SemanticMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + materialId));
		String error = truncate(rootMessage(failure), ERROR_MESSAGE_LIMIT);
		material.setStatus(SemanticMaterialStatus.FAILED);
		material.setErrorMessage(error);
		material.setUpdateTime(LocalDateTime.now());
		materialRepository.update(material);
		finishAttempt(attemptId, SemanticMaterialStatus.FAILED, null, error);
		return material;
	}

	private void finishAttempt(Long attemptId, SemanticMaterialStatus status, String summary, String error) {
		SemanticMaterialAttempt attempt = SemanticMaterialAttempt.builder()
			.id(attemptId)
			.status(status)
			.parseSummary(summary)
			.errorMessage(error)
			.finishTime(LocalDateTime.now())
			.build();
		materialRepository.updateAttempt(attempt);
	}

	private SemanticProjectVersion requireMutableDraft(Long projectId, Long projectVersionId) {
		SemanticProjectVersion version = requireVersion(projectId, projectVersionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Project documents can only be changed in a DRAFT version");
		}
		return version;
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long projectVersionId) {
		if (projectRepository.findProject(projectId).isEmpty()) {
			throw new IllegalArgumentException("Semantic project not found: " + projectId);
		}
		SemanticProjectVersion version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + projectVersionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	private SemanticMaterial requireMaterial(Long projectId, Long projectVersionId, Long materialId) {
		SemanticMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + materialId));
		requireOwnership(material, projectId, projectVersionId);
		return material;
	}

	private void requireRequest(Long projectId, Long projectVersionId, MaterialRegistration registration) {
		if (projectId == null || projectVersionId == null || registration == null || registration.documentType() == null
				|| registration.materialType() == null || registration.sourceType() == null) {
			throw new IllegalArgumentException(
					"projectId, projectVersionId, documentType, materialType and sourceType are required");
		}
		if (registration.content() == null || registration.content().isBlank()) {
			throw new IllegalArgumentException("Semantic material content cannot be blank");
		}
	}

	private void requireOwnership(SemanticMaterial material, Long projectId, Long projectVersionId) {
		if (!projectId.equals(material.getProjectId()) || !projectVersionId.equals(material.getProjectVersionId())) {
			throw new IllegalArgumentException("Semantic material does not belong to the specified project version");
		}
	}

	private IngestionResult result(SemanticMaterial material, int createdGapCount, boolean duplicate) {
		return result(material, createdGapCount, duplicate, 0, 0);
	}

	private IngestionResult result(SemanticMaterial material, int createdGapCount, boolean duplicate, int evidenceCount,
			int createdScenarioCount) {
		CatalogReadiness readiness = catalogService.assess(material.getProjectId(), material.getProjectVersionId());
		return new IngestionResult(view(material, true), material.getStatus(), material.getParseSummary(),
				createdGapCount, evidenceCount, createdScenarioCount, readiness, duplicate);
	}

	private MaterialView view(SemanticMaterial material, boolean includeContent) {
		String content = includeContent ? truncate(material.getContent(), DETAIL_CONTENT_LIMIT) : null;
		int contentLength = material.getContent() == null ? 0 : material.getContent().length();
		return new MaterialView(material.getId(), material.getProjectId(), material.getProjectVersionId(),
				material.getDocumentType(), material.getMaterialCategory(), material.getLifecycle(),
				material.getMaterialType(), material.getSourceType(), material.getSourceMaterialId(),
				material.getSourceName(), material.getOriginalFilename(), material.getMediaType(), null,
				material.getFileSize(), material.getSourceLocation(), material.getDatasourceId(),
				material.getContentHash(), content, contentLength,
				includeContent && contentLength > DETAIL_CONTENT_LIMIT, material.getStatus(),
				material.getParseSummary(), material.getErrorMessage(), material.getCreateTime(),
				material.getUpdateTime());
	}

	private AttemptView attemptView(SemanticMaterialAttempt attempt) {
		return new AttemptView(attempt.getId(), attempt.getAttemptNo(), attempt.getStatus(), attempt.getContentHash(),
				attempt.getSourceLocation(), attempt.getExtractionModel(), attempt.getParseSummary(),
				attempt.getErrorMessage(), attempt.getStartTime(), attempt.getFinishTime(), attempt.getCreateTime());
	}

	private ProvenanceView provenanceView(SemanticAssetProvenance provenance) {
		return new ProvenanceView(provenance.getId(), provenance.getAttemptId(), provenance.getAssetType(),
				provenance.getAssetKey(), provenance.getAssetFingerprint(), provenance.getDisposition(),
				provenance.getConflictGapKey(), provenance.getConfidence(), provenance.getSourceLocation(),
				provenance.getExtractionModel(), provenance.getEvidence(), provenance.getCreateTime());
	}

	private MaterialCategory resolveMaterialCategory(MaterialRegistration registration) {
		if (registration.materialCategory() != null) {
			return registration.materialCategory();
		}
		if (registration.sourceType() == SemanticMaterialSourceType.DATABASE_SCAN
				|| registration.materialType() == SemanticMaterialType.DDL) {
			return MaterialCategory.DATABASE_SCHEMA;
		}
		return switch (registration.documentType()) {
			case DATA_DICTIONARY, ENUM_SPEC -> MaterialCategory.DATA_DICTIONARY;
			case METRIC_SPEC -> MaterialCategory.METRIC_DEFINITION;
			case GLOSSARY -> MaterialCategory.BUSINESS_GLOSSARY;
			case REPORT_SPEC -> MaterialCategory.REPORT_OR_BI;
			case HISTORICAL_SQL -> MaterialCategory.SQL_QUERY;
			case SYSTEM_RESPONSIBILITY -> MaterialCategory.SYSTEM_DESIGN;
			case SYNC_POLICY -> MaterialCategory.BUSINESS_RULE;
			case REQUIREMENT -> MaterialCategory.PRODUCT_REQUIREMENT;
		};
	}

	private MaterialLifecycle resolveLifecycle(MaterialRegistration registration) {
		if (registration.lifecycle() != null) {
			return registration.lifecycle();
		}
		return registration.documentType() == ProjectDocumentType.HISTORICAL_SQL ? MaterialLifecycle.HISTORICAL
				: MaterialLifecycle.CURRENT;
	}

	private ProjectDocumentType defaultDocumentType(SemanticMaterialType materialType) {
		return switch (materialType) {
			case DDL -> ProjectDocumentType.DATA_DICTIONARY;
			case HISTORICAL_SQL -> ProjectDocumentType.HISTORICAL_SQL;
			case JSON, YAML, MARKDOWN -> ProjectDocumentType.REQUIREMENT;
		};
	}

	private String defaultExtractionModel(SemanticMaterialType materialType, String content) {
		return materialType == SemanticMaterialType.MARKDOWN && !structuredParser.hasEmbeddedCatalogBlock(content)
				? "llm-semantic-extractor" : "built-in-parser";
	}

	private TransactionTemplate required() {
		return new TransactionTemplate(transactionManager);
	}

	private TransactionTemplate requiresNew() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	private String sha256(String content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
	}

	private String truncate(String value, int limit) {
		return value == null || value.length() <= limit ? value : value.substring(0, limit);
	}

	private String trim(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private record ReceivedMaterial(SemanticMaterial material, SemanticMaterialAttempt attempt) {
	}

	private record ProcessingOutcome(SemanticMaterial material, int createdGapCount, int evidenceCount,
			int createdScenarioCount) {
	}

	public record MaterialRegistration(ProjectDocumentType documentType, MaterialCategory materialCategory,
			MaterialLifecycle lifecycle, SemanticMaterialType materialType, SemanticMaterialSourceType sourceType,
			Long sourceMaterialId, String sourceName, String originalFilename, String mediaType, String filePath,
			Long fileSize, String sourceLocation, Integer datasourceId, String content, String extractionModel) {

		public MaterialRegistration(ProjectDocumentType documentType, SemanticMaterialType materialType,
				SemanticMaterialSourceType sourceType, Long sourceMaterialId, String sourceName,
				String originalFilename, String mediaType, String filePath, Long fileSize, String sourceLocation,
				Integer datasourceId, String content, String extractionModel) {
			this(documentType, null, null, materialType, sourceType, sourceMaterialId, sourceName, originalFilename,
					mediaType, filePath, fileSize, sourceLocation, datasourceId, content, extractionModel);
		}
	}

	public record ParsedCorpusMaterial(String contentHash, SemanticMaterialParseResult parsed) {
	}

	public record DeletedMaterial(Long materialId, String filePath) {
	}

	public record IngestionResult(MaterialView material, SemanticMaterialStatus status, String parseSummary,
			int createdGapCount, int evidenceCount, int createdScenarioCount, CatalogReadiness catalogReadiness,
			boolean duplicate, CorpusEvolutionView corpusEvolution) {

		public IngestionResult(MaterialView material, SemanticMaterialStatus status, String parseSummary,
				int createdGapCount, int evidenceCount, int createdScenarioCount, CatalogReadiness catalogReadiness,
				boolean duplicate) {
			this(material, status, parseSummary, createdGapCount, evidenceCount, createdScenarioCount, catalogReadiness,
					duplicate, null);
		}

		public IngestionResult(MaterialView material, SemanticMaterialStatus status, String parseSummary,
				int createdGapCount, CatalogReadiness catalogReadiness, boolean duplicate) {
			this(material, status, parseSummary, createdGapCount, 0, 0, catalogReadiness, duplicate, null);
		}
	}

	public record CorpusEvolutionView(Long corpusRevisionId, Long corpusRevisionNo, String semanticChangeSetId,
			boolean semanticDiffDetected, String status, Long semanticVersionId, String semanticVersion,
			List<?> blockedChanges) {
	}

	public record MaterialView(Long id, Long projectId, Long projectVersionId, ProjectDocumentType documentType,
			MaterialCategory materialCategory, MaterialLifecycle lifecycle, SemanticMaterialType materialType,
			SemanticMaterialSourceType sourceType, Long sourceMaterialId, String sourceName, String originalFilename,
			String mediaType, String filePath, Long fileSize, String sourceLocation, Integer datasourceId,
			String contentHash, String content, int contentLength, boolean contentTruncated,
			SemanticMaterialStatus status, String parseSummary, String errorMessage, LocalDateTime createTime,
			LocalDateTime updateTime) {

		public MaterialView(Long id, Long projectId, Long projectVersionId, ProjectDocumentType documentType,
				SemanticMaterialType materialType, SemanticMaterialSourceType sourceType, Long sourceMaterialId,
				String sourceName, String originalFilename, String mediaType, String filePath, Long fileSize,
				String sourceLocation, Integer datasourceId, String contentHash, String content, int contentLength,
				boolean contentTruncated, SemanticMaterialStatus status, String parseSummary, String errorMessage,
				LocalDateTime createTime, LocalDateTime updateTime) {
			this(id, projectId, projectVersionId, documentType, null, null, materialType, sourceType, sourceMaterialId,
					sourceName, originalFilename, mediaType, filePath, fileSize, sourceLocation, datasourceId,
					contentHash, content, contentLength, contentTruncated, status, parseSummary, errorMessage,
					createTime, updateTime);
		}
	}

	public record AttemptView(Long id, Integer attemptNo, SemanticMaterialStatus status, String contentHash,
			String sourceLocation, String extractionModel, String parseSummary, String errorMessage,
			LocalDateTime startTime, LocalDateTime finishTime, LocalDateTime createTime) {
	}

	public record ProvenanceView(Long id, Long attemptId, AssetType assetType, String assetKey, String assetFingerprint,
			Disposition disposition, String conflictGapKey, BigDecimal confidence, String sourceLocation,
			String extractionModel, String evidence, LocalDateTime createTime) {
	}

}
