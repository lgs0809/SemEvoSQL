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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationOption;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService.BindingContext;
import cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationStatus;
import cn.lgs.semevosql.clarification.RuntimeClarificationRepository.ClarificationAnswer;
import cn.lgs.semevosql.common.OptimisticLockingFailureException;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService;
import cn.lgs.semevosql.episode.domain.EpisodeTurnType;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.ThreadExecutionGuardService;
import cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPlanningQueryResolver;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeClarificationService implements ApplicationEventPublisherAware {

	private static final Set<String> METRIC_TERMS = Set.of("收入", "营收", "金额", "销售额", "revenue", "income", "sales",
			"amount");

	private static final Set<String> TIME_TERMS = Set.of("今天", "昨天", "本周", "上周", "本月", "上月", "今年", "today", "yesterday",
			"week", "month", "year");

	private static final Set<String> DETAIL_TERMS = Set.of("明细", "逐条", "列表", "detail", "records", "rows");

	private static final Set<String> DIMENSION_TERMS = Set.of("地区", "区域", "地域", "渠道", "客户", "产品", "region", "area",
			"channel", "customer", "product");

	private static final Set<String> CUSTOM_ANSWER_REQUIRED = Set.of("REFRAME_IN_SCOPE", "SPECIFY_TIME_RANGE", "OTHER");

	private final RuntimeClarificationRepository repository;

	private final SemanticCatalogCache catalogCache;

	private final QueryRunService runService;

	private final EpisodeApplicationService episodeApplicationService;

	private final ThreadExecutionGuardService threadExecutionGuardService;

	private final SemEvoSQLMetrics metrics;

	private final RuntimePrincipalResolver principalResolver;

	private final RuntimeSemanticBindingService semanticBindingService;

	private final UserSemanticPreferenceService preferenceService;

	private final ProjectSemanticAliasWorkflowService projectAliasWorkflowService;

	private final LocalOperatorService authorization;

	private ApplicationEventPublisher eventPublisher = event -> {
	};

	public RuntimeClarificationService(RuntimeClarificationRepository repository, SemanticCatalogCache catalogCache,
			QueryRunService runService, EpisodeApplicationService episodeApplicationService,
			ThreadExecutionGuardService threadExecutionGuardService, SemEvoSQLMetrics metrics,
			RuntimePrincipalResolver principalResolver,
			RuntimeSemanticBindingService semanticBindingService, UserSemanticPreferenceService preferenceService,
			ProjectSemanticAliasWorkflowService projectAliasWorkflowService,
			LocalOperatorService authorization) {
		this.repository = repository;
		this.catalogCache = catalogCache;
		this.runService = runService;
		this.episodeApplicationService = episodeApplicationService;
		this.threadExecutionGuardService = threadExecutionGuardService;
		this.metrics = metrics;
		this.principalResolver = principalResolver;
		this.semanticBindingService = semanticBindingService;
		this.preferenceService = preferenceService;
		this.projectAliasWorkflowService = projectAliasWorkflowService;
		this.authorization = authorization;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.eventPublisher = applicationEventPublisher == null ? event -> {
		} : applicationEventPublisher;
	}

	@Transactional
	public Optional<RuntimeClarification> detect(String runId, Long projectId, Long projectVersionId, String query) {
		return detect(runId, projectId, projectVersionId, query, List.of());
	}

	@Transactional
	public Optional<RuntimeClarification> detect(String runId, Long projectId, Long projectVersionId, String query,
			java.util.Collection<String> scopedPhysicalTables) {
		QueryRun run = runService.lockForUpdate(runId);
		Optional<RuntimeClarification> pending = repository.findPendingByRun(runId);
		if (pending.isPresent()) {
			return pending;
		}
		String executableQuery = ConversationContextPlanningQueryResolver.sanitizeForExecution(query);
		String effectiveQuery = applyResolvedAnswer(runId, executableQuery);
		BindingContext durableBindings = durableBindings(run, projectId, projectVersionId, effectiveQuery);
		effectiveQuery = applyDurableBindings(effectiveQuery, durableBindings);
		SemanticCatalogSnapshot catalog = catalogCache.get(projectId, projectVersionId);
		Set<String> semanticScope = semanticScopeModels(catalog, effectiveQuery, scopedPhysicalTables, durableBindings);
		RuntimeClarification clarification = List
			.of(outOfScope(runId, effectiveQuery, catalog), missingRequiredTimeRange(runId, effectiveQuery, catalog),
					restrictedDetail(runId, effectiveQuery, catalog),
					metricAmbiguity(runId, effectiveQuery, catalog, semanticScope),
					timeAmbiguity(runId, effectiveQuery, catalog, semanticScope),
					dimensionAmbiguity(runId, effectiveQuery, catalog, semanticScope),
					joinPathAmbiguity(runId, effectiveQuery, catalog, semanticScope))
			.stream()
			.flatMap(Optional::stream)
			.filter(candidate -> !coveredByDurableBinding(candidate, durableBindings))
			.filter(candidate -> !repository.hasAnsweredQuestion(runId, candidate.question()))
			.findFirst()
			.orElse(null);
		if (clarification == null) {
			return Optional.empty();
		}
		repository.insert(clarification);
		appendEpisodeTurn(run, EpisodeTurnType.CLARIFICATION_REQUEST, clarification.question(),
				clarification.clarificationId());
		if (run.status() == RunStatus.QUEUED) {
			runService.transition(runId, RunStatus.RUNNING, "runtime-clarification", null, null);
		}
		runService.transition(runId, RunStatus.WAITING_HUMAN, "runtime-clarification", null, null);
		runService.saveCheckpoint(runId, run.threadId(), "runtime-clarification",
				json(new ClarificationCheckpoint(clarification.clarificationId(), query, null, null)), "");
		runService.appendEvent(runId, "CLARIFICATION_REQUIRED", "runtime-clarification", json(clarification),
				clarification.question(), "clarification:" + clarification.clarificationId());
		RuntimeClarification persisted = repository.find(clarification.clarificationId()).orElseThrow();
		metrics.afterCommit(() -> metrics.clarificationRequired(persisted));
		return Optional.of(persisted);
	}

	@Transactional
	public RuntimeClarification createPlanningClarification(String runId, String originalQuery,
			SemanticPlanningOutcome.ClarificationRequired outcome) {
		if (outcome == null || !hasText(outcome.question())) {
			throw new IllegalArgumentException("Planning clarification question is required");
		}
		QueryRun run = runService.lockForUpdate(runId);
		Optional<RuntimeClarification> pending = repository.findPendingByRun(runId);
		if (pending.isPresent()) {
			return pending.orElseThrow();
		}
		List<SemanticPlanningOutcome.Option> sourceOptions = outcome.options() == null ? List.of() : outcome.options();
		List<ClarificationOption> options = sourceOptions.stream()
			.map(option -> new ClarificationOption(option.code(), option.label(), option.label(), outcome.reason(), null))
			.toList();
		SemanticIssueType issueType = semanticIssueType(outcome.issueType());
		String assetType = singleAssetType(sourceOptions);
		boolean allOptionsHaveAssetKeys = !sourceOptions.isEmpty()
				&& sourceOptions.stream().allMatch(option -> hasText(option.assetKey()));
		String assetKeys = allOptionsHaveAssetKeys ? sourceOptions.stream()
			.map(SemanticPlanningOutcome.Option::assetKey)
			.map(String::trim)
			.collect(java.util.stream.Collectors.joining(",")) : null;
		RuntimeClarification clarification = newClarification(runId, outcome.question(), options, null, outcome.reason(),
				"Semantic Planner requested user clarification", issueType, assetType,
				hasText(assetKeys) ? assetKeys : null, originalQuery);
		repository.insert(clarification);
		appendEpisodeTurn(run, EpisodeTurnType.CLARIFICATION_REQUEST, clarification.question(),
				clarification.clarificationId());
		if (run.status() == RunStatus.QUEUED) {
			runService.transition(runId, RunStatus.RUNNING, "semantic-planning-clarification", null, null);
		}
		runService.transition(runId, RunStatus.WAITING_HUMAN, "semantic-planning-clarification", null, null);
		runService.saveCheckpoint(runId, run.threadId(), "semantic-planning-clarification",
				json(new ClarificationCheckpoint(clarification.clarificationId(), originalQuery, null, null)), "");
		runService.appendEvent(runId, "CLARIFICATION_REQUIRED", "semantic-planning-clarification", json(clarification),
				clarification.question(), "planning-clarification:" + clarification.clarificationId());
		RuntimeClarification persisted = repository.find(clarification.clarificationId()).orElseThrow();
		metrics.afterCommit(() -> metrics.clarificationRequired(persisted));
		return persisted;
	}

	public RuntimeClarification getPending(String runId) {
		return repository.findPendingByRun(runId)
			.orElseThrow(() -> new IllegalArgumentException("No pending runtime clarification for run: " + runId));
	}

	private SemanticIssueType semanticIssueType(String value) {
		if (!hasText(value)) {
			return SemanticIssueType.USER_QUESTION_AMBIGUOUS;
		}
		try {
			return SemanticIssueType.valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ignored) {
			return SemanticIssueType.USER_QUESTION_AMBIGUOUS;
		}
	}

	private String singleAssetType(List<SemanticPlanningOutcome.Option> options) {
		Set<String> types = options.stream()
			.map(SemanticPlanningOutcome.Option::assetType)
			.map(value -> Objects.toString(value, "").trim())
			.filter(RuntimeClarificationService::hasText)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return types.size() == 1 ? types.iterator().next() : null;
	}

	public BindingContext resolvedBindingContext(String runId, Long projectId, Long projectVersionId) {
		List<BindingContext> contexts = new ArrayList<>();
		for (RuntimeClarification clarification : repository.answeredByRun(runId)) {
			if (!isBindingClarification(clarification) || !hasText(clarification.resolvedValue())) {
				continue;
			}
			BindingTarget target = selectedBindingTarget(clarification, clarification.selectedOption());
			if (target == null) {
				continue;
			}
			String phrase = Objects.toString(clarification.rawExpression(), clarification.question());
			SemanticBindingScope scope = clarification.selectedScope() == null ? SemanticBindingScope.QUERY
					: clarification.selectedScope();
			String source = switch (scope) {
				case QUERY -> "QUERY";
				case USER -> "USER";
				case PROJECT -> "PROJECT_PENDING";
			};
			Long sourceRecordId = scope == SemanticBindingScope.USER
					? preferenceService.find(projectId, clarification.answeredBy(), phrase)
						.map(UserSemanticPreferenceService.UserSemanticPreference::id)
						.orElse(null)
					: null;
			contexts.add(semanticBindingService.explicit(projectId, projectVersionId, phrase, target.assetType(),
					target.assetKey(), clarification.resolvedValue(), source, sourceRecordId, clarification.answeredBy()));
		}
		return semanticBindingService.merge(contexts);
	}

	public String applyResolvedAnswer(String runId, String originalQuery) {
		List<RuntimeClarification> answered = repository.answeredByRun(runId);
		if (answered.isEmpty()) {
			return originalQuery;
		}
		String resolvedQuery = originalQuery;
		List<String> constraints = new ArrayList<>();
		for (RuntimeClarification clarification : answered) {
			String selected = Objects.toString(clarification.selectedOption(), "");
			String custom = Objects.toString(clarification.customAnswer(), "").trim();
			if ("REFRAME_IN_SCOPE".equals(selected) && !custom.isBlank()) {
				resolvedQuery = custom;
				continue;
			}
			if ("SPECIFY_TIME_RANGE".equals(selected) && !custom.isBlank()) {
				constraints.add("时间范围=" + custom);
				continue;
			}
			if (isBindingClarification(clarification) && hasText(clarification.resolvedValue())) {
				// Known catalog targets are carried as strict structured planner hints by
				// resolvedBindingContext(). A free-form OTHER/custom answer has no safe
				// asset
				// identity, so keep it as an explicit query constraint instead of
				// dropping it.
				if (selectedBindingTarget(clarification, selected) != null) {
					continue;
				}
				if (!custom.isBlank()) {
					constraints.add("用户补充业务含义=" + custom);
					continue;
				}
			}
			constraints.add("question=" + clarification.question() + "; selectedOption=" + selected + "; customAnswer="
					+ custom);
		}
		if (constraints.isEmpty()) {
			return resolvedQuery;
		}
		return resolvedQuery + "\n[用户已确认的运行时语义澄清]\n" + String.join("\n", constraints);
	}

	@Transactional
	public RuntimeClarification answer(String runId, String clarificationId, AnswerCommand command) {
		return answer(runId, clarificationId, command, null);
	}

	@Transactional
	public RuntimeClarification answer(String runId, String clarificationId, AnswerCommand command,
			OperatorContext operator) {
		SemanticBindingScope scope = command.scope() == null ? SemanticBindingScope.QUERY : command.scope();
		if (operator != null) {
			command = new AnswerCommand(command.revision(), command.idempotencyKey(), command.selectedOption(),
					command.customAnswer(), scope, operator.operator());
		}
		if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		if (command.answeredBy() == null || command.answeredBy().isBlank()) {
			throw new IllegalArgumentException("answeredBy is required");
		}
		RuntimeClarification scoped = repository.find(clarificationId)
			.orElseThrow(() -> new IllegalArgumentException("Runtime clarification not found: " + clarificationId));
		if (!runId.equals(scoped.runId())) {
			throw new IllegalArgumentException("Clarification does not belong to run: " + runId);
		}
		validateAnswer(scoped, command, scope);
		requireDurableBindingAuthorization(runId, scope, operator);
		ClarificationAnswer idempotent = repository.findAnswerByIdempotency(clarificationId, command.idempotencyKey())
			.orElse(null);
		if (idempotent != null) {
			assertSameAnswer(idempotent, command);
			return repository.find(clarificationId).orElseThrow();
		}
		RuntimeClarification current = repository.lock(clarificationId);
		idempotent = repository.findAnswerByIdempotency(clarificationId, command.idempotencyKey()).orElse(null);
		if (idempotent != null) {
			assertSameAnswer(idempotent, command);
			return repository.find(clarificationId).orElseThrow();
		}
		if (!runId.equals(current.runId())) {
			throw new IllegalArgumentException("Clarification does not belong to run: " + runId);
		}
		if (current.revision() != command.revision()) {
			throw new OptimisticLockingFailureException("RuntimeClarification", clarificationId, current.revision());
		}
		if (current.status() != ClarificationStatus.PENDING) {
			throw new IllegalStateException("Runtime clarification is no longer pending");
		}
		validateAnswer(current, command, scope);
		repository.insertAnswer(clarificationId, command.idempotencyKey(), command.selectedOption(),
				command.customAnswer(), scope, command.answeredBy(), command.revision());
		String resolvedValue = resolveAnswerValue(current, command);
		if (repository.answer(clarificationId, command.revision(), command.selectedOption(), command.customAnswer(),
				scope, command.answeredBy(), resolvedValue, "USER_SELECTED") != 1) {
			throw new OptimisticLockingFailureException("RuntimeClarification", clarificationId,
					repository.find(clarificationId).map(RuntimeClarification::revision).orElse(-1L));
		}
		applyBindingScope(current, command, scope, resolvedValue, operator);
		QueryRun answeringRun = runService.get(runId);
		appendEpisodeTurn(answeringRun, EpisodeTurnType.CLARIFICATION_RESPONSE,
				Objects.toString(command.customAnswer(), command.selectedOption()), clarificationId);
		String resumeNode = "semantic-planning-clarification".equals(answeringRun.currentNode())
				? "semantic-planning-clarification" : "runtime-clarification";
		runService.saveCheckpoint(runId, answeringRun.threadId(), resumeNode, json(
				new ClarificationCheckpoint(clarificationId, null, command.selectedOption(), command.customAnswer())),
				resumeNode + ":" + clarificationId);
		runService.appendEvent(runId, "CLARIFICATION_ANSWERED", resumeNode, json(command),
				"Runtime clarification answered", "clarification-answer:" + command.idempotencyKey());
		boolean cancelled = "CANCEL".equals(command.selectedOption());
		if (cancelled) {
			runService.cancel(runId, "clarification:" + command.idempotencyKey());
			QueryRun cancelledRun = runService.acknowledgeCancelled(runId);
			runService.appendEvent(runId, "RUN_CANCELLED", cancelledRun.currentNode(), null, "Run cancelled",
					"run-cancelled:" + runId);
			threadExecutionGuardService.release(cancelledRun.threadId(), cancelledRun.runId());
		}
		else {
			runService.resume(runId, "clarification:" + command.idempotencyKey());
			eventPublisher.publishEvent(new RuntimeClarificationResumeRequestedEvent(runId));
		}
		RuntimeClarification answered = repository.find(clarificationId).orElseThrow();
		metrics.afterCommit(() -> metrics.clarificationAnswered(answered, cancelled));
		return answered;
	}

	private void appendEpisodeTurn(QueryRun run, EpisodeTurnType turnType, String content, String clarificationId) {
		if (run == null || !hasText(run.episodeId())) {
			return;
		}
		episodeApplicationService.appendTurn(run.episodeId(), turnType,
				turnType == EpisodeTurnType.CLARIFICATION_RESPONSE ? "USER" : "ASSISTANT", content,
				Map.of("clarificationId", clarificationId, "runId", run.runId()),
				"clarification:" + clarificationId + ":" + turnType.name());
	}

	private Optional<RuntimeClarification> outOfScope(String runId, String query, SemanticCatalogSnapshot catalog) {
		String normalized = normalize(query);
		List<SemanticCatalogSnapshot.Rule> matched = catalog.getRules()
			.stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(rule -> "UNSUPPORTED_QUERY_SCOPE".equalsIgnoreCase(rule.getRuleType()))
			.filter(rule -> scopeRuleMatches(normalized, rule))
			.toList();
		if (matched.isEmpty()) {
			return Optional.empty();
		}
		String evidence = matched.stream()
			.map(rule -> rule.getRuleCode() + ":" + Objects.toString(rule.getExpression(), ""))
			.limit(10)
			.collect(java.util.stream.Collectors.joining("; "));
		List<ClarificationOption> options = List.of(new ClarificationOption("REFRAME_IN_SCOPE", "改写为项目支持的查询",
				"REFRAME_IN_SCOPE", "请在补充说明中给出符合项目支持范围的新问题。", evidence),
				new ClarificationOption("CANCEL", "取消请求", "CANCEL", "不继续生成超出项目边界的 SQL。", evidence));
		return Optional.of(newClarification(runId, "该请求命中了项目明确声明的不支持范围，是否改写问题？", options, "REFRAME_IN_SCOPE",
				"Semantic Catalog 的 UNSUPPORTED_QUERY_SCOPE 规则禁止直接执行该请求。", evidence, SemanticIssueType.OUT_OF_SCOPE,
				"RULE", matched.get(0).getRuleCode(), query));
	}

	private Optional<RuntimeClarification> missingRequiredTimeRange(String runId, String query,
			SemanticCatalogSnapshot catalog) {
		String normalized = normalize(query);
		if (containsAny(normalized, TIME_TERMS) || containsExplicitDate(normalized)) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Rule> policies = catalog.getRules()
			.stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(rule -> "RUNTIME_CLARIFICATION_POLICY".equalsIgnoreCase(rule.getRuleType())
					|| "QUERY_AMBIGUITY_POLICY".equalsIgnoreCase(rule.getRuleType()))
			.filter(RuntimeClarificationService::requiresExplicitTimeRange)
			.toList();
		if (policies.isEmpty()) {
			return Optional.empty();
		}
		String evidence = policies.stream()
			.map(rule -> rule.getRuleCode() + ":" + Objects.toString(rule.getExpression(), ""))
			.limit(10)
			.collect(java.util.stream.Collectors.joining("; "));
		List<ClarificationOption> options = List.of(
				new ClarificationOption("SPECIFY_TIME_RANGE", "补充明确时间范围", "SPECIFY_TIME_RANGE",
						"在补充说明中填写开始、结束时间或业务时间表达。", evidence),
				new ClarificationOption("CANCEL", "取消请求", "CANCEL", "不在缺少强制时间范围时执行查询。", evidence));
		return Optional
			.of(newClarification(runId, "该项目要求查询必须包含时间范围，请补充具体时间。", options, null, "运行时澄清策略要求显式时间范围，当前问题未包含可识别的时间表达。",
					evidence, SemanticIssueType.TIME_SEMANTICS_MISSING, "RULE", policies.get(0).getRuleCode(), query));
	}

	private Optional<RuntimeClarification> dimensionAmbiguity(String runId, String query,
			SemanticCatalogSnapshot catalog, Set<String> semanticScope) {
		String normalized = normalize(query);
		Set<String> requestedGenericTerms = DIMENSION_TERMS.stream()
			.filter(normalized::contains)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		List<SemanticCatalogSnapshot.Dimension> exactMatches = catalog.getDimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> semanticScope.isEmpty() || semanticScope.contains(dimension.getModelCode()))
			.filter(dimension -> matches(normalized, dimension.getBusinessName(), dimension.getDimensionCode(),
					dimension.getColumnName()))
			.toList();
		if ((requestedGenericTerms.isEmpty() && exactMatches.size() == 1)
				|| explicitlyRequestsAllDimensions(normalized, exactMatches)) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Dimension> genericMatches = requestedGenericTerms.isEmpty() ? List.of()
				: catalog.getDimensions()
					.stream()
					.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
					.filter(dimension -> semanticScope.isEmpty() || semanticScope.contains(dimension.getModelCode()))
					.filter(dimension -> containsAny(normalize(dimension.getBusinessName() + " "
							+ dimension.getDimensionCode() + " " + dimension.getColumnName()), requestedGenericTerms))
					.toList();
		List<SemanticCatalogSnapshot.Dimension> matches = genericMatches.size() > exactMatches.size() ? genericMatches
				: exactMatches;
		if (matches.size() > 1 && hasUniqueSpecificDimensionMatch(normalized, exactMatches, requestedGenericTerms)) {
			return Optional.empty();
		}
		if (matches.size() <= 1) {
			return Optional.empty();
		}
		List<ClarificationOption> options = businessOptions(matches.stream()
			.map(dimension -> new BusinessOption(dimension.getBusinessName(), dimension.getDescription()))
			.toList());
		return Optional.of(newClarification(runId, "您所说的分析维度具体指哪一个？", options, null, "多个同等优先级维度与查询匹配，系统不能随机选择。",
				"Semantic Catalog dimension matches=" + options.size(), SemanticIssueType.DIMENSION_AMBIGUOUS,
				"DIMENSION",
				matches.stream()
					.map(SemanticCatalogSnapshot.Dimension::getDimensionCode)
					.collect(java.util.stream.Collectors.joining(",")),
				ambiguousPhrase(normalized,
						matches.stream().map(SemanticCatalogSnapshot.Dimension::getBusinessName).toList(),
						DIMENSION_TERMS, "分析维度")));
	}

	static boolean hasUniqueSpecificDimensionMatch(String query,
			List<SemanticCatalogSnapshot.Dimension> exactMatches, Set<String> requestedGenericTerms) {
		long specificMatches = exactMatches.stream()
			.filter(dimension -> List.of(normalize(dimension.getBusinessName()), normalize(dimension.getDimensionCode()),
					normalize(dimension.getColumnName()))
				.stream()
				.filter(RuntimeClarificationService::hasText)
				.anyMatch(token -> containsAssetToken(query, token) && !requestedGenericTerms.contains(token)))
			.count();
		return specificMatches == 1;
	}

	private static boolean explicitlyRequestsAllDimensions(String query,
			List<SemanticCatalogSnapshot.Dimension> exactMatches) {
		return exactMatches.size() > 1
				&& exactMatches.stream().allMatch(dimension -> hasUniqueExplicitDimensionToken(query, dimension, exactMatches));
	}

	private static boolean hasUniqueExplicitDimensionToken(String query, SemanticCatalogSnapshot.Dimension dimension,
			List<SemanticCatalogSnapshot.Dimension> exactMatches) {
		for (String token : List.of(normalize(dimension.getBusinessName()), normalize(dimension.getDimensionCode()),
				normalize(dimension.getColumnName()))) {
			if (token.isBlank() || !containsAssetToken(query, token)) {
				continue;
			}
			boolean shared = exactMatches.stream()
				.filter(other -> other != dimension)
				.anyMatch(other -> token.equals(normalize(other.getBusinessName()))
						|| token.equals(normalize(other.getDimensionCode()))
						|| token.equals(normalize(other.getColumnName())));
			if (!shared) {
				return true;
			}
		}
		return false;
	}

	private Optional<RuntimeClarification> metricAmbiguity(String runId, String query, SemanticCatalogSnapshot catalog,
			Set<String> semanticScope) {
		String normalized = normalize(query);
		boolean genericMetricRequest = containsAny(normalized, METRIC_TERMS);
		List<SemanticCatalogSnapshot.Metric> exactMatches = catalog.getMetrics()
			.stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> semanticScope.isEmpty() || semanticScope.contains(metric.getModelCode()))
			.filter(metric -> matches(normalized, metric.getBusinessName(), metric.getMetricCode()))
			.toList();
		if (exactMatches.size() == 1 || explicitlyRequestsAllMetrics(normalized, exactMatches)) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Metric> matches = exactMatches.isEmpty() && genericMetricRequest
				? catalog.getMetrics()
					.stream()
					.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
					.filter(metric -> semanticScope.isEmpty() || semanticScope.contains(metric.getModelCode()))
					.filter(metric -> containsAny(normalize(metric.getBusinessName() + " " + metric.getMetricCode()),
							METRIC_TERMS))
					.toList()
				: exactMatches;
		if (matches.size() <= 1) {
			return Optional.empty();
		}
		List<ClarificationOption> options = businessOptions(matches.stream()
			.map(metric -> new BusinessOption(metric.getBusinessName(), metric.getDescription()))
			.toList());
		return Optional.of(newClarification(runId, "您所说的指标具体指哪一个？", options, null, "多个同等优先级指标与查询匹配，系统不能随机选择。",
				"Semantic Catalog metric matches=" + matches.size(), SemanticIssueType.METRIC_AMBIGUOUS, "METRIC",
				matches.stream()
					.map(SemanticCatalogSnapshot.Metric::getMetricCode)
					.collect(java.util.stream.Collectors.joining(",")),
				ambiguousPhrase(normalized,
						matches.stream().map(SemanticCatalogSnapshot.Metric::getBusinessName).toList(), METRIC_TERMS,
						"指标")));
	}

	private static boolean explicitlyRequestsAllMetrics(String query,
			List<SemanticCatalogSnapshot.Metric> exactMatches) {
		return exactMatches.size() > 1
				&& exactMatches.stream().allMatch(metric -> hasUniqueExplicitMetricToken(query, metric, exactMatches));
	}

	private static boolean hasUniqueExplicitMetricToken(String query, SemanticCatalogSnapshot.Metric metric,
			List<SemanticCatalogSnapshot.Metric> exactMatches) {
		for (String token : List.of(normalize(metric.getBusinessName()), normalize(metric.getMetricCode()))) {
			if (token.isBlank() || !containsAssetToken(query, token)) {
				continue;
			}
			boolean shared = exactMatches.stream()
				.filter(other -> other != metric)
				.anyMatch(other -> token.equals(normalize(other.getBusinessName()))
						|| token.equals(normalize(other.getMetricCode())));
			if (!shared) {
				return true;
			}
		}
		return false;
	}

	private Optional<RuntimeClarification> timeAmbiguity(String runId, String query, SemanticCatalogSnapshot catalog,
			Set<String> semanticScope) {
		String normalized = normalize(query);
		if (!containsAny(normalized, TIME_TERMS)) {
			return Optional.empty();
		}
		Set<String> relevantModels = relevantTimeModels(runId, normalized, catalog, semanticScope);
		if (relevantModels.isEmpty()) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Column> timeColumns = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> column.getRole() == SemanticColumnRole.TIME)
			.filter(column -> relevantModels.contains(column.getModelCode()))
			.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
			.toList();
		if (timeColumns.size() <= 1) {
			return Optional.empty();
		}
		List<ClarificationOption> options = businessOptions(timeColumns.stream()
			.map(column -> new BusinessOption(column.getBusinessName(), column.getDescription()))
			.toList());
		return Optional
			.of(newClarification(runId, "该时间范围应按哪个业务时间字段计算？", options, null, "存在多个合理时间字段，且 Catalog 未声明唯一默认业务时间。",
					"time columns=" + options.size(), SemanticIssueType.TIME_SEMANTICS_AMBIGUOUS, "COLUMN",
					timeColumns.stream()
						.map(column -> column.getModelCode() + ":" + column.getColumnName())
						.collect(java.util.stream.Collectors.joining(",")),
					ambiguousPhrase(normalized, List.of(), TIME_TERMS, "业务时间")));
	}

	private Set<String> semanticScopeModels(SemanticCatalogSnapshot catalog, String query,
			java.util.Collection<String> scopedPhysicalTables, BindingContext durableBindings) {
		Set<String> models = new LinkedHashSet<>();
		Set<String> tables = scopedPhysicalTables == null ? Set.of()
				: scopedPhysicalTables.stream()
					.filter(RuntimeClarificationService::hasText)
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> tables.contains(model.getPhysicalTable()))
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		if (durableBindings != null && durableBindings.bindings() != null) {
			durableBindings.bindings()
				.stream()
				.map(cn.lgs.semevosql.clarification.RuntimeSemanticBindingService.ResolvedRuntimeBinding::modelCode)
				.filter(RuntimeClarificationService::hasText)
				.forEach(models::add);
		}
		if (!models.isEmpty()) {
			return models;
		}
		String normalized = normalize(query);
		catalog.getMetrics()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(asset -> matches(normalized, asset.getBusinessName(), asset.getMetricCode()))
			.map(SemanticCatalogSnapshot.Metric::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		catalog.getDimensions()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(asset -> matches(normalized, asset.getBusinessName(), asset.getDimensionCode(),
					asset.getColumnName()))
			.map(SemanticCatalogSnapshot.Dimension::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		catalog.getModels()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(asset -> matches(normalized, asset.getBusinessName(), asset.getModelCode(),
					asset.getPhysicalTable()))
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		if (models.isEmpty()) {
			List<String> enabled = catalog.getModels()
				.stream()
				.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
				.map(SemanticCatalogSnapshot.Model::getModelCode)
				.filter(RuntimeClarificationService::hasText)
				.toList();
			if (enabled.size() == 1) {
				models.add(enabled.get(0));
			}
		}
		return models;
	}

	private Set<String> relevantTimeModels(String runId, String normalizedQuery, SemanticCatalogSnapshot catalog,
			Set<String> semanticScope) {
		Set<String> models = answeredBindingModels(runId, catalog);
		if (!semanticScope.isEmpty()) {
			models.retainAll(semanticScope);
		}
		if (models.isEmpty() && !semanticScope.isEmpty()) {
			models.addAll(semanticScope);
		}
		if (!models.isEmpty()) {
			return models;
		}
		catalog.getMetrics()
			.stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> matches(normalizedQuery, metric.getBusinessName(), metric.getMetricCode()))
			.map(SemanticCatalogSnapshot.Metric::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		catalog.getDimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> matches(normalizedQuery, dimension.getBusinessName(), dimension.getDimensionCode(),
					dimension.getColumnName()))
			.map(SemanticCatalogSnapshot.Dimension::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> matches(normalizedQuery, model.getBusinessName(), model.getModelCode(),
					model.getPhysicalTable()))
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.filter(RuntimeClarificationService::hasText)
			.forEach(models::add);
		if (models.isEmpty()) {
			List<String> enabled = catalog.getModels()
				.stream()
				.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
				.map(SemanticCatalogSnapshot.Model::getModelCode)
				.filter(RuntimeClarificationService::hasText)
				.toList();
			if (enabled.size() == 1) {
				models.add(enabled.get(0));
			}
		}
		return models;
	}

	private Set<String> answeredBindingModels(String runId, SemanticCatalogSnapshot catalog) {
		Set<String> models = new LinkedHashSet<>();
		for (RuntimeClarification clarification : repository.answeredByRun(runId)) {
			BindingTarget target = selectedBindingTarget(clarification, clarification.selectedOption());
			if (target == null) {
				continue;
			}
			if ("METRIC".equals(target.assetType())) {
				catalog.getMetrics()
					.stream()
					.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
					.filter(value -> Objects.equals(value.getMetricCode(), target.assetKey()))
					.map(SemanticCatalogSnapshot.Metric::getModelCode)
					.filter(RuntimeClarificationService::hasText)
					.forEach(models::add);
			}
			else if ("DIMENSION".equals(target.assetType())) {
				catalog.getDimensions()
					.stream()
					.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
					.filter(value -> Objects.equals(value.getDimensionCode(), target.assetKey()))
					.map(SemanticCatalogSnapshot.Dimension::getModelCode)
					.filter(RuntimeClarificationService::hasText)
					.forEach(models::add);
			}
			else if ("ENUM_VALUE".equals(target.assetType()) || "TIME_COLUMN".equals(target.assetType())) {
				String[] parts = target.assetKey().split(":", 2);
				if (parts.length > 0 && hasText(parts[0])) {
					models.add(parts[0]);
				}
			}
		}
		return models;
	}

	private Optional<RuntimeClarification> restrictedDetail(String runId, String query,
			SemanticCatalogSnapshot catalog) {
		String normalized = normalize(query);
		if (!containsAny(normalized, DETAIL_TERMS)) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Column> restricted = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> Boolean.FALSE.equals(column.getAllowProjection()))
			.toList();
		if (restricted.isEmpty()) {
			return Optional.empty();
		}
		List<ClarificationOption> options = List.of(
				new ClarificationOption("AGGREGATE_ONLY", "改为聚合结果", "AGGREGATE_ONLY", "受限字段不允许明细投影，但允许安全聚合。",
						restrictedEvidence(restricted)),
				new ClarificationOption("CANCEL", "取消请求", "CANCEL", "不生成绕过字段策略的 SQL。", restrictedEvidence(restricted)));
		return Optional.of(newClarification(runId, "请求包含受限明细字段，是否改为聚合查询？", options, "AGGREGATE_ONLY",
				"Column Policy 禁止直接投影一个或多个明细字段。", restrictedEvidence(restricted), SemanticIssueType.PERMISSION_DENIED,
				"COLUMN", restrictedEvidence(restricted), query));
	}

	private Optional<RuntimeClarification> joinPathAmbiguity(String runId, String query,
			SemanticCatalogSnapshot catalog, Set<String> semanticScope) {
		Set<String> mentionedModels = new LinkedHashSet<>();
		String normalized = normalize(query);
		catalog.getModels()
			.stream()
			.filter(model -> semanticScope.isEmpty() || semanticScope.contains(model.getModelCode()))
			.filter(model -> matches(normalized, model.getBusinessName(), model.getModelCode()))
			.forEach(model -> mentionedModels.add(model.getModelCode()));
		if (mentionedModels.size() < 2) {
			return Optional.empty();
		}
		List<SemanticCatalogSnapshot.Relationship> paths = catalog.getRelationships()
			.stream()
			.filter(relationship -> mentionedModels.contains(relationship.getSourceModelCode())
					&& mentionedModels.contains(relationship.getTargetModelCode()))
			.toList();
		if (paths.size() <= 1) {
			return Optional.empty();
		}
		List<ClarificationOption> options = paths.stream()
			.map(path -> new ClarificationOption(path.getRelationshipCode(), path.getRelationshipCode(),
					path.getJoinCondition(), Objects.toString(path.getCardinality(), ""), path.getEvidence()))
			.toList();
		return Optional.of(newClarification(runId, "该查询应采用哪条模型关联路径？", options, null, "多个合法 Join Path 同时连接查询涉及的模型。",
				"relationship paths=" + paths.size(), SemanticIssueType.USER_QUESTION_AMBIGUOUS, "RELATIONSHIP",
				paths.stream()
					.map(SemanticCatalogSnapshot.Relationship::getRelationshipCode)
					.collect(java.util.stream.Collectors.joining(",")),
				query));
	}

	private RuntimeClarification newClarification(String runId, String question, List<ClarificationOption> options,
			String recommended, String reason, String evidence) {
		return newClarification(runId, question, options, recommended, reason, evidence, SemanticIssueType.UNKNOWN,
				null, null, null);
	}

	private RuntimeClarification newClarification(String runId, String question, List<ClarificationOption> options,
			String recommended, String reason, String evidence, SemanticIssueType issueType, String assetType,
			String assetKey, String rawExpression) {
		return RuntimeClarification.builder()
			.clarificationId(UUID.randomUUID().toString())
			.runId(runId)
			.question(question)
			.options(new ArrayList<>(options))
			.recommendedOption(recommended)
			.reason(reason)
			.evidence(evidence)
			.issueType(issueType)
			.assetType(assetType)
			.assetKey(assetKey)
			.rawExpression(rawExpression)
			.status(ClarificationStatus.PENDING)
			.revision(0)
			.build();
	}

	private String resolveAnswerValue(RuntimeClarification current, AnswerCommand command) {
		if (command.customAnswer() != null && !command.customAnswer().isBlank()) {
			return command.customAnswer().trim();
		}
		return current.options()
			.stream()
			.filter(option -> Objects.equals(option.code(), command.selectedOption()))
			.map(ClarificationOption::value)
			.findFirst()
			.orElse(command.selectedOption());
	}

	private void requireDurableBindingAuthorization(String runId, SemanticBindingScope scope,
			OperatorContext operator) {
		if (scope == SemanticBindingScope.QUERY) {
			// The no-operator overload is a trusted in-process API used by the durable
			// runtime and state-machine tests. External HTTP/MCP entrypoints always call
			// the OperatorContext overload and therefore enforce Run ownership here.
			if (operator != null) {
				requireRunOwner(runId, operator);
			}
		}
		else if (scope == SemanticBindingScope.USER) {
			requireRunOwner(runId, operator);
		}
		else if (scope == SemanticBindingScope.PROJECT) {
			authorization.require(operator, "persist PROJECT semantic binding");
		}
	}

	private void requireRunOwner(String runId, OperatorContext operator) {
		if (operator == null) {
			throw new SecurityException("A server-resolved OperatorContext is required for QUERY/USER semantic binding");
		}
		String principal = principalResolver.resolve(runService.get(runId));
		if (!hasText(principal) || RuntimePrincipalResolver.ANONYMOUS.equals(principal)) {
			throw new IllegalStateException("A stable authenticated userId is required for USER semantic binding");
		}
		if (!Objects.equals(principal, operator.operator())) {
			throw new SecurityException("USER semantic binding can only be changed by the Run owner");
		}
	}

	private void applyBindingScope(RuntimeClarification current, AnswerCommand command, SemanticBindingScope scope,
			String resolvedValue, OperatorContext operator) {
		if (scope == SemanticBindingScope.QUERY || !isDurablePhraseBinding(current)) {
			return;
		}
		QueryRun run = runService.get(current.runId());
		String principal = principalResolver.resolve(run);
		if (!hasText(principal) || RuntimePrincipalResolver.ANONYMOUS.equals(principal)) {
			throw new IllegalStateException("A stable authenticated userId is required for durable semantic bindings");
		}
		BindingTarget target = selectedBindingTarget(current, command);
		if (target == null) {
			throw new IllegalArgumentException("Selected clarification option cannot be saved as a semantic binding");
		}
		if (scope == SemanticBindingScope.USER) {
			preferenceService.save(run.projectId(), principal, current.rawExpression(), target.assetType(),
					target.assetKey(), resolvedValue);
		}
		else if (scope == SemanticBindingScope.PROJECT) {
			projectAliasWorkflowService.proposeAlias(run.projectId(), current.rawExpression(), target.assetType(),
					target.assetKey(), resolvedValue, operator);
		}
	}

	private BindingTarget selectedBindingTarget(RuntimeClarification current, AnswerCommand command) {
		return selectedBindingTarget(current, command.selectedOption());
	}

	private BindingTarget selectedBindingTarget(RuntimeClarification current, String selectedOption) {
		if (!isBindingClarification(current) || "OTHER".equals(selectedOption)) {
			return null;
		}
		int selectedIndex = -1;
		for (int i = 0; i < current.options().size(); i++) {
			if (Objects.equals(current.options().get(i).code(), selectedOption)) {
				selectedIndex = i;
				break;
			}
		}
		if (selectedIndex < 0) {
			return null;
		}
		List<String> targets = java.util.Arrays.stream(Objects.toString(current.assetKey(), "").split(","))
			.map(String::trim)
			.filter(RuntimeClarificationService::hasText)
			.toList();
		if (selectedIndex >= targets.size()) {
			return null;
		}
		String type = current.issueType() == SemanticIssueType.TIME_SEMANTICS_AMBIGUOUS ? "TIME_COLUMN"
				: current.assetType();
		return new BindingTarget(type, targets.get(selectedIndex));
	}

	private static boolean isBindingClarification(RuntimeClarification clarification) {
		if (clarification == null || clarification.issueType() == null) {
			return false;
		}
		return clarification.issueType() == SemanticIssueType.METRIC_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.DIMENSION_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.TIME_SEMANTICS_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.ENUM_MAPPING_AMBIGUOUS
				|| hasMappedDurableOptions(clarification);
	}

	static boolean isDurablePhraseBinding(RuntimeClarification clarification) {
		return clarification != null && (clarification.issueType() == SemanticIssueType.METRIC_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.DIMENSION_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.TIME_SEMANTICS_AMBIGUOUS
				|| clarification.issueType() == SemanticIssueType.ENUM_MAPPING_AMBIGUOUS
				|| hasMappedDurableOptions(clarification));
	}

	private static boolean hasMappedDurableOptions(RuntimeClarification clarification) {
		if (clarification.issueType() != SemanticIssueType.USER_QUESTION_AMBIGUOUS
				|| !Set.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN").contains(clarification.assetType())
				|| clarification.options() == null || clarification.options().isEmpty()) {
			return false;
		}
		List<String> targets = java.util.Arrays.stream(Objects.toString(clarification.assetKey(), "").split(","))
			.map(String::trim)
			.filter(RuntimeClarificationService::hasText)
			.toList();
		return targets.size() == clarification.options().size();
	}

	private BindingContext durableBindings(QueryRun run, Long projectId, Long projectVersionId, String query) {
		String principal = principalResolver.resolve(run);
		return semanticBindingService.resolve(projectId, projectVersionId, principal, query);
	}

	private String applyDurableBindings(String query, BindingContext context) {
		if (context == null || context.empty()) {
			return query;
		}
		List<String> constraints = context.bindings()
			.stream()
			.map(binding -> "“" + binding.displayPhrase() + "”表示“" + binding.businessLabel() + "”（来源="
					+ binding.source() + "）")
			.toList();
		return query + "\n[已保存的业务表达习惯]\n" + String.join("\n", constraints);
	}

	private boolean coveredByDurableBinding(RuntimeClarification candidate, BindingContext context) {
		if (context == null || context.empty() || !isDurablePhraseBinding(candidate)) {
			return false;
		}
		String expectedType = candidate.issueType() == SemanticIssueType.TIME_SEMANTICS_AMBIGUOUS ? "TIME_COLUMN"
				: candidate.assetType();
		Set<String> targets = java.util.Arrays.stream(Objects.toString(candidate.assetKey(), "").split(","))
			.map(String::trim)
			.filter(RuntimeClarificationService::hasText)
			.map(value -> expectedType.equals("TIME_COLUMN") ? value.replace('.', ':') : value)
			.collect(java.util.stream.Collectors.toSet());
		String expression = normalize(Objects.toString(candidate.rawExpression(), ""));
		return context.bindings()
			.stream()
			.filter(binding -> expectedType.equals(binding.assetType()))
			.filter(binding -> targets.contains(binding.assetKey()))
			.anyMatch(binding -> expression.contains(normalize(binding.displayPhrase())));
	}

	private void validateAnswer(RuntimeClarification current, AnswerCommand command, SemanticBindingScope scope) {
		if ((command.selectedOption() == null || command.selectedOption().isBlank())
				&& (command.customAnswer() == null || command.customAnswer().isBlank())) {
			throw new IllegalArgumentException("selectedOption or customAnswer is required");
		}
		if (command.selectedOption() != null
				&& current.options().stream().noneMatch(option -> option.code().equals(command.selectedOption()))) {
			throw new IllegalArgumentException("Unknown clarification option: " + command.selectedOption());
		}
		if (command.selectedOption() != null && CUSTOM_ANSWER_REQUIRED.contains(command.selectedOption())
				&& (command.customAnswer() == null || command.customAnswer().isBlank())) {
			throw new IllegalArgumentException("customAnswer is required for option: " + command.selectedOption());
		}
		if (scope != SemanticBindingScope.QUERY && !isDurablePhraseBinding(current)) {
			throw new IllegalArgumentException("Only durable language aliases can be saved beyond the current query");
		}
		if (scope != SemanticBindingScope.QUERY && "OTHER".equals(command.selectedOption())) {
			throw new IllegalArgumentException(
					"A custom meaning must first map to an existing semantic asset before it can be saved");
		}
	}

	private static void assertSameAnswer(ClarificationAnswer existing, AnswerCommand command) {
		SemanticBindingScope scope = command.scope() == null ? SemanticBindingScope.QUERY : command.scope();
		if (!Objects.equals(existing.selectedOption(), command.selectedOption())
				|| !Objects.equals(existing.customAnswer(), command.customAnswer())
				|| !Objects.equals(existing.selectedScope(), scope)
				|| !Objects.equals(existing.answeredBy(), command.answeredBy())
				|| existing.clarificationRevision() != command.revision()) {
			throw new IllegalArgumentException("idempotencyKey is already bound to a different clarification answer");
		}
	}

	private static List<ClarificationOption> businessOptions(List<BusinessOption> values) {
		List<ClarificationOption> options = new ArrayList<>();
		for (int index = 0; index < values.size(); index++) {
			BusinessOption value = values.get(index);
			String label = businessOptionLabel(value.businessName(), value.description());
			options.add(new ClarificationOption("OPTION_" + (index + 1), label, label,
					hasText(value.description()) ? value.description() : "选择这个业务含义。", null));
		}
		options.add(new ClarificationOption("OTHER", "其他", "其他", "现有选项都不是我要表达的业务含义。", null));
		return List.copyOf(options);
	}

	private static String businessOptionLabel(String businessName, String description) {
		String name = hasText(businessName) ? businessName.trim() : "未命名业务含义";
		if (!hasText(description)) {
			return name;
		}
		String detail = description.trim();
		return normalize(detail).contains(normalize(name)) ? detail : name + "（" + detail + "）";
	}

	private static String ambiguousPhrase(String normalizedQuery, List<String> businessNames, Set<String> fallbackTerms,
			String fallback) {
		Map<String, Long> counts = businessNames.stream()
			.filter(RuntimeClarificationService::hasText)
			.collect(java.util.stream.Collectors.groupingBy(RuntimeClarificationService::normalize, LinkedHashMap::new,
					java.util.stream.Collectors.counting()));
		for (String businessName : businessNames) {
			String normalizedName = normalize(businessName);
			if (counts.getOrDefault(normalizedName, 0L) > 1 && normalizedQuery.contains(normalizedName)) {
				return businessName.trim();
			}
		}
		return fallbackTerms.stream()
			.filter(normalizedQuery::contains)
			.sorted(java.util.Comparator.comparingInt(String::length).reversed())
			.findFirst()
			.orElse(fallback);
	}

	private static boolean matches(String query, String... values) {
		for (String value : values) {
			String normalizedValue = normalize(value);
			if (!normalizedValue.isBlank() && containsAssetToken(query, normalizedValue)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAssetToken(String query, String value) {
		boolean containsCjk = value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
		if (containsCjk) {
			return query.contains(value);
		}
		if (value.length() < 2) {
			return query.equals(value);
		}
		return java.util.regex.Pattern
			.compile("(?<![a-z0-9_])" + java.util.regex.Pattern.quote(value) + "(?![a-z0-9_])")
			.matcher(query)
			.find();
	}

	private static boolean containsAny(String value, Set<String> terms) {
		return terms.stream().anyMatch(value::contains);
	}

	private static boolean scopeRuleMatches(String normalizedQuery, SemanticCatalogSnapshot.Rule rule) {
		if (matches(normalizedQuery, rule.getBusinessName())) {
			return true;
		}
		String expression = Objects.toString(rule.getExpression(), "");
		for (String token : expression.split("[，,;；。\\n|/]+")) {
			String normalizedToken = normalize(token);
			if (meaningfulScopeToken(normalizedToken) && normalizedQuery.contains(normalizedToken)) {
				return true;
			}
		}
		return false;
	}

	private static boolean meaningfulScopeToken(String value) {
		if (value.isBlank()) {
			return false;
		}
		boolean containsCjk = value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
		return containsCjk ? value.codePointCount(0, value.length()) >= 2 : value.length() >= 4;
	}

	private static boolean requiresExplicitTimeRange(SemanticCatalogSnapshot.Rule rule) {
		String policy = normalize(Objects.toString(rule.getExpression(), "") + " "
				+ Objects.toString(rule.getDescription(), "") + " " + Objects.toString(rule.getBusinessName(), ""));
		return policy.contains("必须指定时间") || policy.contains("必须包含时间") || policy.contains("需要时间范围")
				|| policy.contains("显式时间范围") || policy.contains("requiredtimerange")
				|| policy.contains("timeisrequired") || policy.contains("mustspecifytime");
	}

	private static boolean containsExplicitDate(String normalizedQuery) {
		return normalizedQuery.matches(".*\\d{4}[-/.年]\\d{1,2}.*")
				|| normalizedQuery.matches(".*\\d{1,2}[-/.月]\\d{1,2}.*");
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String restrictedEvidence(List<SemanticCatalogSnapshot.Column> columns) {
		return columns.stream()
			.map(column -> column.getModelCode() + "." + column.getColumnName())
			.limit(20)
			.collect(java.util.stream.Collectors.joining(","));
	}

	private static String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize clarification state", ex);
		}
	}

	private record BindingTarget(String assetType, String assetKey) {
	}

	private record BusinessOption(String businessName, String description) {
	}

	public record AnswerCommand(long revision, String idempotencyKey, String selectedOption, String customAnswer,
			SemanticBindingScope scope, String answeredBy) {

		public AnswerCommand(long revision, String idempotencyKey, String selectedOption, String customAnswer,
				String answeredBy) {
			this(revision, idempotencyKey, selectedOption, customAnswer, SemanticBindingScope.QUERY, answeredBy);
		}
	}

	private record ClarificationCheckpoint(String clarificationId, String originalQuery, String selectedOption,
			String customAnswer) {
	}

}
