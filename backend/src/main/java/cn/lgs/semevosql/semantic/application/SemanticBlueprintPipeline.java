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

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHistoryService;
import cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryInput;
import cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryContext;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlanningDecision;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlannerProfile;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService.PlanningRecall;
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Application pipeline for governed semantic planning.
 *
 * <p>Each stage has an immutable output so candidate-recall failures, LLM binding failures and
 * deterministic plan-resolution failures remain attributable instead of collapsing into one
 * generic NL2SQL error.
 */
@Service
public class SemanticBlueprintPipeline {

	private final SemanticCatalogApplicationService catalogService;

	private final SemanticBlueprintGenerationService llmPlanningService;

	private final ValidatedQueryExampleService queryExampleService;

    private final QueryCaseHistoryService history;

    private PersonalDefinitionCatalogOverlay personalOverlay;
    private SemanticCatalogReadService catalogReader;
    @org.springframework.beans.factory.annotation.Autowired
    public void personalCatalog(PersonalDefinitionCatalogOverlay personalOverlay,SemanticCatalogReadService catalogReader) {
        this.personalOverlay=personalOverlay;this.catalogReader=catalogReader;
    }

    private cn.lgs.semevosql.clarification.PersonalDefinitionRetrievalService personalRetrieval;
    private cn.lgs.semevosql.clarification.ProjectDefinitionCandidateService sharedSuggestions;
    private java.util.concurrent.Executor definitionRecallExecutor;
    @org.springframework.beans.factory.annotation.Autowired
    public void definitionRecall(cn.lgs.semevosql.clarification.PersonalDefinitionRetrievalService personal,
            cn.lgs.semevosql.clarification.ProjectDefinitionCandidateService shared,
            @org.springframework.beans.factory.annotation.Qualifier("semEvoSQLDefinitionRecallExecutor") java.util.concurrent.Executor executor) {
        this.personalRetrieval=personal;this.sharedSuggestions=shared;this.definitionRecallExecutor=executor;
    }

