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
package cn.lgs.semevosql.onboarding;

import cn.lgs.semevosql.common.OptimisticLockingFailureException;
import cn.lgs.semevosql.onboarding.OnboardingConflict.ConflictStatus;
import cn.lgs.semevosql.onboarding.OnboardingCoverageItem.CoverageRequirement;
import cn.lgs.semevosql.onboarding.OnboardingCoverageItem.CoverageStatus;
import cn.lgs.semevosql.onboarding.OnboardingQuestion.QuestionStatus;
import cn.lgs.semevosql.onboarding.OnboardingRepository.OnboardingAnswer;
import cn.lgs.semevosql.onboarding.ProjectOnboardingSession.SessionStatus;
import cn.lgs.semevosql.project.domain.InitializationAnalysisStatus;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticGapResolutionHandler;
import cn.lgs.semevosql.project.domain.SemanticGapStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectOnboardingApplicationService {

	private static final Set<OnboardingCategory> ALWAYS_REQUIRED = EnumSet.of(OnboardingCategory.PROJECT_GOAL,
			OnboardingCategory.SUPPORTED_QUERY_SCOPE, OnboardingCategory.UNSUPPORTED_QUERY_SCOPE,
			OnboardingCategory.DATASOURCE_SCOPE, OnboardingCategory.QUERY_AMBIGUITY_POLICY,
			OnboardingCategory.RUNTIME_CLARIFICATION_POLICY, OnboardingCategory.GOLDEN_QUESTION,
			OnboardingCategory.ACCEPTANCE_CRITERIA);

	private static final Map<OnboardingCategory, List<OnboardingCategory>> DEPENDENCIES = dependencies();

	private static final Map<OnboardingCategory, Integer> PRIORITIES = priorities();

	private final OnboardingRepository repository;

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogApplicationService catalogService;

	private final ProjectVersionCatalogReadiness readiness;

	private final SemanticGapResolutionHandler gapResolutionHandler;

	private final OnboardingAnswerProjector answerProjector;

	private final JdbcTemplate jdbc;

	public ProjectOnboardingApplicationService(OnboardingRepository repository,
			SemanticProjectRepository projectRepository, SemanticCatalogApplicationService catalogService,
			ProjectVersionCatalogReadiness readiness, SemanticGapResolutionHandler gapResolutionHandler,
			OnboardingAnswerProjector answerProjector, JdbcTemplate jdbc) {
		this.repository = repository;
		this.projectRepository = projectRepository;
		this.catalogService = catalogService;
		this.readiness = readiness;
		this.gapResolutionHandler = gapResolutionHandler;
		this.answerProjector = answerProjector;
		this.jdbc = jdbc;
	}

	@Transactional
	public OnboardingView start(Long projectId, Long versionId, String idempotencyKey, String startedBy) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		if (startedBy == null || startedBy.isBlank()) {
			throw new IllegalArgumentException("startedBy is required");
		}
		repository.lockProjectVersion(versionId);
		ProjectOnboardingSession existingByKey = repository.findSessionByIdempotency(idempotencyKey).orElse(null);
		if (existingByKey != null) {
			assertSessionScope(existingByKey, projectId, versionId);
			return view(existingByKey);
		}
		ProjectOnboardingSession existing = repository.findSession(projectId, versionId).orElse(null);
		if (existing != null) {
			return view(existing);
		}
		SemanticProjectVersion version = requireDraftVersion(projectId, versionId);
		if (version.getAnalysisStatus() == InitializationAnalysisStatus.PENDING) {
			version.startAnalysis();
			projectRepository.updateVersion(version);
		}
		else if (version.getAnalysisStatus() != InitializationAnalysisStatus.RUNNING) {
			throw new IllegalStateException("Onboarding requires a PENDING or RUNNING initialization analysis");
		}
		ProjectOnboardingSession session = ProjectOnboardingSession.builder()
			.sessionId(UUID.randomUUID().toString())
			.projectId(projectId)
			.projectVersionId(versionId)
			.status(SessionStatus.ACTIVE)
			.summaryConfirmed(false)
			.idempotencyKey(idempotencyKey)
			.revision(0)
			.build();
		repository.insertSession(session);
		SemanticCatalogSnapshot catalog = catalogService.getCatalog(projectId, versionId);
		initializeCoverage(session, catalog);
		ensureNextQuestion(session, catalog);
		return view(repository.findSession(session.sessionId()).orElseThrow());
	}

	public OnboardingView get(Long projectId, Long versionId) {
		return view(requireSession(projectId, versionId));
	}

	@Transactional
	public OnboardingQuestion nextQuestion(Long projectId, Long versionId) {
		ProjectOnboardingSession session = requireSession(projectId, versionId);
		SemanticCatalogSnapshot catalog = catalogService.getCatalog(projectId, versionId);
		return ensureNextQuestion(session, catalog).orElse(null);
	}

	@Transactional
	public OnboardingView answer(Long projectId, Long versionId, String questionId, AnswerCommand command) {
		requireCommandMetadata(command.idempotencyKey(), command.answeredBy());
		ProjectOnboardingSession session = repository.lockSession(requireSession(projectId, versionId).sessionId());
		OnboardingQuestion scopedQuestion = repository.findQuestion(questionId)
			.orElseThrow(() -> new IllegalArgumentException("Onboarding question not found: " + questionId));
		assertQuestionScope(scopedQuestion, session);
		OnboardingAnswer duplicate = repository.findAnswerByIdempotency(questionId, command.idempotencyKey())
			.orElse(null);
		if (duplicate != null) {
			assertSameAnswer(duplicate, command);
			return view(session);
		}
		assertSessionMutable(session);
		OnboardingQuestion question = repository.lockQuestion(questionId);
		duplicate = repository.findAnswerByIdempotency(questionId, command.idempotencyKey()).orElse(null);
		if (duplicate != null) {
			assertSameAnswer(duplicate, command);
			return view(repository.findSession(session.sessionId()).orElseThrow());
		}
		assertQuestionScope(question, session);
		if (question.revision() != command.revision()) {
			throw new OptimisticLockingFailureException("OnboardingQuestion", questionId, question.revision());
		}
		if (question.status() != QuestionStatus.PENDING && question.status() != QuestionStatus.ANSWERED) {
			throw new IllegalStateException("Question is no longer answerable: " + question.status());
		}
		if (command.answer() == null || command.answer().isBlank()) {
			throw new IllegalArgumentException("answer is required");
		}
		if (command.answerType() == null || command.answerType().isBlank()) {
			throw new IllegalArgumentException("answerType is required");
		}
		boolean semanticGapQuestion = isSemanticGapQuestion(question);
		boolean conflictResolution = !semanticGapQuestion
				&& repository.hasOpenConflictByQuestion(session.sessionId(), questionId);
		boolean replacingConflictedAnswer = !semanticGapQuestion && !conflictResolution
				&& question.status() == QuestionStatus.ANSWERED
				&& repository.hasOpenConflictByCategory(session.sessionId(), question.category());
		if (replacingConflictedAnswer) {
			closeOpenConflictsForCategory(session, question.category());
		}
		if (conflictResolution) {
			repository.deactivateAnswersByCategory(session.sessionId(), question.category());
		}
		else {
			repository.deactivateAnswers(questionId);
		}
		repository.insertAnswer(new OnboardingAnswer(UUID.randomUUID().toString(), session.sessionId(), questionId,
				question.category(), command.answer(), command.answerType(), command.idempotencyKey(),
				command.answeredBy(), command.revision(), true, null));
		if (semanticGapQuestion) {
			resolveSemanticGapQuestion(question, command.answer(), command.answeredBy());
		}
		else {
			answerProjector.project(projectId, versionId, question.category(), command.answer(), question.evidence());
		}
		if (repository.updateQuestionStatus(questionId, command.revision(), QuestionStatus.ANSWERED) != 1) {
			throw questionConflict(questionId);
		}
		if (!semanticGapQuestion) {
			repository.updateCoverage(session.sessionId(), question.category(), CoverageStatus.ANSWERED, "USER_ANSWER",
					command.answer());
			invalidateDependents(session, question.category());
		}
		if (conflictResolution) {
			repository.resolveConflictByQuestion(session.sessionId(), questionId);
		}
		if (!semanticGapQuestion) {
			detectConflicts(session, question, command.answer());
		}
		refreshSessionState(session);
		ensureNextQuestion(repository.findSession(session.sessionId()).orElseThrow(),
				catalogService.getCatalog(projectId, versionId));
		return view(repository.findSession(session.sessionId()).orElseThrow());
	}

	@Transactional
	public OnboardingView skip(Long projectId, Long versionId, String questionId, QuestionCommand command) {
		return changeQuestionStatus(projectId, versionId, questionId, command, QuestionStatus.SKIPPED, false);
	}

	@Transactional
	public OnboardingView notApplicable(Long projectId, Long versionId, String questionId, QuestionCommand command) {
		return changeQuestionStatus(projectId, versionId, questionId, command, QuestionStatus.NOT_APPLICABLE, true);
	}

	public List<OnboardingConflict> conflicts(Long projectId, Long versionId) {
		return repository.conflicts(requireSession(projectId, versionId).sessionId());
	}

	public OnboardingSummary summary(Long projectId, Long versionId) {
		return buildSummary(requireSession(projectId, versionId));
	}

	@Transactional
	public OnboardingSummary confirm(Long projectId, Long versionId, ConfirmCommand command) {
		if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		if (command.confirmedBy() == null || command.confirmedBy().isBlank()) {
			throw new IllegalArgumentException("confirmedBy is required");
		}
		ProjectOnboardingSession session = requireSession(projectId, versionId);
		ProjectOnboardingSession locked = repository.lockSession(session.sessionId());
		if (locked.status() == SessionStatus.COMPLETED && locked.summaryConfirmed()) {
			if (Objects.equals(locked.confirmationIdempotencyKey(), command.idempotencyKey())) {
				if (!Objects.equals(locked.confirmedBy(), command.confirmedBy())
						|| !Objects.equals(locked.confirmationRevision(), command.revision())) {
					throw new IllegalArgumentException(
							"idempotencyKey is already bound to a different onboarding confirmation");
				}
				return buildSummary(locked);
			}
			throw new IllegalStateException("Onboarding was already confirmed by another command");
		}
		if (locked.revision() != command.revision()) {
			throw new OptimisticLockingFailureException("ProjectOnboardingSession", locked.sessionId(),
					locked.revision());
		}
		OnboardingSummary summary = buildSummary(locked);
		if (!summary.readyToConfirm()) {
			throw new IllegalStateException(
					"Onboarding cannot be confirmed: coverage, conflicts, gaps or catalog readiness remain");
		}
		if (repository.updateSession(locked.sessionId(), locked.revision(), SessionStatus.COMPLETED, true,
				command.confirmedBy(), command.idempotencyKey(), command.revision(), LocalDateTime.now()) != 1) {
			throw sessionConflict(locked.sessionId());
		}
		SemanticProjectVersion version = requireDraftVersion(projectId, versionId);
		if (version.getAnalysisStatus() == InitializationAnalysisStatus.RUNNING) {
			version.completeAnalysis();
			projectRepository.updateVersion(version);
		}
		return buildSummary(repository.findSession(locked.sessionId()).orElseThrow());
	}

	private OnboardingView changeQuestionStatus(Long projectId, Long versionId, String questionId,
			QuestionCommand command, QuestionStatus status, boolean completeCoverage) {
		requireCommandMetadata(command.idempotencyKey(), command.answeredBy());
		ProjectOnboardingSession session = repository.lockSession(requireSession(projectId, versionId).sessionId());
		OnboardingQuestion scopedQuestion = repository.findQuestion(questionId)
			.orElseThrow(() -> new IllegalArgumentException("Onboarding question not found: " + questionId));
		assertQuestionScope(scopedQuestion, session);
		OnboardingAnswer duplicate = repository.findAnswerByIdempotency(questionId, command.idempotencyKey())
			.orElse(null);
		if (duplicate != null) {
			assertSameQuestionCommand(duplicate, command, status);
			return view(session);
		}
		assertSessionMutable(session);
		OnboardingQuestion question = repository.lockQuestion(questionId);
		duplicate = repository.findAnswerByIdempotency(questionId, command.idempotencyKey()).orElse(null);
		if (duplicate != null) {
			assertSameQuestionCommand(duplicate, command, status);
			return view(repository.findSession(session.sessionId()).orElseThrow());
		}
		assertQuestionScope(question, session);
		if (question.revision() != command.revision()) {
			throw new OptimisticLockingFailureException("OnboardingQuestion", questionId, question.revision());
		}
		if (question.status() != QuestionStatus.PENDING) {
			throw new IllegalStateException("Only a pending question can be " + status.name().toLowerCase(Locale.ROOT));
		}
		if (completeCoverage) {
			OnboardingCoverageItem item = repository.coverage(session.sessionId())
				.stream()
				.filter(value -> value.category() == question.category())
				.findFirst()
				.orElseThrow();
			if (item.requirement() == CoverageRequirement.REQUIRED) {
				throw new IllegalStateException("Required onboarding coverage cannot be marked not applicable");
			}
		}
		repository.insertAnswer(new OnboardingAnswer(UUID.randomUUID().toString(), session.sessionId(), questionId,
				question.category(), Objects.toString(command.reason(), ""), status.name(), command.idempotencyKey(),
				command.answeredBy(), command.revision(), true, null));
		if (repository.updateQuestionStatus(questionId, command.revision(), status) != 1) {
			throw questionConflict(questionId);
		}
		if (completeCoverage) {
			repository.updateCoverage(session.sessionId(), question.category(), CoverageStatus.NOT_APPLICABLE,
					"USER_NOT_APPLICABLE", command.reason());
		}
		ensureNextQuestion(session, catalogService.getCatalog(projectId, versionId));
		return view(repository.findSession(session.sessionId()).orElseThrow());
	}

	private void initializeCoverage(ProjectOnboardingSession session, SemanticCatalogSnapshot catalog) {
		for (OnboardingCategory category : OnboardingCategory.values()) {
			Applicability applicability = notApplicable(
					"Onboarding questions are demand-driven by uploaded-material evidence and mined business scenarios");
			OnboardingCoverageItem item = OnboardingCoverageItem.builder()
				.id(UUID.randomUUID().toString())
				.sessionId(session.sessionId())
				.category(category)
				.requirement(applicability.requirement())
				.status(applicability.status())
				.satisfiedBy(applicability.satisfiedBy())
				.evidence(applicability.evidence())
				.revision(0)
				.build();
			repository.insertCoverage(item);
		}
	}

	private Applicability applicability(OnboardingCategory category, SemanticCatalogSnapshot catalog) {
		if (ALWAYS_REQUIRED.contains(category)) {
			if (category == OnboardingCategory.DATASOURCE_SCOPE && !catalog.enabledDatasourceIds().isEmpty()) {
				return answered("CATALOG", "datasourceIds=" + catalog.enabledDatasourceIds());
			}
			return required();
		}
		boolean hasModels = !catalog.getModels().isEmpty();
		boolean hasColumns = !catalog.getColumns().isEmpty();
		boolean hasTime = catalog.getColumns().stream().anyMatch(column -> column.getRole() == SemanticColumnRole.TIME);
		boolean hasMetrics = !catalog.getMetrics().isEmpty();
		boolean hasNumeric = catalog.getColumns().stream().anyMatch(OnboardingCatalogAdvisor::numericColumn);
		boolean hasRelationships = !catalog.getRelationships().isEmpty();
		boolean multiModel = catalog.getModels().size() > 1;
		boolean hasEnums = !catalog.getEnumValues().isEmpty()
				|| catalog.getColumns().stream().anyMatch(OnboardingCatalogAdvisor::enumCandidate);
		boolean hasRules = !catalog.getRules().isEmpty();
		return switch (category) {
			case MODEL_BUSINESS_NAME -> hasModels && catalog.getModels().stream().allMatch(OnboardingCatalogAdvisor::hasBusinessName)
					? answered("CATALOG", "Model business names already exist") : conditional(hasModels);
			case MODEL_TYPE -> conditional(hasModels);
			case MODEL_GRAIN, MODEL_UNIQUENESS -> !catalog.getGrains().isEmpty()
					? answered("CATALOG", "grain definitions=" + catalog.getGrains().size()) : conditional(hasModels);
			case DEFAULT_TIME_COLUMN -> catalog.getGrains().stream().anyMatch(grain -> text(grain.getTimeColumn()))
					? answered("CATALOG", "Default grain time column exists") : conditional(hasTime);
			case TIME_SEMANTICS, TIMEZONE -> conditional(hasTime);
			case METRIC_DEFINITION ->
				hasMetrics ? answered("CATALOG", "metrics=" + catalog.getMetrics().size()) : conditional(hasNumeric);
			case METRIC_AGGREGATION ->
				hasMetrics && catalog.getMetrics().stream().allMatch(metric -> text(metric.getAggregation()))
						? answered("CATALOG", "All metric aggregations are declared")
						: conditional(hasMetrics || hasNumeric);
			case METRIC_FILTER, METRIC_DISTINCT_RULE -> conditional(hasMetrics || hasNumeric);
			case METRIC_ADDITIVITY ->
				hasMetrics && catalog.getMetrics().stream().allMatch(metric -> text(metric.getAdditiveType()))
						? answered("CATALOG", "All metric additivity rules are declared")
						: conditional(hasMetrics || hasNumeric);
			case DIMENSION_DEFINITION -> !catalog.getDimensions().isEmpty()
					? answered("CATALOG", "dimensions=" + catalog.getDimensions().size()) : conditional(hasColumns);
			case ENUM_MEANING -> !catalog.getEnumValues().isEmpty()
					? answered("CATALOG", "enum values=" + catalog.getEnumValues().size()) : conditional(hasEnums);
			case RELATIONSHIP_JOIN, RELATIONSHIP_CARDINALITY ->
				hasRelationships ? answered("CATALOG", "relationships=" + catalog.getRelationships().size())
						: conditional(multiModel);
			case FAN_OUT_POLICY -> conditional(hasRelationships || multiModel);
			case BUSINESS_FILTER_RULE -> hasRules ? answered("CATALOG", "rules=" + catalog.getRules().size())
					: notApplicable("No declared business filter evidence");
			case LOGICAL_DELETE_RULE ->
				conditional(catalog.getColumns().stream().anyMatch(OnboardingCatalogAdvisor::logicalDeleteCandidate));
			case TEST_DATA_FILTER_RULE -> conditional(catalog.getColumns().stream().anyMatch(OnboardingCatalogAdvisor::testDataCandidate));
			case SEMANTIC_GAP -> notApplicable("Semantic gaps are projected dynamically from the current Catalog");
			default -> required();
		};
	}

	private Optional<OnboardingQuestion> ensureNextQuestion(ProjectOnboardingSession session,
			SemanticCatalogSnapshot catalog) {
		session = repository.lockSession(session.sessionId());
		ProjectOnboardingSession lockedSession = session;
		if (session.status() == SessionStatus.COMPLETED) {
			return Optional.empty();
		}
		List<OnboardingQuestion> questions = repository.questions(session.sessionId());
		reconcileSemanticGapQuestions(questions);
		Optional<OnboardingQuestion> semanticGapQuestion = ensureSemanticGapQuestion(session,
				repository.questions(session.sessionId()));
		if (semanticGapQuestion.isPresent()) {
			return semanticGapQuestion;
		}
		questions = repository.questions(session.sessionId());
		Optional<OnboardingQuestion> existing = questions.stream()
			.filter(question -> question.status() == QuestionStatus.PENDING)
			.min(Comparator.comparingInt(OnboardingQuestion::priority));
		if (existing.isPresent()) {
			return existing;
		}
		List<OnboardingCoverageItem> coverage = repository.coverage(session.sessionId());
		Map<OnboardingCategory, OnboardingCoverageItem> byCategory = coverage.stream()
			.collect(Collectors.toMap(OnboardingCoverageItem::category, item -> item));
		for (OnboardingCoverageItem item : coverage.stream()
			.sorted(Comparator.comparingInt(value -> PRIORITIES.getOrDefault(value.category(), 1000)))
			.toList()) {
			if (item.complete() || !dependenciesResolved(item.category(), byCategory)) {
				continue;
			}
			Optional<OnboardingQuestion> latest = repository.latestQuestion(session.sessionId(), item.category());
			if (latest.isPresent() && latest.get().status() != QuestionStatus.STALE
					&& latest.get().status() != QuestionStatus.SUPERSEDED) {
				continue;
			}
			OnboardingQuestion question = buildQuestion(session, item.category(), catalog, false);
			repository.insertQuestion(question);
			return Optional.of(question);
		}
		Optional<OnboardingCoverageItem> skipped = coverage.stream()
			.filter(item -> !item.complete())
			.filter(item -> repository.latestQuestion(lockedSession.sessionId(), item.category())
				.map(question -> question.status() == QuestionStatus.SKIPPED)
				.orElse(false))
			.min(Comparator.comparingInt(item -> PRIORITIES.getOrDefault(item.category(), 1000)));
		if (skipped.isPresent()) {
			OnboardingQuestion question = buildQuestion(session, skipped.get().category(), catalog, true);
			repository.insertQuestion(question);
			return Optional.of(question);
		}
		refreshSessionState(session);
		return Optional.empty();
	}

	private void reconcileSemanticGapQuestions(List<OnboardingQuestion> questions) {
		for (OnboardingQuestion question : questions) {
			if (!isSemanticGapQuestion(question) || question.status() != QuestionStatus.PENDING) {
				continue;
			}
			SemanticGap gap = projectRepository.findGap(semanticGapId(question)).orElse(null);
			if (gap == null || gap.getStatus() != SemanticGapStatus.OPEN
					|| !gapResolutionHandler.supports(gap.getGapType())) {
				repository.updateQuestionStatus(question.id(), question.revision(), QuestionStatus.NOT_APPLICABLE);
			}
		}
	}

	private Optional<OnboardingQuestion> ensureSemanticGapQuestion(ProjectOnboardingSession session,
			List<OnboardingQuestion> questions) {
		SemanticGap gap = projectRepository.findOpenGaps(session.projectId(), session.projectVersionId())
			.stream()
			.filter(candidate -> candidate.getStatus() == SemanticGapStatus.OPEN)
			.filter(candidate -> gapResolutionHandler.supports(candidate.getGapType()))
			.findFirst()
			.orElse(null);
		if (gap == null) {
			return Optional.empty();
		}
		for (OnboardingQuestion pending : questions) {
			if (pending.status() == QuestionStatus.PENDING && !isSemanticGapQuestion(pending)) {
				repository.updateQuestionStatus(pending.id(), pending.revision(), QuestionStatus.STALE);
			}
		}
		String questionId = semanticGapQuestionId(gap.getId());
		OnboardingQuestion existing = repository.findQuestion(questionId).orElse(null);
		if (existing != null) {
			if (existing.status() != QuestionStatus.PENDING) {
				if (repository.updateQuestionStatus(existing.id(), existing.revision(), QuestionStatus.PENDING) != 1) {
					throw questionConflict(existing.id());
				}
				existing = repository.findQuestion(questionId).orElseThrow();
			}
			OnboardingQuestion currentDefinition = buildSemanticGapQuestion(session, gap, questionId);
			if (questionDefinitionChanged(existing, currentDefinition)) {
				if (repository.updateQuestionDefinition(currentDefinition, existing.revision()) != 1) {
					throw questionConflict(existing.id());
				}
			}
			return repository.findQuestion(questionId);
		}
		OnboardingQuestion question = buildSemanticGapQuestion(session, gap, questionId);
		repository.insertQuestion(question);
		return Optional.of(question);
	}

	private boolean questionDefinitionChanged(OnboardingQuestion existing, OnboardingQuestion current) {
		return !Objects.equals(existing.question(), current.question())
				|| !Objects.equals(existing.recommendedAnswer(), current.recommendedAnswer())
				|| !Objects.equals(existing.recommendationReason(), current.recommendationReason())
				|| !jsonEquivalent(existing.evidence(), current.evidence())
				|| !jsonEquivalent(existing.answerSchema(), current.answerSchema())
				|| existing.blocking() != current.blocking() || existing.priority() != current.priority()
				|| !Objects.equals(existing.dependsOn(), current.dependsOn());
	}

	private boolean jsonEquivalent(String left, String right) {
		if (Objects.equals(left, right)) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		try {
			return Objects.equals(JsonUtil.getObjectMapper().readTree(left), JsonUtil.getObjectMapper().readTree(right));
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private OnboardingQuestion buildSemanticGapQuestion(ProjectOnboardingSession session, SemanticGap gap,
			String questionId) {
		boolean conflict = "SEMANTIC_ASSET_CONFLICT".equals(gap.getGapType());
		String answerSchema = semanticGapAnswerSchema(gap);
		return OnboardingQuestion.builder()
			.id(questionId)
			.sessionId(session.sessionId())
			.projectId(session.projectId())
			.projectVersionId(session.projectVersionId())
			.category(OnboardingCategory.SEMANTIC_GAP)
			.question(gap.getQuestion())
			.recommendedAnswer(conflict ? "{\"choice\":\"CURRENT\"}" : null)
			.recommendationReason(gap.getRecommendation())
			.evidence(gap.getEvidence())
			.answerSchema(answerSchema)
			.blocking(true)
			.priority(gap.getPriority())
			.dependsOn(List.of())
			.status(QuestionStatus.PENDING)
			.revision(0)
			.build();
	}

	private String semanticGapAnswerSchema(SemanticGap gap) {
		String gapType = gap.getGapType();
		if (gapType != null && (gapType.startsWith("AMBIGUOUS_") || "MISSING_REQUIRED_SEMANTIC".equals(gapType)
				|| "UNSUPPORTED_QUERY_CAPABILITY".equals(gapType))) {
			return scenarioGapAnswerSchema(gap);
		}
		return switch (gapType) {
			case "SEMANTIC_ASSET_CONFLICT" ->
				"{\"type\":\"object\",\"required\":[\"choice\"],\"properties\":{\"choice\":{\"type\":\"string\",\"enum\":[\"CURRENT\",\"INCOMING\"]}}}";
			case "MISSING_LOGICAL_BINDING" ->
				requiredObjectSchema("logicalEntityCode", "logicalAttributeCode", "datasourceId", "modelCode");
			case "MISSING_AUTHORITY_RULE" ->
				requiredObjectSchema("logicalAssetType", "logicalAssetCode", "datasourceId", "sourceRole");
			case "MISSING_FRESHNESS_POLICY" -> requiredObjectSchema("datasourceId", "businessDateField", "timeZone",
					"freshnessType", "latencyMinutes");
			case "MISSING_CROSS_SOURCE_RELATIONSHIP" -> requiredObjectSchema("relationshipCode", "leftDatasourceId",
					"leftModelCode", "leftKey", "rightDatasourceId", "rightModelCode", "rightKey", "cardinality",
					"nullPolicy", "uniquenessRule", "confidence");
			case "MISSING_MERGE_POLICY" -> requiredObjectSchema("policyCode", "mergeType", "nullPolicy",
					"duplicatePolicy", "maxRows", "partialFailurePolicy");
			case "CROSS_ASSET_METRIC_DEFINITION_CONFLICT" -> requiredObjectSchema("authoritativeMetricCode");
			case "FANOUT_METRIC_RISK" -> objectSchema("keyColumns");
			default -> objectSchema("keyColumns");
		};
	}

	private String scenarioGapAnswerSchema(SemanticGap gap) {
		try {
			JsonNode evidence = JsonUtil.getObjectMapper().readTree(gap.getEvidence());
			List<String> options = new ArrayList<>();
			for (JsonNode candidate : evidence.path("candidates")) {
				String label = candidate.path("optionLabel").asText("").trim();
				if (!label.isBlank() && !options.contains(label)) {
					options.add(label);
				}
			}
			boolean hasCandidateOptions = !options.isEmpty();
			options.add("其他");
			if ("MISSING_REQUIRED_SEMANTIC".equals(gap.getGapType()) && !hasCandidateOptions) {
				String requirementType = evidence.path("requirementType").asText();
				Map<String, Object> definition = null;
				if ("FILTER".equals(requirementType)) {
					definition = new LinkedHashMap<>();
					definition.put("type", "object");
					definition.put("required",
							List.of("type", "ruleCode", "modelCode", "columnName", "valueCodes"));
					definition.put("properties",
							Map.of("type", Map.of("type", "string", "enum", List.of("ENUM_SET_FILTER")), "ruleCode",
									Map.of("type", "string"), "businessName", Map.of("type", "string"), "modelCode",
									Map.of("type", "string"), "columnName", Map.of("type", "string"), "valueCodes",
									Map.of("type", "array", "minItems", 1, "items", Map.of("type", "string"))));
				}
				else if ("MEASURE".equals(requirementType)) {
					definition = new LinkedHashMap<>();
					definition.put("type", "object");
					definition.put("required",
							List.of("type", "metricCode", "businessName", "modelCode", "expression", "aggregation"));
					definition.put("properties",
							Map.of("type", Map.of("type", "string", "enum", List.of("DERIVED_METRIC")), "metricCode",
									Map.of("type", "string"), "businessName", Map.of("type", "string"), "modelCode",
									Map.of("type", "string"), "expression", Map.of("type", "string"), "aggregation",
									Map.of("type", "string", "enum", List.of("SUM", "AVG", "MIN", "MAX", "COUNT", "COUNT_DISTINCT")), "timeColumn",
									Map.of("type", "string"), "unit", Map.of("type", "string")));
				}
				else if ("DIMENSION".equals(requirementType) || "TIME".equals(requirementType)) {
					String assetType = "TIME".equals(requirementType) ? "TIME_COLUMN" : "DIMENSION";
					definition = new LinkedHashMap<>();
					definition.put("type", "object");
					definition.put("required", List.of("type", "assetType", "assetKey"));
					definition.put("properties",
							Map.of("type", Map.of("type", "string", "enum", List.of("EXISTING_ASSET")), "assetType",
									Map.of("type", "string", "enum", List.of(assetType)), "assetKey", Map.of("type", "string")));
				}
				else if ("RELATIONSHIP".equals(requirementType)) {
					definition = new LinkedHashMap<>();
					definition.put("type", "object");
					definition.put("required", List.of("type", "relationshipCode", "sourceModelCode", "sourceColumn",
							"targetModelCode", "targetColumn", "cardinality", "joinType"));
					definition.put("properties",
							Map.of("type", Map.of("type", "string", "enum", List.of("RELATIONSHIP")), "relationshipCode",
									Map.of("type", "string"), "sourceModelCode", Map.of("type", "string"), "sourceColumn",
									Map.of("type", "string"), "targetModelCode", Map.of("type", "string"), "targetColumn",
									Map.of("type", "string"), "cardinality",
									Map.of("type", "string", "enum", List.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY")),
									"joinType", Map.of("type", "string", "enum", List.of("INNER", "LEFT", "RIGHT", "FULL"))));
				}
				if (definition != null) {
					return json(Map.of("type", "object", "required", List.of("choice", "other", "definition"),
							"properties", Map.of("choice", Map.of("type", "string", "enum", List.of("其他")), "other",
									Map.of("type", "string"), "definition", definition)));
				}
			}
			return json(Map.of("type", "object", "required", List.of("choice"), "properties",
					Map.of("choice", Map.of("type", "string", "enum", options), "other", Map.of("type", "string"))));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Scenario semantic gap evidence is invalid", ex);
		}
	}

	private String requiredObjectSchema(String... fields) {
		String required = Arrays.stream(fields).map(field -> "\"" + field + "\"").collect(Collectors.joining(","));
		String properties = Arrays.stream(fields)
			.collect(Collectors.toMap(field -> field,
					field -> Map.of("type",
							field.endsWith("Id") || "latencyMinutes".equals(field) || "confidence".equals(field)
									|| "maxRows".equals(field) ? "integer" : "string"),
					(left, right) -> left, LinkedHashMap::new))
			.entrySet()
			.stream()
			.map(entry -> "\"" + entry.getKey() + "\":{\"type\":\"" + entry.getValue().get("type") + "\"}")
			.collect(Collectors.joining(","));
		return "{\"type\":\"object\",\"required\":[" + required + "],\"properties\":{" + properties + "}}";
	}

	private boolean isSemanticGapQuestion(OnboardingQuestion question) {
		return question != null && question.category() == OnboardingCategory.SEMANTIC_GAP && question.id() != null
				&& question.id().startsWith("semantic-gap-");
	}

	private String semanticGapQuestionId(Long gapId) {
		if (gapId == null) {
			throw new IllegalArgumentException("Persisted semantic gap id is required for Grill-Me projection");
		}
		return "semantic-gap-" + gapId;
	}

	private Long semanticGapId(OnboardingQuestion question) {
		try {
			return Long.valueOf(question.id().substring("semantic-gap-".length()));
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("Invalid semantic gap question id: " + question.id(), ex);
		}
	}

	private void resolveSemanticGapQuestion(OnboardingQuestion question, String answer, String answeredBy) {
		SemanticGap gap = projectRepository.findGap(semanticGapId(question))
			.orElseThrow(() -> new IllegalArgumentException("Semantic gap not found for question: " + question.id()));
		if (gap.getStatus() != SemanticGapStatus.OPEN) {
			throw new IllegalStateException("Semantic gap is no longer open: " + gap.getId());
		}
		if (!gapResolutionHandler.supports(gap.getGapType())) {
			throw new IllegalArgumentException("Semantic gap is not safely resolvable: " + gap.getGapType());
		}
		gapResolutionHandler.applyResolution(gap, answer);
		gap.resolve(answer, answeredBy);
		projectRepository.updateGap(gap);
	}

	private OnboardingQuestion buildQuestion(ProjectOnboardingSession session, OnboardingCategory category,
			SemanticCatalogSnapshot catalog, boolean revisit) {
		QuestionDefinition definition = definition(category, catalog);
		return OnboardingQuestion.builder()
			.id(UUID.randomUUID().toString())
			.sessionId(session.sessionId())
			.projectId(session.projectId())
			.projectVersionId(session.projectVersionId())
			.category(category)
			.question((revisit ? "此前跳过的问题仍会阻止完成。" : "") + definition.question())
			.recommendedAnswer(definition.recommendedAnswer())
			.recommendationReason(definition.reason())
			.evidence(definition.evidence())
			.answerSchema(definition.answerSchema())
			.blocking(true)
			.priority(PRIORITIES.getOrDefault(category, 100))
			.dependsOn(DEPENDENCIES.getOrDefault(category, List.of()))
			.status(QuestionStatus.PENDING)
			.revision(0)
			.build();
	}

	private QuestionDefinition definition(OnboardingCategory category, SemanticCatalogSnapshot catalog) {
		String modelEvidence = catalog.getModels()
			.stream()
			.map(model -> model.getModelCode() + "=" + model.getPhysicalTable())
			.collect(Collectors.joining(", "));
		String columnEvidence = catalog.getColumns()
			.stream()
			.limit(30)
			.map(column -> column.getModelCode() + "." + column.getColumnName() + ":" + column.getDataType())
			.collect(Collectors.joining(", "));
		String firstModel = catalog.getModels()
			.stream()
			.findFirst()
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.orElse(null);
		return switch (category) {
			case PROJECT_GOAL -> q("这个 NL2SQL 项目要帮助用户完成什么业务决策？", null, "项目描述不能替代业务目标确认。", "project/version metadata",
					objectSchema("goal"));
			case SUPPORTED_QUERY_SCOPE ->
				q("哪些查询属于本项目明确支持的范围？", null, "明确正向边界可约束规划和验收。", modelEvidence, objectSchema("supportedQueries"));
			case UNSUPPORTED_QUERY_SCOPE ->
				q("哪些查询必须明确拒绝或转交其他系统？", null, "负向边界用于防止模型越界猜测。", modelEvidence, objectSchema("unsupportedQueries"));
			case DATASOURCE_SCOPE -> q("本项目允许查询哪些数据源和表？",
					json(Map.of("datasourceIds", catalog.enabledDatasourceIds(), "tables",
							catalog.enabledPhysicalTables())),
					"推荐范围来自当前 Catalog 已绑定的数据源和表。", modelEvidence, objectSchema("datasourceIds", "tables"));
			case MODEL_BUSINESS_NAME -> q("这些物理表对应的业务对象名称分别是什么？", OnboardingCatalogAdvisor.modelRecommendations(catalog),
					"表名和注释可提供候选名称，但仍需业务确认。", modelEvidence, objectSchema("modelCode", "businessName"));
			case MODEL_TYPE -> q("该模型属于事实、维度、事件、快照还是桥接模型？", json(Map.of("modelCode", firstModel, "modelType", "FACT")),
					"模型类型决定指标、Join 和可加性约束。", modelEvidence, objectSchema("modelCode", "modelType"));
			case MODEL_GRAIN -> q("该模型一行数据准确代表什么？", OnboardingCatalogAdvisor.grainRecommendation(catalog), "主键、唯一字段和字段命名仅能形成粒度建议。",
					columnEvidence, objectSchema("modelCode", "grainCode", "keyColumns", "description"));
			case MODEL_UNIQUENESS -> q("如何验证该粒度在业务上唯一？", null, "唯一约束必须与业务实体粒度一致。", columnEvidence,
					objectSchema("modelCode", "keyColumns", "uniquenessRule"));
			case DEFAULT_TIME_COLUMN -> q("默认业务时间应使用哪个字段？", OnboardingCatalogAdvisor.timeRecommendation(catalog), "时间字段名称可推荐，但不能自动决定业务口径。",
					columnEvidence, objectSchema("modelCode", "timeColumn"));
			case TIME_SEMANTICS -> q("默认时间字段表示创建、支付、完成还是其他业务事件？", null, "同一实体可能存在多个合理时间事件。", columnEvidence,
					objectSchema("modelCode", "timeColumn", "semantics"));
			case TIMEZONE -> q("时间范围计算采用哪个时区？", "{\"timezone\":\"Asia/Shanghai\"}", "推荐值仅是部署默认值，需要业务确认。",
					"application default timezone", objectSchema("timezone"));
			case METRIC_DEFINITION -> q("需要支持的核心指标如何定义？", OnboardingCatalogAdvisor.metricRecommendation(catalog), "数值字段和历史 Catalog 可形成候选指标。",
					columnEvidence, objectSchema("modelCode", "metricCode", "businessName", "expression"));
			case METRIC_AGGREGATION -> q("该指标应使用 SUM、COUNT、COUNT DISTINCT、AVG 还是其他聚合？", null, "聚合方式必须与粒度共同确认。",
					columnEvidence, objectSchema("metricCode", "aggregation"));
			case METRIC_FILTER -> q("计算该指标时必须应用哪些业务过滤条件？", null, "过滤条件不能由系统凭空生成。", OnboardingCatalogAdvisor.rulesEvidence(catalog),
					objectSchema("metricCode", "filterExpression"));
			case METRIC_DISTINCT_RULE -> q("该指标何时需要去重，按哪些字段去重？", null, "重复事件或一对多 Join 会改变 COUNT 口径。",
					OnboardingCatalogAdvisor.grainEvidence(catalog), objectSchema("metricCode", "distinct", "distinctColumns"));
			case METRIC_ADDITIVITY -> q("该指标在时间和维度上是否可加？", null, "库存、余额和比率通常不是完全可加指标。", OnboardingCatalogAdvisor.metricEvidence(catalog),
					objectSchema("metricCode", "additiveType"));
			case DIMENSION_DEFINITION ->
				q("哪些字段应作为业务维度，它们的业务名称是什么？", OnboardingCatalogAdvisor.dimensionRecommendation(catalog), "低基数字段和已有注释可形成候选维度。", columnEvidence,
						objectSchema("modelCode", "dimensionCode", "businessName", "columnName"));
			case ENUM_MEANING -> q("枚举值分别代表什么业务含义？", OnboardingCatalogAdvisor.enumRecommendation(catalog), "状态字段值不能仅按字面自动解释。", columnEvidence,
					objectSchema("modelCode", "columnName", "valueCode", "businessName"));
			case RELATIONSHIP_JOIN -> q("模型之间应使用哪些字段和 Join 类型关联？", OnboardingCatalogAdvisor.relationshipRecommendation(catalog),
					"外键和历史 SQL 只能提供候选 Join。", modelEvidence, objectSchema("relationshipCode", "sourceModelCode",
							"targetModelCode", "joinType", "joinCondition"));
			case RELATIONSHIP_CARDINALITY -> q("该关系的业务基数是一对一、一对多、多对一还是多对多？", null, "基数决定 Join 后是否可能放大行数。",
					OnboardingCatalogAdvisor.relationshipEvidence(catalog), objectSchema("relationshipCode", "cardinality"));
			case FAN_OUT_POLICY -> q("发生一对多或多对多 Join 时如何防止指标被重复放大？", null, "必须明确预聚合、去重或拒绝策略。",
					OnboardingCatalogAdvisor.relationshipEvidence(catalog), objectSchema("relationshipCode", "policy"));
			case BUSINESS_FILTER_RULE -> q("哪些业务过滤规则必须自动应用？", null, "只投影明确声明的规则。", OnboardingCatalogAdvisor.rulesEvidence(catalog),
					objectSchema("modelCode", "ruleCode", "businessName", "expression"));
			case LOGICAL_DELETE_RULE -> q("逻辑删除记录应通过哪个字段和值过滤？", OnboardingCatalogAdvisor.logicalDeleteRecommendation(catalog),
					"字段名可形成建议，不能替代业务确认。", columnEvidence, objectSchema("modelCode", "ruleCode", "expression"));
			case TEST_DATA_FILTER_RULE -> q("测试或演示数据应如何识别并排除？", OnboardingCatalogAdvisor.testDataRecommendation(catalog), "仅在发现测试数据候选字段时询问。",
					columnEvidence, objectSchema("modelCode", "ruleCode", "expression"));
			case QUERY_AMBIGUITY_POLICY -> q("当多个语义资产同等匹配时，系统应如何处理？", "{\"policy\":\"ASK_USER\"}", "默认推荐显式澄清，而不是随机选择。",
					OnboardingCatalogAdvisor.metricEvidence(catalog), objectSchema("policy"));
			case RUNTIME_CLARIFICATION_POLICY -> q("哪些运行时歧义必须暂停并等待用户确认？",
					"{\"metric\":true,\"timeColumn\":true,\"joinPath\":true,\"restrictedDetail\":true}",
					"推荐覆盖指标、时间、Join Path 与字段策略歧义。", "runtime policy defaults", objectSchema("policy"));
			case SEMANTIC_GAP -> q("当前 Semantic Catalog 缺口应如何确认？", null, "该类别仅由活动 Semantic Gap 动态投影。",
					"active semantic gap", objectSchema("answer"));
			case GOLDEN_QUESTION -> q("请给出一个代表性的业务问题及其预期语义结果。", null, "Golden Case 用于验证 Catalog 是否表达共同理解。",
					"current catalog", objectSchema("caseCode", "question", "expected"));
			case ACCEPTANCE_CRITERIA -> q("项目初始化通过的可验证验收标准是什么？", null, "完成标准应可测试，而不是固定问题数量。",
					"coverage/readiness/golden case", objectSchema("criteria"));
		};
	}

	private void invalidateDependents(ProjectOnboardingSession session, OnboardingCategory changed) {
		repository.markQuestionsStale(session.sessionId(), changed.name());
		for (Map.Entry<OnboardingCategory, List<OnboardingCategory>> entry : DEPENDENCIES.entrySet()) {
			if (entry.getValue().contains(changed)) {
				repository.deactivateAnswersByCategory(session.sessionId(), entry.getKey());
				repository.updateCoverage(session.sessionId(), entry.getKey(), CoverageStatus.PENDING,
						"DEPENDENCY_CHANGED", changed.name());
			}
		}
	}

	private void detectConflicts(ProjectOnboardingSession session, OnboardingQuestion question, String answer) {
		Map<String, Object> value = answerObject(answer);
		List<ConflictCandidate> candidates = new ArrayList<>();
		String modelCode = string(value, "modelCode");
		if (text(modelCode) && count("""
				SELECT COUNT(*) FROM qw_semantic_model WHERE project_version_id = ? AND model_code = ?
				""", session.projectVersionId(), modelCode) == 0) {
			candidates.add(new ConflictCandidate("UNKNOWN_MODEL", "答案引用了不存在的 Model: " + modelCode, jsonValue(value)));
		}
		String columnName = firstText(value, "columnName", "timeColumn");
		if (text(modelCode) && text(columnName)
				&& count(
						"""
								SELECT COUNT(*) FROM qw_semantic_column WHERE project_version_id = ? AND model_code = ? AND column_name = ?
								""",
						session.projectVersionId(), modelCode, columnName) == 0) {
			candidates.add(new ConflictCandidate("UNKNOWN_COLUMN", "答案引用了不存在的 Column: " + modelCode + "." + columnName,
					jsonValue(value)));
		}
		if (question.category() == OnboardingCategory.MODEL_GRAIN) {
			for (String key : stringList(value.get("keyColumns"))) {
				if (count("""
						SELECT COUNT(*) FROM qw_semantic_column
						WHERE project_version_id = ? AND model_code = ? AND column_name = ?
						""", session.projectVersionId(), modelCode, key) == 0) {
					candidates.add(new ConflictCandidate("GRAIN_UNKNOWN_KEY", "Grain key 不存在: " + modelCode + "." + key,
							jsonValue(value)));
				}
			}
		}
		if (question.category() == OnboardingCategory.METRIC_AGGREGATION
				&& "COUNT".equalsIgnoreCase(string(value, "aggregation"))) {
			String metricCode = string(value, "metricCode");
			String distinct = repository.activeAnswers(session.sessionId())
				.get(OnboardingCategory.METRIC_DISTINCT_RULE);
			if (distinct != null && distinct.toLowerCase(Locale.ROOT).contains("false")
					&& count("SELECT COUNT(*) FROM qw_semantic_grain WHERE project_version_id = ?",
							session.projectVersionId()) == 0) {
				candidates.add(new ConflictCandidate("COUNT_WITHOUT_UNIQUE_GRAIN",
						"Metric " + metricCode + " 使用 COUNT，但模型尚无可验证唯一 Grain。", jsonValue(value)));
			}
		}
		if (question.category() == OnboardingCategory.RELATIONSHIP_CARDINALITY
				&& "MANY_TO_MANY".equalsIgnoreCase(string(value, "cardinality"))
				&& !repository.activeAnswers(session.sessionId()).containsKey(OnboardingCategory.FAN_OUT_POLICY)) {
			candidates.add(new ConflictCandidate("CARDINALITY_WITHOUT_FANOUT_POLICY", "多对多关系必须先定义 Fan-out 策略。",
					jsonValue(value)));
		}
		if (candidates.isEmpty()) {
			closeOpenConflictsForCategory(session, question.category());
		}
		else {
			createConflicts(session, candidates, question);
		}
	}

	private void closeOpenConflictsForCategory(ProjectOnboardingSession session, OnboardingCategory category) {
		repository.supersedeOpenConflictQuestionsByCategory(session.sessionId(), category);
		repository.resolveOpenConflictsByCategory(session.sessionId(), category);
	}

	private void createConflicts(ProjectOnboardingSession session, List<ConflictCandidate> candidates,
			OnboardingQuestion sourceQuestion) {
		List<OnboardingConflict> existing = repository.conflicts(session.sessionId());
		List<ConflictCandidate> unresolved = candidates.stream()
			.filter(candidate -> existing.stream()
				.noneMatch(conflict -> conflict.status() == ConflictStatus.OPEN
						&& conflict.conflictType().equals(candidate.type())
						&& conflict.message().equals(candidate.message())))
			.toList();
		if (unresolved.isEmpty()) {
			return;
		}
		String messages = unresolved.stream().map(ConflictCandidate::message).collect(Collectors.joining("；"));
		String evidence = unresolved.stream()
			.map(ConflictCandidate::evidence)
			.filter(Objects::nonNull)
			.collect(Collectors.joining("\n"));
		OnboardingQuestion resolution = OnboardingQuestion.builder()
			.id(UUID.randomUUID().toString())
			.sessionId(session.sessionId())
			.projectId(session.projectId())
			.projectVersionId(session.projectVersionId())
			.category(sourceQuestion.category())
			.question("检测到冲突：" + messages + "。请提交该问题的完整纠正答案并说明依据。")
			.recommendationReason("纠正答案会重新投影 Semantic Catalog 并再次执行冲突检查。")
			.evidence(evidence)
			.answerSchema(sourceQuestion.answerSchema())
			.blocking(true)
			.priority(1)
			.dependsOn(List.of())
			.status(QuestionStatus.PENDING)
			.revision(0)
			.build();
		repository.insertQuestion(resolution);
		for (ConflictCandidate candidate : unresolved) {
			repository.insertConflict(OnboardingConflict.builder()
				.id(UUID.randomUUID().toString())
				.sessionId(session.sessionId())
				.conflictType(candidate.type())
				.message(candidate.message())
				.evidence(candidate.evidence())
				.blocking(true)
				.status(ConflictStatus.OPEN)
				.resolutionQuestionId(resolution.id())
				.revision(0)
				.build());
		}
	}

	private void refreshSessionState(ProjectOnboardingSession session) {
		ProjectOnboardingSession current = repository.lockSession(session.sessionId());
		if (current.status() == SessionStatus.COMPLETED) {
			return;
		}
		OnboardingSummary summary = buildSummary(current);
		SessionStatus target = summary.readyToConfirm() ? SessionStatus.AWAITING_CONFIRMATION : SessionStatus.ACTIVE;
		if (current.status() != target && repository.updateSession(current.sessionId(), current.revision(), target,
				false, null, current.confirmationIdempotencyKey(), current.confirmationRevision(), null) != 1) {
			throw sessionConflict(current.sessionId());
		}
	}

	private OnboardingSummary buildSummary(ProjectOnboardingSession session) {
		List<OnboardingCoverageItem> coverage = repository.coverage(session.sessionId());
		List<OnboardingQuestion> questions = repository.questions(session.sessionId());
		List<OnboardingConflict> conflicts = repository.conflicts(session.sessionId());
		long required = coverage.stream()
			.filter(item -> item.requirement() != CoverageRequirement.NOT_APPLICABLE)
			.count();
		long completed = coverage.stream()
			.filter(item -> item.requirement() != CoverageRequirement.NOT_APPLICABLE)
			.filter(OnboardingCoverageItem::complete)
			.count();
		boolean coverageComplete = coverage.stream().allMatch(OnboardingCoverageItem::complete);
		long blockingQuestions = questions.stream()
			.filter(OnboardingQuestion::blocking)
			.filter(question -> question.status() == QuestionStatus.PENDING || question.status() == QuestionStatus.STALE
					|| question.status() == QuestionStatus.SKIPPED)
			.count();
		long blockingConflicts = conflicts.stream()
			.filter(OnboardingConflict::blocking)
			.filter(conflict -> conflict.status() == ConflictStatus.OPEN)
			.count();
		long openGaps = projectRepository.countOpenGaps(session.projectId(), session.projectVersionId());
		CatalogReadiness catalogReadiness = readiness.assess(session.projectId(), session.projectVersionId());
		boolean ready = coverageComplete && blockingQuestions == 0 && blockingConflicts == 0 && openGaps == 0
				&& catalogReadiness.ready();
		return OnboardingSummary.builder()
			.sessionId(session.sessionId())
			.projectId(session.projectId())
			.projectVersionId(session.projectVersionId())
			.requiredItems(required)
			.completedItems(completed)
			.blockingQuestions(blockingQuestions)
			.blockingConflicts(blockingConflicts)
			.openSemanticGaps(openGaps)
			.catalogReady(catalogReadiness.ready())
			.catalogViolations(catalogReadiness.violations())
			.acceptedAnswers(repository.activeAnswers(session.sessionId()))
			.readyToConfirm(ready)
			.confirmed(session.summaryConfirmed())
			.revision(session.revision())
			.build();
	}

	private OnboardingView view(ProjectOnboardingSession session) {
		List<OnboardingQuestion> questions = repository.questions(session.sessionId());
		OnboardingQuestion next = questions.stream()
			.filter(question -> question.status() == QuestionStatus.PENDING)
			.min(Comparator.comparingInt(OnboardingQuestion::priority))
			.orElse(null);
		return new OnboardingView(session, repository.coverage(session.sessionId()), next,
				repository.conflicts(session.sessionId()), buildSummary(session));
	}

	private ProjectOnboardingSession requireSession(Long projectId, Long versionId) {
		ProjectOnboardingSession session = repository.findSession(projectId, versionId)
			.orElseThrow(() -> new IllegalArgumentException("Onboarding session has not been started"));
		assertSessionScope(session, projectId, versionId);
		return session;
	}

	private SemanticProjectVersion requireDraftVersion(Long projectId, Long versionId) {
		SemanticProjectVersion version = projectRepository.findVersion(versionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + versionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Project onboarding can only modify a DRAFT version");
		}
		return version;
	}

	private void assertSessionScope(ProjectOnboardingSession session, Long projectId, Long versionId) {
		if (!projectId.equals(session.projectId()) || !versionId.equals(session.projectVersionId())) {
			throw new IllegalArgumentException("Onboarding idempotency key belongs to another project version");
		}
	}

	private void assertQuestionScope(OnboardingQuestion question, ProjectOnboardingSession session) {
		if (!session.sessionId().equals(question.sessionId())) {
			throw new IllegalArgumentException("Question does not belong to onboarding session");
		}
	}

	private static void assertSessionMutable(ProjectOnboardingSession session) {
		if (session.status() == SessionStatus.COMPLETED) {
			throw new IllegalStateException("Completed onboarding cannot be modified");
		}
	}

	private static void assertSameAnswer(OnboardingAnswer existing, AnswerCommand command) {
		if (!Objects.equals(existing.answer(), command.answer())
				|| !Objects.equals(existing.answerType(), command.answerType())
				|| !Objects.equals(existing.answeredBy(), command.answeredBy())
				|| existing.questionRevision() != command.revision()) {
			throw new IllegalArgumentException("idempotencyKey is already bound to a different onboarding answer");
		}
	}

	private static void assertSameQuestionCommand(OnboardingAnswer existing, QuestionCommand command,
			QuestionStatus status) {
		if (!Objects.equals(existing.answer(), Objects.toString(command.reason(), ""))
				|| !Objects.equals(existing.answerType(), status.name())
				|| !Objects.equals(existing.answeredBy(), command.answeredBy())
				|| existing.questionRevision() != command.revision()) {
			throw new IllegalArgumentException("idempotencyKey is already bound to a different onboarding command");
		}
	}

	private boolean dependenciesResolved(OnboardingCategory category,
			Map<OnboardingCategory, OnboardingCoverageItem> coverage) {
		return DEPENDENCIES.getOrDefault(category, List.of())
			.stream()
			.allMatch(dependency -> Optional.ofNullable(coverage.get(dependency))
				.map(OnboardingCoverageItem::complete)
				.orElse(false));
	}

	private static Applicability required() {
		return new Applicability(CoverageRequirement.REQUIRED, CoverageStatus.PENDING, null, null);
	}

	private static Applicability conditional(boolean applies) {
		return applies ? new Applicability(CoverageRequirement.CONDITIONAL, CoverageStatus.PENDING, null, null)
				: notApplicable("No relevant catalog evidence");
	}

	private static Applicability answered(String by, String evidence) {
		return new Applicability(CoverageRequirement.CONDITIONAL, CoverageStatus.ANSWERED, by, evidence);
	}

	private static Applicability notApplicable(String evidence) {
		return new Applicability(CoverageRequirement.NOT_APPLICABLE, CoverageStatus.NOT_APPLICABLE,
				"SYSTEM_NOT_APPLICABLE", evidence);
	}

	private static QuestionDefinition q(String question, String recommendation, String reason, String evidence,
			String schema) {
		return new QuestionDefinition(question, recommendation, reason, evidence, schema);
	}

	private Map<String, Object> answerObject(String answer) {
		try {
			if (answer.trim().startsWith("{")) {
				return JsonUtil.getObjectMapper().readValue(answer, new TypeReference<>() {
				});
			}
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("answer must match the question answerSchema", ex);
		}
		return new LinkedHashMap<>(Map.of("value", answer));
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private OptimisticLockingFailureException questionConflict(String questionId) {
		return new OptimisticLockingFailureException("OnboardingQuestion", questionId,
				repository.findQuestion(questionId).map(OnboardingQuestion::revision).orElse(-1L));
	}

	private OptimisticLockingFailureException sessionConflict(String sessionId) {
		return new OptimisticLockingFailureException("ProjectOnboardingSession", sessionId,
				repository.findSession(sessionId).map(ProjectOnboardingSession::revision).orElse(-1L));
	}

	private static String objectSchema(String... properties) {
		return json(Map.of("type", "object", "properties", List.of(properties)));
	}

	private static String string(Map<String, Object> value, String key) {
		Object result = value.get(key);
		return result == null ? null : String.valueOf(result);
	}

	private static String firstText(Map<String, Object> value, String... keys) {
		for (String key : keys) {
			String result = string(value, key);
			if (text(result)) {
				return result;
			}
		}
		return null;
	}

	private static String defaultString(Map<String, Object> value, String key, String defaultValue) {
		String result = string(value, key);
		return text(result) ? result : defaultValue;
	}

	private static List<String> stringList(Object value) {
		if (value instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		if (value instanceof String string && !string.isBlank()) {
			return List.of(string.split(","));
		}
		return List.of();
	}

	private static String mergeEvidence(String evidence, String answer) {
		return Objects.toString(evidence, "") + "\nUSER_CONFIRMED=" + answer;
	}

	private static String emptyToNull(String value) {
		return text(value) ? value : null;
	}

	private static void requireCommandMetadata(String idempotencyKey, String actor) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		if (actor == null || actor.isBlank()) {
			throw new IllegalArgumentException("answeredBy is required");
		}
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static String jsonValue(Object value) {
		return json(value);
	}

	private static String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize onboarding data", ex);
		}
	}

	private static Map<OnboardingCategory, List<OnboardingCategory>> dependencies() {
		Map<OnboardingCategory, List<OnboardingCategory>> result = new EnumMap<>(OnboardingCategory.class);
		result.put(OnboardingCategory.MODEL_TYPE, List.of(OnboardingCategory.MODEL_BUSINESS_NAME));
		result.put(OnboardingCategory.MODEL_GRAIN, List.of(OnboardingCategory.MODEL_BUSINESS_NAME));
		result.put(OnboardingCategory.MODEL_UNIQUENESS, List.of(OnboardingCategory.MODEL_GRAIN));
		result.put(OnboardingCategory.DEFAULT_TIME_COLUMN, List.of(OnboardingCategory.MODEL_GRAIN));
		result.put(OnboardingCategory.TIME_SEMANTICS, List.of(OnboardingCategory.DEFAULT_TIME_COLUMN));
		result.put(OnboardingCategory.METRIC_DEFINITION, List.of(OnboardingCategory.MODEL_GRAIN));
		result.put(OnboardingCategory.METRIC_AGGREGATION,
				List.of(OnboardingCategory.MODEL_GRAIN, OnboardingCategory.METRIC_DEFINITION));
		result.put(OnboardingCategory.METRIC_FILTER, List.of(OnboardingCategory.METRIC_DEFINITION));
		result.put(OnboardingCategory.METRIC_DISTINCT_RULE,
				List.of(OnboardingCategory.MODEL_UNIQUENESS, OnboardingCategory.METRIC_DEFINITION));
		result.put(OnboardingCategory.METRIC_ADDITIVITY, List.of(OnboardingCategory.METRIC_DEFINITION));
		result.put(OnboardingCategory.RELATIONSHIP_CARDINALITY, List.of(OnboardingCategory.RELATIONSHIP_JOIN));
		result.put(OnboardingCategory.FAN_OUT_POLICY, List.of(OnboardingCategory.RELATIONSHIP_CARDINALITY));
		result.put(OnboardingCategory.RUNTIME_CLARIFICATION_POLICY, List.of(OnboardingCategory.QUERY_AMBIGUITY_POLICY));
		result.put(OnboardingCategory.GOLDEN_QUESTION,
				List.of(OnboardingCategory.SUPPORTED_QUERY_SCOPE, OnboardingCategory.METRIC_DEFINITION));
		result.put(OnboardingCategory.ACCEPTANCE_CRITERIA, List.of(OnboardingCategory.GOLDEN_QUESTION));
		return Map.copyOf(result);
	}

	private static Map<OnboardingCategory, Integer> priorities() {
		Map<OnboardingCategory, Integer> result = new EnumMap<>(OnboardingCategory.class);
		int priority = 10;
		for (OnboardingCategory category : OnboardingCategory.values()) {
			result.put(category, priority);
			priority += 10;
		}
		return Map.copyOf(result);
	}

	public record AnswerCommand(String answer, String answerType, long revision, String idempotencyKey,
			String answeredBy) {
	}

	public record QuestionCommand(long revision, String idempotencyKey, String answeredBy, String reason) {
	}

	public record ConfirmCommand(long revision, String idempotencyKey, String confirmedBy) {
	}

	public record OnboardingView(ProjectOnboardingSession session, List<OnboardingCoverageItem> coverage,
			OnboardingQuestion nextQuestion, List<OnboardingConflict> conflicts, OnboardingSummary summary) {
	}

	private record Applicability(CoverageRequirement requirement, CoverageStatus status, String satisfiedBy,
			String evidence) {
	}

	private record QuestionDefinition(String question, String recommendedAnswer, String reason, String evidence,
			String answerSchema) {
	}

	private record ConflictCandidate(String type, String message, String evidence) {
	}

}