	public SemanticBlueprintPipeline(SemanticCatalogApplicationService catalogService,
			SemanticBlueprintGenerationService llmPlanningService, ValidatedQueryExampleService queryExampleService) {
        this(catalogService, llmPlanningService, queryExampleService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SemanticBlueprintPipeline(SemanticCatalogApplicationService catalogService,
            SemanticBlueprintGenerationService llmPlanningService, ValidatedQueryExampleService queryExampleService,
            QueryCaseHistoryService history) {
		this.catalogService = catalogService;
		this.llmPlanningService = llmPlanningService;
		this.queryExampleService = queryExampleService;
        this.history = history;
	}

    public HistoryInput historyInput(com.alibaba.cloud.ai.graph.OverAllState state) {
        return history == null ? null : history.input(state);
    }

    public HistoryContext historyContext(HistoryInput input) {
        return history == null || input == null ? HistoryContext.empty() : history.planningContext(input);
    }

	public PlanningResult plan(PlanningRequest request) {
		String planningId = UUID.randomUUID().toString();
		long started = System.nanoTime();
		if (request == null || request.projectId() == null || request.projectVersionId() == null
				|| !StringUtils.hasText(request.query())) {
			throw new SemanticPlanningRejectedException("INVALID_REQUEST", "Semantic planning request is incomplete");
		}

        var ownFuture = personalRetrieval == null ? java.util.concurrent.CompletableFuture.<List<SemanticBlueprint.BindingDependency>>completedFuture(List.of())
            : java.util.concurrent.CompletableFuture.supplyAsync(() -> personalRetrieval.retrieve(request.projectId(),request.principalId(),request.requestQuestion()),definitionRecallExecutor);
        var suggestionFuture = sharedSuggestions == null ? java.util.concurrent.CompletableFuture.<List<SemanticCandidateSet.ProjectSuggestion>>completedFuture(List.of())
            : java.util.concurrent.CompletableFuture.supplyAsync(() -> sharedSuggestions.retrieve(request.projectId(),request.projectVersionId(),request.principalId(),request.requestQuestion()),definitionRecallExecutor);
        long recallStarted = System.nanoTime();
        var historyFuture = history != null && request.historyInput() != null
            ? history.startPlanning(request.historyInput()) : null;
		PlanningRecall recall = catalogService.recallPlanning(request.projectId(), request.projectVersionId(),
				request.effectiveRetrievalQuery(), request.recallLimit());
		Set<String> candidateTables = new LinkedHashSet<>(recall.physicalTables());
		candidateTables.addAll(safe(request.additionalPhysicalTables()));
		long recallMs = elapsedMillis(recallStarted);

		long examplesStarted = System.nanoTime();
		QueryCaseHints historicalHints;
        HistoryContext historicalContext = historyFuture == null ? HistoryContext.empty() : historyFuture.join();
        if (historyFuture != null) {
            historicalHints = historicalContext.hints();
            candidateTables.addAll(llmPlanningService.physicalTablesForHistoricalModels(request.projectId(),
                request.projectVersionId(), historicalHints.modelCodes()));
        }
		else if (StringUtils.hasText(request.principalId())) {
			historicalHints = queryExampleService.recallHints(request.projectId(), request.projectVersionId(),
					request.catalogHash(), request.query(), request.contextHash(), request.principalId(), request.exampleLimit());
		}
		else if (StringUtils.hasText(request.contextHash())) {
			historicalHints = queryExampleService.recallHints(request.projectId(), request.projectVersionId(),
					request.catalogHash(), request.query(), request.contextHash(), request.exampleLimit());
		}
		else {
			historicalHints = queryExampleService.recallHints(request.projectId(), request.projectVersionId(),
					request.catalogHash(), request.query(), request.exampleLimit());
		}
		long exampleRecallMs = elapsedMillis(examplesStarted);
        candidateTables.addAll(llmPlanningService.physicalTablesForHistoricalModels(request.projectId(),
            request.projectVersionId(), request.previousTaskHints().modelCodes()));

        var references=new java.util.LinkedHashMap<String,SemanticBlueprint.BindingDependency>();
        java.util.stream.Stream.concat(request.personalDefinitions().stream(),ownFuture.join().stream()).forEach(b ->
            references.putIfAbsent(b.getSource()+":"+b.getSourceRecordId()+":"+b.getSourceRevision(),b));
        var personal=personalOverlay==null?PersonalDefinitionCatalogOverlay.Slice.empty()
            :personalOverlay.prepare(request.projectId(),request.projectVersionId(),request.principalId(),
                List.copyOf(references.values()),request.personalSelectionPending());
        var suggestions=suggestionFuture.join();
        Set<String> suggestionModels=suggestions.stream().map(SemanticCandidateSet.ProjectSuggestion::modelCode)
            .filter(StringUtils::hasText).collect(java.util.stream.Collectors.toSet());
        candidateTables.addAll(llmPlanningService.physicalTablesForHistoricalModels(request.projectId(),request.projectVersionId(),
            java.util.stream.Stream.concat(personal.modelCodes().stream(),suggestionModels.stream()).collect(java.util.stream.Collectors.toSet())));
        if (candidateTables.isEmpty()) {
            throw new SemanticPlanningRejectedException("RETRIEVAL_MISS", "No governed candidate table was recalled for semantic planning");
        }
        long candidateStarted = System.nanoTime();
        SemanticCandidateSet candidates = llmPlanningService.candidates(request.projectId(), request.projectVersionId(),
            candidateTables, recall.hits(), java.util.stream.Stream.concat(historicalHints.modelCodes().stream(),
                java.util.stream.Stream.concat(request.requiredHints() == null ? java.util.stream.Stream.empty() : request.requiredHints().modelCodes().stream(),java.util.stream.Stream.concat(personal.modelCodes().stream(),suggestionModels.stream()))).toList());
        candidates=personal.candidates(candidates).withProjectSuggestions(suggestions);
        if (candidates.empty()) throw new SemanticPlanningRejectedException("CANDIDATE_BUILD_EMPTY", "Candidate recall did not resolve to an enabled semantic model");
        long candidateMs = elapsedMillis(candidateStarted);

		long bindingStarted = System.nanoTime();
        if (historyFuture != null) {
            // Catalog retrieval ran in parallel; recheck authority after it finishes and before passing old SQL to a model.
            var fresh = history.existingContext(request.historyInput());
            historicalContext = new HistoryContext(fresh.prompt(),fresh.hints(),historicalContext.snapshotReferences());
            historicalHints = historicalContext.hints();
            history.recordConsumption(request.historyInput(),"SEMANTIC_BLUEPRINT",historicalContext);
        }
		PlanningDecision planningDecision = !request.requestQuestion().equals(request.query())
                || !request.currentUserMessage().equals(request.requestQuestion()) || !request.definitionConfirmations().isEmpty()
                || !request.previousTaskHints().emptyHints()
                ? llmPlanningService.planDecision(new SemanticPlanningInput(request.requestQuestion(), request.query(), request.currentUserMessage(), request.definitionConfirmations(), request.previousTaskHints()),
                    candidates, recall.hits(), historicalHints, request.requiredHints(), PlannerProfile.CONFIGURED,
                    request.runDeadlineEpochMillis(), historicalContext.prompt())
                : historyFuture != null
                ? llmPlanningService.planDecision(request.query(), candidates, recall.hits(), historicalHints,
                    request.requiredHints(), PlannerProfile.CONFIGURED, request.runDeadlineEpochMillis(), historicalContext.prompt())
                : request.runDeadlineEpochMillis() == null
				? llmPlanningService.planDecision(request.query(), candidates, recall.hits(), historicalHints,
						request.requiredHints(), PlannerProfile.CONFIGURED)
				: llmPlanningService.planDecision(request.query(), candidates, recall.hits(), historicalHints,
						request.requiredHints(), PlannerProfile.CONFIGURED, request.runDeadlineEpochMillis());
		if (planningDecision == null) {
			SemanticPlanningOutcome compatibilityOutcome = llmPlanningService.planOutcome(request.query(), candidates,
					recall.hits(), historicalHints, request.requiredHints());
			planningDecision = new PlanningDecision(compatibilityOutcome, List.of());
		}
		SemanticPlanningOutcome outcome = planningDecision.outcome();
		if (outcome instanceof SemanticPlanningOutcome.ClarificationRequired clarification) {
			throw new SemanticPlanningClarificationRequiredException(clarification,planningDecision.modelCalls());
		}
		if (outcome instanceof SemanticPlanningOutcome.Rejected rejected) {
			throw new SemanticPlanningRejectedException(rejected.errorCode(), rejected.reason(),planningDecision.modelCalls());
		}
		SemanticPlanningOutcome.Resolved resolved = (SemanticPlanningOutcome.Resolved) outcome;
		QueryCaseHints binding = resolved.binding();
		ComputationIntent computationIntent = resolved.computationIntent();
        List<Long> personalIds=resolved.personalDefinitionIds();
        var resultContract=resolved.resultContract();
		long bindingMs = elapsedMillis(bindingStarted);

		long resolutionStarted = System.nanoTime();
		Collection<String> materializationTables = materializationTables(candidateTables, candidates, binding);
		SemanticBlueprint plan = null;
		String resolutionError = null;
		try {
			plan = materialize(request,personal,materializationTables,binding);
			reconcileComputationIntent(plan, computationIntent);
            requireResolvedTimeFilter(plan, candidates);
			if (!plan.isExecutable()) {
				resolutionError = resolutionFailureMessage(String.join("; ", plan.getValidationErrors()));
			}
		}
		catch (IllegalArgumentException resolutionFailure) {
			resolutionError = resolutionFailureMessage(resolutionFailure.getMessage());
		}
		if (StringUtils.hasText(resolutionError)) {
			PlanningDecision repairDecision = planningDecision.planningSession() == null
					? llmPlanningService.repairAfterResolutionFailure(request.query(), candidates, recall.hits(), historicalHints,
							request.requiredHints(), PlannerProfile.CONFIGURED, resolutionError)
					: llmPlanningService.repairAfterResolutionFailure(request.query(), candidates, recall.hits(), historicalHints,
							request.requiredHints(), PlannerProfile.CONFIGURED, resolutionError,
							planningDecision.planningSession());
			List<ModelCallResult> combinedCalls = new ArrayList<>(planningDecision.modelCalls());
			combinedCalls.addAll(repairDecision.modelCalls());
			planningDecision = new PlanningDecision(repairDecision.outcome(), List.copyOf(combinedCalls),
					repairDecision.planningSession());
			SemanticPlanningOutcome repairedOutcome = repairDecision.outcome();
			if (repairedOutcome instanceof SemanticPlanningOutcome.ClarificationRequired clarification) {
				throw new SemanticPlanningClarificationRequiredException(clarification,planningDecision.modelCalls());
			}
			if (repairedOutcome instanceof SemanticPlanningOutcome.Rejected rejected) {
				throw new SemanticPlanningRejectedException(rejected.errorCode(), rejected.reason(),planningDecision.modelCalls());
			}
			SemanticPlanningOutcome.Resolved repaired = (SemanticPlanningOutcome.Resolved) repairedOutcome;
			binding = repaired.binding();
			computationIntent = repaired.computationIntent();
            personalIds=repaired.personalDefinitionIds();
            resultContract=repaired.resultContract();
			materializationTables = materializationTables(candidateTables, candidates, binding);
			try {
				plan = materialize(request,personal,materializationTables,binding);
				reconcileComputationIntent(plan, computationIntent);
                requireResolvedTimeFilter(plan, candidates);
			}
			catch (IllegalArgumentException resolutionFailure) {
				throw new SemanticPlanningRejectedException("PLAN_RESOLUTION_ERROR",
						"Resolved governed semantic plan remains non-executable after semantic repair: "
								+ resolutionFailureMessage(resolutionFailure.getMessage()),planningDecision.modelCalls());
			}
			if (!plan.isExecutable()) {
				throw new SemanticPlanningRejectedException("PLAN_RESOLUTION_ERROR",
						"Resolved governed semantic plan remains non-executable after semantic repair: "
								+ String.join("; ", plan.getValidationErrors()),planningDecision.modelCalls());
			}
		}
		plan.setBindingDependencies(personal.selected(binding,personalIds));
        SemanticResultContractResolver.apply(plan,resultContract,request.definitionConfirmations());
        if(plan.getBindingDependencies().stream().anyMatch(b->"TEXT_DEFINITION".equals(b.getAssetType())&&b.getRepresentationCode()==null))
            plan.setCompilerMode("CONSTRAINED_GENERATION");
        long resolutionMs = elapsedMillis(resolutionStarted);
		int modelCallCount = planningDecision.modelCalls().size();
		boolean nativeReasoningUsed = planningDecision.modelCalls()
			.stream()
			.anyMatch(call -> call.invocationProfile().reasoningApplied());
		PlanningTrace trace = new PlanningTrace(planningId, candidates.catalogHash(), candidateTables, recall.hits().size(),
				candidates.models().size(), candidates.metrics().size(), candidates.dimensions().size(), recallMs, candidateMs,
				exampleRecallMs, bindingMs, resolutionMs, elapsedMillis(started), modelCallCount, nativeReasoningUsed);
		return new PlanningResult(plan, candidates, historicalHints, binding, trace, historicalContext);
	}

    private SemanticBlueprint materialize(PlanningRequest request,PersonalDefinitionCatalogOverlay.Slice personal,
            Collection<String> tables,QueryCaseHints binding) {
        if(personal.metrics().isEmpty())return catalogService.buildBlueprint(request.projectId(),request.projectVersionId(),request.query(),tables,binding);
        return catalogReader.readCurrent(request.projectId(),request.projectVersionId(),scope->{
            var models=binding.modelCodes().isEmpty()?scope.resolvePhysicalModels(tables):binding.modelCodes();
            return catalogService.buildBlueprint(request.projectId(),request.projectVersionId(),personal.catalog(scope.models(models)),request.query(),tables,binding);
        });
    }

    static void requireResolvedTimeFilter(SemanticBlueprint plan, SemanticCandidateSet candidates) {
        if (plan == null || !plan.getComputationIntent().requires(Capability.TIME_FILTER)) return;
        var range = plan.getTimeRange();
        if (range != null && (StringUtils.hasText(range.getRelativeExpression())
                || StringUtils.hasText(range.getStartInclusive()) && StringUtils.hasText(range.getEndExclusive()))) return;
        boolean boundFilter = plan.getFilters().stream().anyMatch(filter -> candidates.timeColumns().stream()
            .anyMatch(column -> java.util.Objects.equals(column.getModelCode(), filter.getModelCode())
                && java.util.Objects.equals(column.getColumnName(), filter.getColumnName())));
        if (!boundFilter)
            throw new IllegalArgumentException("TIME_FILTER was requested but no governed observation predicate was resolved. "
                + "Bind the requested time axis and an explicit startInclusive/endExclusive interval, "
                + "or a governed time-column filter. Grouping by time alone does not satisfy TIME_FILTER.");
    }

	static void reconcileComputationIntent(SemanticBlueprint plan, ComputationIntent computationIntent) {
		if (plan == null) {
			return;
		}
		ComputationIntent effective = computationIntent == null ? ComputationIntent.empty() : computationIntent;
		plan.setComputationIntent(effective);
        reconcileOrdering(plan, effective);
        reconcileNullReplacement(plan, effective);
        var pagination=effective.requirements().stream().filter(r->r.capability()==Capability.OFFSET).toList();
        if(pagination.size()>1) throw new IllegalArgumentException("Conflicting pagination requirements");
        if(effective.requires(Capability.OFFSET) && pagination.isEmpty())
            throw new IllegalArgumentException("OFFSET requires a confirmed skip count");
        if(!pagination.isEmpty()) {
            plan.setOffset(pagination.get(0).offset());
            var sizes=effective.requirements().stream().filter(r->r.capability()==Capability.LIMIT && r.limit()!=null)
                .map(cn.lgs.semevosql.semantic.domain.ComputationIntent.Requirement::limit).distinct().toList();
            if(sizes.size()>1) throw new IllegalArgumentException("Conflicting page sizes");
            if(!sizes.isEmpty()) {
                plan.setLimit(sizes.get(0));
                if(plan.getExpectedResult()!=null) plan.getExpectedResult().setMaxRows(sizes.get(0));
            }
        }
        cn.lgs.semevosql.semantic.domain.QueryPagination.validate(plan);
		SemanticBlueprint.TimeRangeSelection timeRange = plan.getTimeRange();
		if (timeRange != null && StringUtils.hasText(timeRange.getRelativeExpression())
				&& effective.requires(Capability.PERIOD_COMPARISON) && !effective.requires(Capability.TIME_FILTER)) {
			// Relative period phrases can describe the baseline of a comparison rather than the observation range.
			// The semantic planner owns that distinction through TIME_FILTER; the lexical enricher must not turn a
			// comparison baseline into a row filter after planning has explicitly classified it as comparison-only.
			plan.setTimeRange(null);
		}
	}

    private static void reconcileOrdering(SemanticBlueprint plan, ComputationIntent intent) {
        var direct = intent.requirements().stream().filter(r -> r.capability() == Capability.ORDERING
            && (r.basis() == null || "ORDERING".equals(r.basis()))
            && (r.metricCode() != null || r.dimensionCode() != null)).toList();
        if (direct.isEmpty()) return; // Historical capability-only snapshots keep their already frozen order.
        List<SemanticBlueprint.OrderSelection> order = new ArrayList<>();
        for (var requirement : direct) {
            String target = requirement.dimensionCode() == null ? requirement.metricCode() : requirement.dimensionCode();
            boolean selected = requirement.dimensionCode() == null
                ? plan.getMetrics().stream().anyMatch(m -> target.equals(m.getMetricCode()))
                : plan.getDimensions().stream().anyMatch(d -> target.equals(d.getDimensionCode()));
            boolean projected = plan.getProjections().stream().anyMatch(p -> target.equals(p.getAlias())
                && (requirement.dimensionCode() == null ? "METRIC" : "DIMENSION").equals(p.getProjectionType()));
            if (!selected || !projected)
                throw new IllegalArgumentException("Ordering target must be a selected governed projection: " + target);
            String direction = switch (java.util.Objects.toString(requirement.mode(), "")) {
                case "LOWEST" -> "ASC";
                case "HIGHEST" -> "DESC";
                default -> throw new IllegalArgumentException("Direct ordering requires LOWEST or HIGHEST mode");
            };
            if (order.stream().anyMatch(o -> target.equals(o.getExpression())))
                throw new IllegalArgumentException("Conflicting ordering requirements for " + target);
            order.add(SemanticBlueprint.OrderSelection.builder().expression(target).direction(direction).nulls("LAST").build());
        }
        plan.setOrderBy(List.copyOf(order));
    }

    private static void reconcileNullReplacement(SemanticBlueprint plan,ComputationIntent intent) {
        var replacements=intent.requirements().stream().filter(r->r.capability()==Capability.NULL_REPLACEMENT).toList();
        if(intent.requires(Capability.NULL_REPLACEMENT) && replacements.isEmpty())
            throw new IllegalArgumentException("NULL_REPLACEMENT requires an explicit governed dimension and display label");
        Set<String> seen=new java.util.HashSet<>();
        for(var requirement:replacements) {
            String code=requirement.dimensionCode();
            if(!seen.add(code))throw new IllegalArgumentException("Conflicting null labels for "+code);
            var dimension=plan.getDimensions().stream().filter(d->code.equals(d.getDimensionCode())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Null label target must be selected: "+code));
            var projections=plan.getProjections().stream().filter(p->code.equals(p.getAlias()) && "DIMENSION".equals(p.getProjectionType())).toList();
            if(projections.size()!=1)throw new IllegalArgumentException("Null label target must be one governed projection: "+code);
            String original=StringUtils.hasText(dimension.getExpression())?dimension.getExpression():dimension.getColumnName();
            if(!StringUtils.hasText(original))throw new IllegalArgumentException("Null label target lacks a bound expression");
            // Framework AST renders literal data; neither the model nor caller supplies executable SQL.
            String literal=com.alibaba.druid.sql.SQLUtils.toSQLString(new com.alibaba.druid.sql.ast.expr.SQLCharExpr(requirement.nullReplacement()),com.alibaba.druid.DbType.postgresql);
            String expression="COALESCE("+original+", "+literal+")";
            projections.get(0).setExpression(expression);
            plan.getGroupBy().stream().filter(g->code.equals(g.getAlias())).forEach(g->g.setExpression(expression));
        }
    }

	private long elapsedMillis(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
	}

	private String resolutionFailureMessage(String message) {
		return StringUtils.hasText(message) ? message : "Resolved governed semantic plan is non-executable";
	}

	private <T> Collection<T> safe(Collection<T> value) {
		return value == null ? List.of() : value;
	}

	private List<String> materializationTables(Collection<String> recalledTables, SemanticCandidateSet candidates,
			QueryCaseHints binding) {
		LinkedHashSet<String> tables = new LinkedHashSet<>(safe(recalledTables));
		if (candidates == null || binding == null || binding.modelCodes().isEmpty()) {
			return List.copyOf(tables);
		}
		Set<String> boundModelCodes = binding.modelCodes();
		candidates.models()
			.stream()
			.filter(model -> boundModelCodes.contains(model.getModelCode()))
			.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
			.filter(StringUtils::hasText)
			.forEach(tables::add);
		return List.copyOf(tables);
	}

	public record PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
			String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
			int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis, HistoryInput historyInput,
            List<SemanticBlueprint.BindingDependency> personalDefinitions, String requestQuestion,
            String currentUserMessage, List<SemanticPlanningInput.DefinitionConfirmation> definitionConfirmations,
            boolean personalSelectionPending, QueryCaseHints previousTaskHints) {
        public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
                String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
                int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis, HistoryInput historyInput,
                List<SemanticBlueprint.BindingDependency> personalDefinitions, String requestQuestion,
                String currentUserMessage, List<SemanticPlanningInput.DefinitionConfirmation> definitionConfirmations,
                boolean personalSelectionPending) {
            this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
                recallLimit, exampleLimit, retrievalQuery, principalId, runDeadlineEpochMillis, historyInput,
                personalDefinitions, requestQuestion, currentUserMessage, definitionConfirmations,
                personalSelectionPending, QueryCaseHints.empty());
        }
        public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
                String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
                int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis, HistoryInput historyInput,
                List<SemanticBlueprint.BindingDependency> personalDefinitions, String requestQuestion,
                String currentUserMessage, List<SemanticPlanningInput.DefinitionConfirmation> definitionConfirmations) {
            this(projectId,projectVersionId,catalogHash,query,contextHash,additionalPhysicalTables,requiredHints,recallLimit,
                exampleLimit,retrievalQuery,principalId,runDeadlineEpochMillis,historyInput,personalDefinitions,requestQuestion,
                currentUserMessage,definitionConfirmations,false);
        }
        public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
                String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
                int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis, HistoryInput historyInput,
                List<SemanticBlueprint.BindingDependency> personalDefinitions, String requestQuestion) {
            this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
                recallLimit, exampleLimit, retrievalQuery, principalId, runDeadlineEpochMillis, historyInput,
                personalDefinitions, requestQuestion, requestQuestion, List.of());
        }
        public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
                String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
                int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis, HistoryInput historyInput,
                List<SemanticBlueprint.BindingDependency> personalDefinitions) {
            this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
                recallLimit, exampleLimit, retrievalQuery, principalId, runDeadlineEpochMillis, historyInput, personalDefinitions, query);
        }
        public PlanningRequest(Long projectId,Long projectVersionId,String catalogHash,String query,String contextHash,
                Collection<String> additionalPhysicalTables,QueryCaseHints requiredHints,int recallLimit,int exampleLimit,
                String retrievalQuery,String principalId,Long runDeadlineEpochMillis,HistoryInput historyInput) {
            this(projectId,projectVersionId,catalogHash,query,contextHash,additionalPhysicalTables,requiredHints,recallLimit,exampleLimit,
                retrievalQuery,principalId,runDeadlineEpochMillis,historyInput,List.of());
        }
        public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
                String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
                int exampleLimit, String retrievalQuery, String principalId, Long runDeadlineEpochMillis) {
            this(projectId,projectVersionId,catalogHash,query,contextHash,additionalPhysicalTables,requiredHints,
                recallLimit,exampleLimit,retrievalQuery,principalId,runDeadlineEpochMillis,null);
        }
		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
				int exampleLimit) {
			this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
					recallLimit, exampleLimit, null, null, null);
		}

		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
				int exampleLimit, String retrievalQuery, String principalId) {
			this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
					recallLimit, exampleLimit, retrievalQuery, principalId, null);
		}

		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
				int exampleLimit, String retrievalQuery) {
			this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
					recallLimit, exampleLimit, retrievalQuery, null);
		}

		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit, int exampleLimit) {
			this(projectId, projectVersionId, catalogHash, query, null, additionalPhysicalTables, requiredHints, recallLimit,
					exampleLimit, null, null);
		}

		public PlanningRequest {
            requestQuestion = StringUtils.hasText(requestQuestion) ? requestQuestion : query;
            currentUserMessage = StringUtils.hasText(currentUserMessage) ? currentUserMessage : requestQuestion;
            definitionConfirmations = List.copyOf(definitionConfirmations == null ? List.of() : definitionConfirmations);
			personalDefinitions=List.copyOf(personalDefinitions==null?List.of():personalDefinitions);
            additionalPhysicalTables = List.copyOf(additionalPhysicalTables == null ? List.of() : additionalPhysicalTables);
			requiredHints = requiredHints == null ? QueryCaseHints.empty() : requiredHints;
            previousTaskHints = previousTaskHints == null ? QueryCaseHints.empty() : previousTaskHints;
			recallLimit = Math.max(1, recallLimit);
			exampleLimit = Math.max(1, exampleLimit);
		}

		public String effectiveRetrievalQuery() {
			return StringUtils.hasText(retrievalQuery) ? retrievalQuery.trim() : query;
		}
	}

	public record PlanningResult(SemanticBlueprint plan, SemanticCandidateSet candidateSet,
			QueryCaseHints historicalHints, QueryCaseHints binding, PlanningTrace trace, HistoryContext historyContext) {
        public PlanningResult(SemanticBlueprint plan, SemanticCandidateSet candidateSet,
                QueryCaseHints historicalHints, QueryCaseHints binding, PlanningTrace trace) {
            this(plan,candidateSet,historicalHints,binding,trace,HistoryContext.empty());
        }
	}

	public record PlanningTrace(String planningId, String catalogHash, Set<String> candidatePhysicalTables,
			int retrievalHitCount, int candidateModelCount, int candidateMetricCount, int candidateDimensionCount,
			long recallMs, long candidateBuildMs, long historicalExampleRecallMs, long modelBindingMs,
			long planResolutionMs, long totalMs, int modelCallCount, boolean nativeReasoningUsed) {
		public PlanningTrace(String planningId, String catalogHash, Set<String> candidatePhysicalTables,
				int retrievalHitCount, int candidateModelCount, int candidateMetricCount, int candidateDimensionCount,
				long recallMs, long candidateBuildMs, long historicalExampleRecallMs, long modelBindingMs,
				long planResolutionMs, long totalMs) {
			this(planningId, catalogHash, candidatePhysicalTables, retrievalHitCount, candidateModelCount,
					candidateMetricCount, candidateDimensionCount, recallMs, candidateBuildMs, historicalExampleRecallMs,
					modelBindingMs, planResolutionMs, totalMs, 0, false);
		}

		public PlanningTrace {
			candidatePhysicalTables = Set.copyOf(candidatePhysicalTables == null ? Set.of() : candidatePhysicalTables);
		}
	}
}
