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
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.PlannerReasoningProperties;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.CrossSourceRelationship;
import cn.lgs.semevosql.service.llm.LlmInvocationOptions;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.FilterBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.ResultCompositionHint;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Requirement;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet.RetrievalEvidence;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Governed LLM semantic planning boundary.
 *
 * <p>The model is allowed to choose only already-published Semantic Catalog assets. It never
 * supplies SQL, metric formulae, join predicates, datasource identifiers or arbitrary columns.
 * SemEvoSQL validates every selected code against the candidate Catalog slice and then lets the
 * deterministic semantic resolver expand those codes into the authoritative Semantic Blueprint.
 */
@Service
@Slf4j
public class SemanticBlueprintGenerationService {

	private static final int MAX_CANDIDATE_MODELS = 24;

	private static final int RELATIONSHIP_NEIGHBORHOOD_DEPTH = 2;

	private static final Set<String> SUPPORTED_LITERAL_FILTER_OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT",
			"LTE", "IN", "IS_NULL", "IS_NOT_NULL");

	private static final Set<String> SUPPORTED_TIME_GROUP_GRANULARITIES = Set.of("DAY", "MONTH", "YEAR");

	private static final Pattern SCALAR_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private static final Pattern COMPUTATION_REQUIREMENT_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

	private static final Pattern SCALAR_BINARY_EXPRESSION = Pattern
		.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*([+-])\\s*([A-Za-z_][A-Za-z0-9_]*)");

	private static final Set<String> QUERY_SELECTABLE_RULE_TYPES = Set.of("BUSINESS_RULE", "BUSINESS_FILTER");

	private static final Set<String> MANDATORY_GOVERNANCE_RULE_TYPES = Set.of("MANDATORY_FILTER", "ROW_FILTER",
			"SECURITY_FILTER", "DATA_SCOPE", "REQUIRED_PREDICATE");

	private static final String SYSTEM_PROMPT = """
			You are SemEvoSQL's governed semantic planner.
			Your job is semantic binding, not SQL generation. Select the smallest sufficient set of already-published
			Semantic Catalog assets that exactly represent the user's request.

			STRICT RULES:
			1. Use only codes and enum values present in the supplied candidates. Never invent a model, metric,
			   dimension, rule, relationship, grain, enum value, column, formula, join condition, datasource or SQL.
			2. metricCodes contains only measures actually requested by the user. Prefer the most specific business
			   meaning; do not choose a nearby metric merely because its name is similar.
			3. dimensionCodes contains only fields the user wants returned/grouped as dimensions or entity labels.
			   A field mentioned only to filter rows MUST NOT also become a dimension.
			4. enumBindings represents categorical filters whose value is one of the supplied published enum values.
			5. filters represents non-enum predicates. Use supplied filterableColumns; a supplied timeColumn may also be used
			   as a filter only when the current question explicitly names that governed time field. Copy literal values from
			   the current question; never invent a literal. IS_NULL / IS_NOT_NULL have no value field. Do not duplicate an
			   enumBinding as a filter.
			6. ruleCodes contains only supplied querySelectableRules implied by the user's business wording. planningPolicies
			   and mandatoryGovernanceRules are constraints, never selectable ruleCodes. When an exact supplied business rule
			   represents the user's business wording, select that ruleCode instead of reconstructing the same business concept
			   from a lower-level enumBinding. Do not emit a duplicate enumBinding for a predicate expanded by that selected rule.
			7. timeBinding is only a governed binding for one explicit date/range/relative-time predicate, or for one simple
			   DAY/MONTH/YEAR grouping that can be represented directly. Supplied timeColumns are governed and filter-approved.
			   Do NOT try to encode every use of time in timeBinding. Multiple time axes, WEEK/QUARTER/custom buckets, CTEs,
			   LAG/LEAD, window PARTITION/ORDER, period comparison and other SQL execution structure belong to the downstream
			   execution Planner/SQL and are NOT missing semantic assets. Never return UNRESOLVABLE merely because such SQL
			   operations are not represented by Catalog codes. When a query has several time fields but only one explicit
			   range predicate, bind the field owning that range; leave the other grouping/window usages to SQL.
			   IMPORTANT: the identity of the BUSINESS TIME AXIS is semantic even when bucket/window syntax is execution-owned.
			   Never silently drop a requested temporal grouping/trend. If the user asks to group/trend "by time/date" without
			   identifying which business time field, and two or more supplied governed time dimensions are materially plausible
			   (for example created_at vs paid_at vs completed_at), you MUST return NEEDS_CLARIFICATION and offer those supplied
			   time-dimension assets. The downstream Planner may choose HOW to bucket/order a selected time axis; it may not guess
			   WHICH business time axis the user meant.
			8. relationshipCodes contains only published relationships necessary to connect the selected semantic assets.
			   Do NOT select a row-level relationship merely because metrics come from multiple models.
			9. resultComposition is an execution-composition declaration, not a business asset. Use it only when the user requests
			   independent scalar aggregates from separate selected models/sources that must be returned together without a row-level
			   relationship. Set type=SCALAR and leave relationshipCodes empty. calculationExpression is optional; when requested,
			   it may use only selected metric codes in exactly one validated binary + or - expression, optionally wrapped in ABS,
			   with an output alias assignment (examples of syntax only: delta=left_metric-right_metric,
			   gap=ABS(left_metric-right_metric)). For an undirected difference/magnitude, use ABS; use signed subtraction only
			   when the user explicitly asks for a directional A-minus-B comparison. Never invent metric codes or arbitrary
			   functions/operators.
			10. grainCodes contains only published grains explicitly required for the requested result semantics.
			11. Do not add context that the user did not ask for. Minimal sufficient plan wins.
			12. historicalHints are non-authoritative prior experience and may be reused only when the current question
			    and current Catalog candidates independently support them. requiredHints are explicit user/runtime
			    constraints and MUST be preserved exactly when present.
			13. Return status=NEEDS_CLARIFICATION only when two or more supplied governed candidates represent materially
			    different plausible meanings and the current question/requiredHints cannot distinguish them. Clarification
			    options must reference only supplied candidate asset codes. Do not use clarification to hide a retrieval miss.
			14. Return status=UNRESOLVABLE only when a required BUSINESS meaning (metric/definition/model/relation/rule) is absent
			    from the governed candidates. Missing SQL operators, bucket granularities, window functions or multi-stage
			    computation are execution concerns and must never by themselves make semantic planning UNRESOLVABLE.
			15. Final multi-model consistency check: if selected metrics belong to more than one model and relationshipCodes is
			    empty, a RESOLVED response is valid only when the request is for independent scalar aggregates and
			    resultComposition.type=SCALAR. Never return a relationship-free multi-model RESOLVED selection with
			    resultComposition=null. Conversely, never invent a row-level relationship merely to satisfy this check.
			16. computationCapabilities describes WHAT COMPUTATION the requested answer requires, never HOW SQL should be written.
			    TIME_FILTER means the user's requested observation/output rows are explicitly bounded by a time predicate.
			    A reference period used only as the baseline of PERIOD_COMPARISON is not itself a TIME_FILTER. If the user asks
			    for one bounded period and also compares it with another period, include both TIME_FILTER and PERIOD_COMPARISON;
			    if the user asks for a sequence of periods each compared with its predecessor, use PERIOD_COMPARISON without
			    inventing a relative observation filter merely from the comparison baseline.
			    Use only these capability names when required by the current request:
			    PROJECTION, FILTER, AGGREGATION, GROUPING, ORDERING, LIMIT, JOIN, TIME_FILTER, TIME_BUCKET,
			    CONDITIONAL_AGGREGATION, PERIOD_COMPARISON, WINDOW_ANALYTICS, PARTITION_RANKING,
			    MULTI_STAGE_AGGREGATION, SET_OPERATION, RECURSIVE_QUERY, COHORT_ANALYSIS, MULTI_SOURCE,
			    CROSS_SOURCE_MERGE, SCALAR_COMPOSITION. Include all capabilities materially required by the answer even when
			    the deterministic SQL generator may not implement them; generator capability is an execution concern.
			17. computationRequirements is optional and refines WHAT a capability means when capability names alone would lose
			    user-visible semantics. Each item may contain only capability plus these small semantic parameters: metricCode,
			    grain, mode, limit, scope, basis. metricCode must be one of metricCodes selected above. Examples of WHAT are
			    PERIOD_COMPARISON(metricCode=paid_amount, grain=MONTH, mode=PREVIOUS_PERIOD_RATE),
			    ORDERING(mode=HIGHEST, basis=PERIOD_COMPARISON), and LIMIT(limit=3, scope=GLOBAL, basis=ORDERING).
			    Use semantic modes such as HIGHEST/LOWEST rather than SQL ASC/DESC when expressing user-visible ranking intent.
			    Do NOT encode CTEs, LAG/LEAD calls, SQL expressions, aliases, window frames, subqueries, joins or other SQL
			    AST/physical structure. Omit a parameter when the user did not specify or require it. Requirement capability must
			    also appear in computationCapabilities.

			For a resolved request return exactly one JSON object and no Markdown:
			{
			  "status": "RESOLVED",
			  "metricCodes": ["published_metric_code"],
			  "dimensionCodes": ["published_dimension_code"],
			  "ruleCodes": ["published_rule_code"],
			  "relationshipCodes": ["published_relationship_code"],
			  "grainCodes": ["published_grain_code"],
			  "computationCapabilities": ["AGGREGATION","TIME_BUCKET"],
			  "computationRequirements": [
			    {"capability":"TIME_BUCKET","metricCode":"published_metric_code","grain":"MONTH"}
			  ],
			  "enumBindings": [
			    {"modelCode":"published_model_code","columnName":"published_column","valueCode":"published_value"}
			  ],
			  "filters": [
			    {"modelCode":"published_model_code","columnName":"published_filterable_column","operator":"EQ","value":"literal copied from question"},
			    {"modelCode":"published_model_code","columnName":"explicitly named nullable column","operator":"IS_NULL"}
			  ],
			  "timeBinding": {"modelCode":"published_model_code","columnName":"published_time_column","groupGranularity":null},
			  "resultComposition": null,
			  "confidence": 0.0
			}

			For an ambiguity return:
			{"status":"NEEDS_CLARIFICATION","clarification":{"issueType":"SEMANTIC_AMBIGUITY","question":"one concise business question","options":[{"code":"option-code","label":"business label","assetType":"METRIC","assetKey":"published_asset_code"}],"reason":"why the supplied candidates remain ambiguous"}}

			If the supplied governed candidates cannot represent the request return:
			{"status":"UNRESOLVABLE","reason":"which required governed meaning is absent"}
			""";

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticDocumentExtractionClient extractionClient;

	private final PlannerReasoningProperties reasoningProperties;

	private final SemanticPlanningProperties planningProperties;

	private final MultiSourcePolicyService multiSourcePolicyService;

	@Autowired
	public SemanticBlueprintGenerationService(SemanticCatalogRepository catalogRepository,
			SemanticDocumentExtractionClient extractionClient, PlannerReasoningProperties reasoningProperties,
			SemanticPlanningProperties planningProperties, MultiSourcePolicyService multiSourcePolicyService) {
		this.catalogRepository = catalogRepository;
		this.extractionClient = extractionClient;
		this.reasoningProperties = reasoningProperties;
		this.planningProperties = planningProperties;
		this.multiSourcePolicyService = multiSourcePolicyService;
	}

	SemanticBlueprintGenerationService(SemanticCatalogRepository catalogRepository,
			SemanticDocumentExtractionClient extractionClient) {
		this.catalogRepository = catalogRepository;
		this.extractionClient = extractionClient;
		this.reasoningProperties = new PlannerReasoningProperties();
		this.reasoningProperties.setEnabled(false);
		this.planningProperties = new SemanticPlanningProperties();
		this.multiSourcePolicyService = null;
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits) {
		return plan(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, QueryCaseHints.empty(),
				QueryCaseHints.empty());
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints) {
		return plan(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, historicalHints,
				QueryCaseHints.empty());
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		SemanticPlanningOutcome outcome = planOutcome(projectId, projectVersionId, query, selectedPhysicalTables,
				retrievalHits, historicalHints, requiredHints);
		if (outcome instanceof SemanticPlanningOutcome.Resolved resolved) {
			return resolved.binding();
		}
		if (outcome instanceof SemanticPlanningOutcome.ClarificationRequired clarification) {
			throw new SemanticPlanningClarificationRequiredException(clarification);
		}
		SemanticPlanningOutcome.Rejected rejected = (SemanticPlanningOutcome.Rejected) outcome;
		throw new SemanticPlanningRejectedException(rejected.errorCode(), rejected.reason());
	}

	public SemanticPlanningOutcome planOutcome(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		return planDecision(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, historicalHints,
				requiredHints, PlannerProfile.CONFIGURED).outcome();
	}

	public PlanningDecision planDecision(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints, PlannerProfile profile) {
		if (!StringUtils.hasText(query)) {
			return new PlanningDecision(
					new SemanticPlanningOutcome.Rejected("BLANK_QUERY", "Semantic planning query cannot be blank"), List.of());
		}
		SemanticCandidateSet candidates = candidates(projectId, projectVersionId, selectedPhysicalTables, retrievalHits);
		if (candidates.models().isEmpty()) {
			return new PlanningDecision(new SemanticPlanningOutcome.Rejected("NO_CANDIDATE_MODEL",
					"Semantic planner has no governed candidate models"), List.of());
		}
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, profile);
	}

	public SemanticPlanningOutcome planOutcome(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, PlannerProfile.CONFIGURED)
			.outcome();
	}

	public SemanticPlanningOutcome planOutcome(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile) {
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, profile).outcome();
	}

	public PlanningDecision planDecision(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile) {
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, profile, null);
	}

	public PlanningDecision planDecision(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile, Long runDeadlineEpochMillis) {
		PlanningSession session = PlanningSession.start(planningProperties, runDeadlineEpochMillis);
		String userPrompt = userPrompt(query, candidates, retrievalHits, historicalHints, requiredHints);
		QueryCaseHints historical = historicalHints == null ? QueryCaseHints.empty() : historicalHints;
		QueryCaseHints required = requiredHints == null ? QueryCaseHints.empty() : requiredHints;
		log.info(
				"Semantic planner prompt profile: systemChars={}, estimatedSystemTokens={}, userChars={}, "
						+ "estimatedUserTokens={}, candidateAssets={}, models={}, metrics={}, dimensions={}, "
						+ "historicalHintExamples={}, requiredHintExamples={}, retrievalHits={}",
				SYSTEM_PROMPT.length(), estimateTokens(SYSTEM_PROMPT), userPrompt.length(), estimateTokens(userPrompt),
				candidateAssetCount(candidates), candidates.models().size(), candidates.metrics().size(),
				candidates.dimensions().size(), historical.sourceExampleIds().size(), required.sourceExampleIds().size(),
				retrievalHits == null ? 0 : retrievalHits.size());
		List<ModelCallResult> calls = new ArrayList<>();
		ModelCallResult initialCall = completePlanner(profile, SYSTEM_PROMPT, userPrompt, session, false);
		calls.add(initialCall);
		String response = initialCall.response();
		try {
			SemanticPlanningOutcome explicit = explicitNonResolvedOutcome(response);
			if (explicit != null) {
				return new PlanningDecision(explicit, calls, session);
			}
			ParsedPlanningSelection selection = parseAndValidate(query, response, candidates, requiredHints);
			SemanticPlanningOutcome ambiguity = unresolvedTimeAxis(candidates, selection.binding(), selection.computationIntent());
			return new PlanningDecision(ambiguity == null
					? new SemanticPlanningOutcome.Resolved(selection.binding(), selection.computationIntent()) : ambiguity, calls,
					session);
		}
		catch (IllegalArgumentException firstFailure) {
			String repairPrompt = userPrompt + "\n\nYour previous response was rejected by SemEvoSQL: "
					+ safeError(firstFailure.getMessage())
					+ "\nReturn a corrected JSON object using only the supplied candidate codes.";
			ModelCallResult repairCall = completePlanner(profile, SYSTEM_PROMPT, repairPrompt, session, true);
			calls.add(repairCall);
			String repaired = repairCall.response();
			try {
				SemanticPlanningOutcome repairedExplicit = explicitNonResolvedOutcome(repaired);
				if (repairedExplicit != null) {
					return new PlanningDecision(repairedExplicit, calls, session);
				}
				ParsedPlanningSelection selection = parseAndValidate(query, repaired, candidates, requiredHints);
				SemanticPlanningOutcome ambiguity = unresolvedTimeAxis(candidates, selection.binding(), selection.computationIntent());
				return new PlanningDecision(ambiguity == null
						? new SemanticPlanningOutcome.Resolved(selection.binding(), selection.computationIntent()) : ambiguity, calls,
						session);
			}
			catch (IllegalArgumentException finalFailure) {
				return new PlanningDecision(new SemanticPlanningOutcome.Rejected("INVALID_GOVERNED_SELECTION",
						safeError(finalFailure.getMessage())), calls, session);
			}
		}
	}

	private int estimateTokens(String text) {
		return Math.max(1, (text == null ? 0 : text.length() + 3) / 4);
	}

	private int candidateAssetCount(SemanticCandidateSet candidates) {
		return candidates.models().size() + candidates.metrics().size() + candidates.dimensions().size()
				+ candidates.enumValues().size() + candidates.querySelectableRules().size()
				+ candidates.mandatoryGovernanceRules().size() + candidates.planningPolicies().size()
				+ candidates.relationships().size() + candidates.grains().size() + candidates.timeColumns().size()
				+ candidates.filterableColumns().size();
	}

	/**
	 * Gives the semantic planner one governed correction opportunity when a syntactically valid binding cannot be
	 * materialized into an executable Semantic Blueprint. The deterministic resolver remains authoritative: this method
	 * only asks the model to reconsider its supplied candidate selection and never infers a relationship/composition.
	 */
	public PlanningDecision repairAfterResolutionFailure(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile, String rejectionReason) {
		return repairAfterResolutionFailure(query, candidates, retrievalHits, historicalHints, requiredHints, profile,
				rejectionReason, PlanningSession.start(planningProperties, null));
	}

	public PlanningDecision repairAfterResolutionFailure(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile, String rejectionReason, PlanningSession planningSession) {
		PlanningSession session = planningSession == null ? PlanningSession.start(planningProperties, null) : planningSession;
		String userPrompt = userPrompt(query, candidates, retrievalHits, historicalHints, requiredHints);
		String repairPrompt = userPrompt + "\n\nA previous syntactically valid RESOLVED selection was rejected by SemEvoSQL's "
				+ "deterministic governed plan resolver: " + safeError(rejectionReason)
				+ "\nReconsider the supplied candidate selection. Correct relationshipCodes/resultComposition or other governed "
				+ "bindings only when justified by the request and supplied candidates. Do not invent a relationship, metric, "
				+ "policy, or business meaning. Return exactly one corrected JSON object.";
		ModelCallResult call = completePlanner(profile, SYSTEM_PROMPT, repairPrompt, session, true);
		List<ModelCallResult> calls = List.of(call);
		String response = call.response();
		try {
			SemanticPlanningOutcome explicit = explicitNonResolvedOutcome(response);
			if (explicit != null) {
				return new PlanningDecision(explicit, calls, session);
			}
			ParsedPlanningSelection selection = parseAndValidate(query, response, candidates, requiredHints);
			SemanticPlanningOutcome ambiguity = unresolvedTimeAxis(candidates, selection.binding(), selection.computationIntent());
			return new PlanningDecision(ambiguity == null
					? new SemanticPlanningOutcome.Resolved(selection.binding(), selection.computationIntent()) : ambiguity, calls,
					session);
		}
		catch (IllegalArgumentException failure) {
			return new PlanningDecision(new SemanticPlanningOutcome.Rejected("INVALID_GOVERNED_SELECTION",
					safeError(failure.getMessage())), calls, session);
		}
	}

	static SemanticPlanningOutcome unresolvedTimeAxis(SemanticCandidateSet candidates, QueryCaseHints binding,
			ComputationIntent computationIntent) {
		if (candidates == null || binding == null || computationIntent == null
				|| !computationIntent.requiresExplicitTimeAxis()) {
			return null;
		}
		if (binding.timeBinding() != null) {
			return null;
		}
		Set<String> governedMetricTimeAxes = candidates.metrics()
			.stream()
			.filter(metric -> binding.metricCodes().contains(metric.getMetricCode()))
			.filter(metric -> StringUtils.hasText(metric.getTimeColumn()))
			.map(metric -> metric.getModelCode() + "::" + metric.getTimeColumn())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (governedMetricTimeAxes.size() == 1) {
			return null;
		}
		List<SemanticCatalogSnapshot.Dimension> plausible = candidates.dimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> "TIME".equalsIgnoreCase(Objects.toString(dimension.getDimensionType(), "")))
			.filter(dimension -> StringUtils.hasText(dimension.getDimensionCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Dimension::getModelCode)
				.thenComparing(SemanticCatalogSnapshot.Dimension::getDimensionCode))
			.toList();
		if (plausible.size() < 2) {
			return null;
		}
		List<SemanticPlanningOutcome.Option> options = plausible.stream()
			.limit(8)
			.map(dimension -> new SemanticPlanningOutcome.Option("time-axis:" + dimension.getDimensionCode(),
					timeAxisLabel(dimension), "DIMENSION", dimension.getDimensionCode()))
			.toList();
		return new SemanticPlanningOutcome.ClarificationRequired("SEMANTIC_AMBIGUITY", "你希望使用哪个业务时间字段？", options,
				"The requested computation requires a business time axis, but multiple governed time dimensions are plausible.");
	}

	private static String timeAxisLabel(SemanticCatalogSnapshot.Dimension dimension) {
		String label = StringUtils.hasText(dimension.getBusinessName()) ? dimension.getBusinessName()
				: StringUtils.hasText(dimension.getColumnName()) ? dimension.getColumnName() : dimension.getDimensionCode();
		return StringUtils.hasText(dimension.getModelCode()) ? label + " (" + dimension.getModelCode() + ")" : label;
	}

	private ModelCallResult completePlanner(PlannerProfile profile, String systemPrompt, String userPrompt,
			PlanningSession session, boolean repair) {
		PlannerProfile effectiveProfile = profile == null ? PlannerProfile.CONFIGURED : profile;
		Duration callBudget = session.nextCallBudget(repair);
		return switch (effectiveProfile) {
			case CONFIGURED -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt,
					callBudget);
			case BASELINE -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt,
					LlmInvocationOptions.none(), callBudget);
			case REASONING -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt,
					new LlmInvocationOptions(reasoningProperties.getModelOverride(), reasoningProperties.getEffort()), callBudget);
		};
	}

	public SemanticCandidateSet candidates(Long projectId, Long projectVersionId,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits) {
		SemanticCatalogSnapshot snapshot = catalogRepository.loadCatalog(projectId, projectVersionId);
		Set<String> selectedTables = selectedPhysicalTables == null ? Set.of()
				: selectedPhysicalTables.stream().filter(StringUtils::hasText)
					.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> seedModels = new LinkedHashSet<>();
		for (SemanticCatalogSnapshot.Model model : safe(snapshot.getModels())) {
			if (model.getStatus() == SemanticAssetStatus.ENABLED && selectedTables.contains(model.getPhysicalTable())) {
				seedModels.add(model.getModelCode());
			}
		}
		for (RetrievalHit hit : safeHits(retrievalHits)) {
			if (StringUtils.hasText(hit.modelCode())) {
				seedModels.add(hit.modelCode());
			}
		}

		List<SemanticCatalogSnapshot.Relationship> governedRelationships = governedRelationships(projectId,
				projectVersionId, snapshot);
		Set<String> modelCodes = relationshipNeighborhood(governedRelationships, seedModels);
		if (modelCodes.size() > MAX_CANDIDATE_MODELS) {
			modelCodes = seedModels.stream().limit(MAX_CANDIDATE_MODELS).collect(Collectors.toCollection(LinkedHashSet::new));
		}
		Set<String> finalModelCodes = Set.copyOf(modelCodes);
		List<SemanticCatalogSnapshot.Model> models = safe(snapshot.getModels()).stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> finalModelCodes.contains(model.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Model::getModelCode))
			.toList();
		List<SemanticCatalogSnapshot.Metric> metrics = safe(snapshot.getMetrics()).stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> finalModelCodes.contains(metric.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Metric::getMetricCode))
			.toList();
		List<SemanticCatalogSnapshot.Dimension> dimensions = safe(snapshot.getDimensions()).stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> finalModelCodes.contains(dimension.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Dimension::getDimensionCode))
			.toList();
		List<SemanticCatalogSnapshot.EnumValue> enumValues = safe(snapshot.getEnumValues()).stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> finalModelCodes.contains(value.getModelCode()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.EnumValue value) -> value.getModelCode() + "::"
					+ value.getColumnName() + "::" + value.getValueCode()))
			.toList();
		List<SemanticCatalogSnapshot.Rule> inScopeRules = safe(snapshot.getRules()).stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(rule -> !StringUtils.hasText(rule.getModelCode()) || finalModelCodes.contains(rule.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Rule::getRuleCode))
			.toList();
		List<SemanticCatalogSnapshot.Rule> rules = inScopeRules.stream().filter(this::querySelectableRule).toList();
		List<SemanticCatalogSnapshot.Rule> mandatoryGovernanceRules = inScopeRules.stream()
			.filter(this::mandatoryGovernanceRule)
			.toList();
		List<SemanticCatalogSnapshot.Rule> planningPolicies = inScopeRules.stream()
			.filter(rule -> !querySelectableRule(rule) && !mandatoryGovernanceRule(rule))
			.toList();
		List<SemanticCatalogSnapshot.Relationship> relationships = governedRelationships.stream()
			.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(relationship -> finalModelCodes.contains(relationship.getSourceModelCode())
					&& finalModelCodes.contains(relationship.getTargetModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Relationship::getRelationshipCode))
			.toList();
		List<SemanticCatalogSnapshot.Grain> grains = safe(snapshot.getGrains()).stream()
			.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(grain -> finalModelCodes.contains(grain.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Grain::getGrainCode))
			.toList();
		List<SemanticCatalogSnapshot.Column> timeColumns = safe(snapshot.getColumns()).stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> finalModelCodes.contains(column.getModelCode()))
			.filter(column -> column.getRole() == SemanticColumnRole.TIME)
			.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
			.filter(column -> Boolean.TRUE.equals(column.getAllowSendToLlm()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.Column column) -> column.getModelCode() + "::"
					+ column.getColumnName()))
			.toList();
		List<SemanticCatalogSnapshot.Column> filterableColumns = safe(snapshot.getColumns()).stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> finalModelCodes.contains(column.getModelCode()))
			.filter(column -> column.getRole() != SemanticColumnRole.TIME)
			.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
			.filter(column -> Boolean.TRUE.equals(column.getAllowSendToLlm()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.Column column) -> column.getModelCode() + "::"
					+ column.getColumnName()))
			.toList();
		List<RetrievalEvidence> retrievalEvidence = retrievalHits == null ? List.of()
				: retrievalHits.stream()
					.map(hit -> new RetrievalEvidence(hit.documentType() == null ? null : hit.documentType().name(),
							hit.assetType(), hit.assetKey(), hit.modelCode(), hit.physicalTable(), hit.score(),
							hit.channelRanks(), hit.channelScores()))
					.toList();
		return new SemanticCandidateSet(projectId, projectVersionId, SemanticCatalogFingerprint.fingerprint(snapshot),
				selectedTables, models, metrics, dimensions, enumValues, rules, mandatoryGovernanceRules, planningPolicies,
				relationships, grains, timeColumns, filterableColumns, retrievalEvidence);
	}

	private List<SemanticCatalogSnapshot.Relationship> governedRelationships(Long projectId, Long projectVersionId,
			SemanticCatalogSnapshot snapshot) {
		Map<String, SemanticCatalogSnapshot.Relationship> relationships = new LinkedHashMap<>();
		for (SemanticCatalogSnapshot.Relationship relationship : safe(snapshot.getRelationships())) {
			if (relationship.getStatus() == SemanticAssetStatus.ENABLED) {
				relationships.put(relationship.getRelationshipCode(), relationship);
			}
		}
		if (multiSourcePolicyService == null) {
			return List.copyOf(relationships.values());
		}
		for (CrossSourceRelationship crossSource : multiSourcePolicyService.get(projectId, projectVersionId)
			.getCrossSourceRelationships()) {
			if (crossSource.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			if (relationships.containsKey(crossSource.getRelationshipCode())) {
				throw new IllegalStateException(
						"Duplicate governed relationshipCode across Semantic Catalog and Multi-Source Policy: "
								+ crossSource.getRelationshipCode());
			}
			relationships.put(crossSource.getRelationshipCode(), plannerRelationship(projectId, projectVersionId, crossSource));
		}
		return relationships.values().stream()
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Relationship::getRelationshipCode))
			.toList();
	}

	static SemanticCatalogSnapshot.Relationship plannerRelationship(Long projectId, Long projectVersionId,
			CrossSourceRelationship relationship) {
		return SemanticCatalogSnapshot.Relationship.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.relationshipCode(relationship.getRelationshipCode())
			.sourceModelCode(relationship.getLeftModelCode())
			.targetModelCode(relationship.getRightModelCode())
			.cardinality(relationship.getCardinality())
			.joinType("CROSS_SOURCE_MERGE")
			.joinCondition(relationship.getLeftModelCode() + "." + relationship.getLeftKey() + " = "
					+ relationship.getRightModelCode() + "." + relationship.getRightKey())
			.description("Governed cross-datasource relationship. Physical execution must use the published Multi-Source merge policy, not a database JOIN.")
			.evidence(relationship.getEvidence())
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private Set<String> relationshipNeighborhood(List<SemanticCatalogSnapshot.Relationship> relationships, Set<String> seeds) {
		Set<String> models = new LinkedHashSet<>(seeds);
		for (int depth = 0; depth < RELATIONSHIP_NEIGHBORHOOD_DEPTH; depth++) {
			Set<String> additions = new LinkedHashSet<>();
			for (SemanticCatalogSnapshot.Relationship relationship : relationships) {
				if (relationship.getStatus() != SemanticAssetStatus.ENABLED) {
					continue;
				}
				if (models.contains(relationship.getSourceModelCode())) {
					additions.add(relationship.getTargetModelCode());
				}
				if (models.contains(relationship.getTargetModelCode())) {
					additions.add(relationship.getSourceModelCode());
				}
			}
			if (!models.addAll(additions)) {
				break;
			}
		}
		return models;
	}

	private String userPrompt(String query, SemanticCandidateSet candidates, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		Map<String, RetrievalHit> hitByAsset = safeHits(retrievalHits).stream()
			.collect(Collectors.toMap(RetrievalHit::assetKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("question", query);
		payload.put("models", candidates.models().stream().map(model -> mapOf("modelCode", model.getModelCode(),
				"businessName", model.getBusinessName(), "physicalTable", model.getPhysicalTable(), "description",
				model.getDescription())).toList());
		payload.put("metrics", candidates.metrics().stream().map(metric -> withRetrieval(mapOf("metricCode",
				metric.getMetricCode(), "modelCode", metric.getModelCode(), "businessName", metric.getBusinessName(),
				"aggregation", metric.getAggregation(), "timeColumn", metric.getTimeColumn(), "description",
				metric.getDescription(), "authoritativeExpression", metric.getExpression()),
				hitByAsset.get("metric:" + metric.getMetricCode()))).toList());
		payload.put("dimensions", candidates.dimensions().stream().map(dimension -> withRetrieval(mapOf("dimensionCode",
				dimension.getDimensionCode(), "modelCode", dimension.getModelCode(), "businessName",
				dimension.getBusinessName(), "columnName", dimension.getColumnName(), "dimensionType",
				dimension.getDimensionType(), "description", dimension.getDescription()),
				hitByAsset.get("dimension:" + dimension.getDimensionCode()))).toList());
		payload.put("enumValues", candidates.enumValues().stream().map(value -> withRetrieval(mapOf("modelCode",
				value.getModelCode(), "columnName", value.getColumnName(), "valueCode", value.getValueCode(),
				"businessName", value.getBusinessName(), "aliases", value.getAliases(), "description",
				value.getDescription()), hitByAsset.get(enumAssetKey(value)))).toList());
		payload.put("querySelectableRules", candidates.querySelectableRules().stream().map(this::rulePrompt).toList());
		payload.put("mandatoryGovernanceRules", candidates.mandatoryGovernanceRules().stream().map(this::rulePrompt).toList());
		payload.put("planningPolicies", candidates.planningPolicies().stream().map(this::rulePrompt).toList());
		payload.put("relationships", candidates.relationships().stream().map(relationship -> mapOf("relationshipCode",
				relationship.getRelationshipCode(), "sourceModelCode", relationship.getSourceModelCode(), "targetModelCode",
				relationship.getTargetModelCode(), "cardinality", Objects.toString(relationship.getCardinality(), null),
				"description", relationship.getDescription(), "authoritativeJoinCondition",
				relationship.getJoinCondition())).toList());
		payload.put("grains", candidates.grains().stream().map(grain -> mapOf("grainCode", grain.getGrainCode(),
				"modelCode", grain.getModelCode(), "keyColumns", grain.getKeyColumns(), "timeColumn",
				grain.getTimeColumn(), "description", grain.getDescription())).toList());
		payload.put("timeColumns", candidates.timeColumns().stream().map(column -> mapOf("modelCode", column.getModelCode(),
				"columnName", column.getColumnName(), "businessName", column.getBusinessName(), "synonyms",
				column.getSynonyms(), "role", "TIME", "timeRangeFilterable", true, "description", column.getDescription()))
			.toList());
		payload.put("filterableColumns", candidates.filterableColumns().stream()
			.map(column -> mapOf("modelCode", column.getModelCode(), "columnName", column.getColumnName(), "businessName",
					column.getBusinessName(), "synonyms", column.getSynonyms(), "role", Objects.toString(column.getRole(), null),
					"dataType", column.getDataType(), "description", column.getDescription()))
			.toList());
		QueryCaseHints historical = historicalHints == null ? QueryCaseHints.empty() : historicalHints;
		if (!historical.emptyHints()) {
			payload.put("historicalHints", hintPayload(historical));
		}
		QueryCaseHints required = requiredHints == null ? QueryCaseHints.empty() : requiredHints;
		if (!required.emptyHints()) {
			payload.put("requiredHints", hintPayload(required));
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(payload);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize governed semantic planning candidates", ex);
		}
	}

	private Map<String, Object> rulePrompt(SemanticCatalogSnapshot.Rule rule) {
		return mapOf("ruleCode", rule.getRuleCode(), "modelCode", rule.getModelCode(), "ruleType", rule.getRuleType(),
				"businessName", rule.getBusinessName(), "description", rule.getDescription(), "authoritativeExpression",
				rule.getExpression());
	}

	private boolean querySelectableRule(SemanticCatalogSnapshot.Rule rule) {
		return rule != null && QUERY_SELECTABLE_RULE_TYPES.contains(normalizeRuleType(rule.getRuleType()));
	}

	private boolean mandatoryGovernanceRule(SemanticCatalogSnapshot.Rule rule) {
		return rule != null && MANDATORY_GOVERNANCE_RULE_TYPES.contains(normalizeRuleType(rule.getRuleType()));
	}

	private String normalizeRuleType(String ruleType) {
		return Objects.toString(ruleType, "").trim().toUpperCase(Locale.ROOT);
	}

	private Map<String, Object> hintPayload(QueryCaseHints hints) {
		return mapOf("modelCodes", hints.modelCodes(), "metricCodes", hints.metricCodes(), "dimensionCodes",
				hints.dimensionCodes(), "grainCodes", hints.grainCodes(), "relationshipCodes", hints.relationshipCodes(),
				"ruleCodes", hints.ruleCodes(), "enumBindings", hints.enumBindings(), "filters", hints.filterBindings(),
				"timeBinding", hints.timeBinding(), "resultComposition", hints.resultComposition(), "confidence",
				hints.confidence());
	}

	private Map<String, Object> withRetrieval(Map<String, Object> values, RetrievalHit hit) {
		Map<String, Object> result = new LinkedHashMap<>(values);
		if (hit != null) {
			result.put("retrievalScore", hit.score());
			result.put("retrievalRanks", hit.channelRanks());
		}
		return result;
	}

	private ParsedPlanningSelection parseAndValidate(String query, String response, SemanticCandidateSet candidates,
			QueryCaseHints priorHints) {
		JsonNode root = parseObject(response);
		Set<String> metricCodes = stringSet(root.path("metricCodes"));
		Set<String> dimensionCodes = stringSet(root.path("dimensionCodes"));
		Set<String> ruleCodes = stringSet(root.path("ruleCodes"));
		Set<String> relationshipCodes = stringSet(root.path("relationshipCodes"));
		Set<String> grainCodes = stringSet(root.path("grainCodes"));
		ComputationIntent computationIntent = computationIntent(root.path("computationCapabilities"),
				root.path("computationRequirements"), metricCodes);

		Map<String, SemanticCatalogSnapshot.Metric> metrics = candidates.metrics().stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Metric::getMetricCode, Function.identity()));
		Map<String, SemanticCatalogSnapshot.Dimension> dimensions = candidates.dimensions().stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Dimension::getDimensionCode, Function.identity()));
		Set<String> allowedRules = candidates.querySelectableRules().stream().map(SemanticCatalogSnapshot.Rule::getRuleCode)
			.collect(Collectors.toSet());
		Set<String> allowedRelationships = candidates.relationships().stream()
			.map(SemanticCatalogSnapshot.Relationship::getRelationshipCode).collect(Collectors.toSet());
		Set<String> allowedGrains = candidates.grains().stream().map(SemanticCatalogSnapshot.Grain::getGrainCode)
			.collect(Collectors.toSet());
		assertSubset("metricCodes", metricCodes, metrics.keySet());
		assertSubset("dimensionCodes", dimensionCodes, dimensions.keySet());
		assertSubset("ruleCodes", ruleCodes, allowedRules);
		assertSubset("relationshipCodes", relationshipCodes, allowedRelationships);
		assertSubset("grainCodes", grainCodes, allowedGrains);

		double confidence = confidence(root.path("confidence"));
		List<EnumBindingHint> enumBindings = enumBindings(query, root.path("enumBindings"), candidates, confidence);
		List<FilterBindingHint> filterBindings = filterBindings(query, root.path("filters"), candidates, confidence);
		TimeBindingHint timeBinding = timeBinding(query, root.path("timeBinding"), candidates, confidence);
		ResultCompositionHint resultComposition = resultComposition(root.path("resultComposition"), metricCodes);

		Set<String> modelCodes = new LinkedHashSet<>();
		metricCodes.stream().map(metrics::get).filter(Objects::nonNull).map(SemanticCatalogSnapshot.Metric::getModelCode)
			.forEach(modelCodes::add);
		dimensionCodes.stream().map(dimensions::get).filter(Objects::nonNull)
			.map(SemanticCatalogSnapshot.Dimension::getModelCode).forEach(modelCodes::add);
		enumBindings.stream().map(EnumBindingHint::modelCode).forEach(modelCodes::add);
		filterBindings.stream().map(FilterBindingHint::modelCode).forEach(modelCodes::add);
		if (timeBinding != null) {
			modelCodes.add(timeBinding.modelCode());
		}
		for (SemanticCatalogSnapshot.Rule rule : candidates.querySelectableRules()) {
			if (ruleCodes.contains(rule.getRuleCode()) && StringUtils.hasText(rule.getModelCode())) {
				modelCodes.add(rule.getModelCode());
			}
		}
		for (SemanticCatalogSnapshot.Relationship relationship : candidates.relationships()) {
			if (relationshipCodes.contains(relationship.getRelationshipCode())) {
				modelCodes.add(relationship.getSourceModelCode());
				modelCodes.add(relationship.getTargetModelCode());
			}
		}

		if (metricCodes.isEmpty() && dimensionCodes.isEmpty()) {
			throw new IllegalArgumentException("LLM semantic plan selected no governed projection metric or dimension");
		}
		Set<String> metricModelCodes = metricCodes.stream()
			.map(metrics::get)
			.filter(Objects::nonNull)
			.map(SemanticCatalogSnapshot.Metric::getModelCode)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		boolean relationshipFreeMultiModelSelection = modelCodes.size() > 1;
		if (relationshipFreeMultiModelSelection && relationshipCodes.isEmpty() && resultComposition == null) {
			throw new IllegalArgumentException(
					"A relationship-free multi-model metric selection must declare resultComposition.type=SCALAR or select a supplied relationship");
		}
		boolean independentScalarMetrics = resultComposition != null && relationshipCodes.isEmpty()
				&& !metricModelCodes.isEmpty() && dimensionCodes.isEmpty() && grainCodes.isEmpty()
				&& (timeBinding == null || !StringUtils.hasText(timeBinding.groupGranularity()))
				&& metricModelCodes.equals(modelCodes);
		if (resultComposition != null && !independentScalarMetrics) {
			throw new IllegalArgumentException(
					"resultComposition=SCALAR requires relationship-free scalar metrics from all selected models");
		}
		assertRelationshipSelection(modelCodes, relationshipCodes, candidates.relationships(), independentScalarMetrics);
		QueryCaseHints result = new QueryCaseHints(Set.copyOf(modelCodes), metricCodes, dimensionCodes, grainCodes,
				relationshipCodes, ruleCodes, enumBindings, filterBindings, List.of(), timeBinding, true,
				"LLM_SEMANTIC_PLANNER", List.of(), confidence, Map.of("semanticPlanner", confidence), resultComposition);
		assertRequiredPriorBindings(result, priorHints);
		return new ParsedPlanningSelection(result, computationIntent);
	}

	private void assertRequiredPriorBindings(QueryCaseHints result, QueryCaseHints priorHints) {
		if (priorHints == null || !priorHints.strictAssetBinding()) {
			return;
		}
		assertSubset("required modelCodes", priorHints.modelCodes(), result.modelCodes());
		assertSubset("required metricCodes", priorHints.metricCodes(), result.metricCodes());
		assertSubset("required dimensionCodes", priorHints.dimensionCodes(), result.dimensionCodes());
		assertSubset("required grainCodes", priorHints.grainCodes(), result.grainCodes());
		assertSubset("required relationshipCodes", priorHints.relationshipCodes(), result.relationshipCodes());
		assertSubset("required ruleCodes", priorHints.ruleCodes(), result.ruleCodes());
		for (EnumBindingHint required : priorHints.enumBindings()) {
			boolean present = result.enumBindings().stream().anyMatch(binding -> Objects.equals(required.modelCode(),
					binding.modelCode()) && Objects.equals(required.columnName(), binding.columnName())
					&& Objects.equals(required.valueCode(), binding.valueCode()));
			if (!present) {
				throw new IllegalArgumentException("LLM semantic plan dropped required enum binding: " + required.modelCode()
						+ "." + required.columnName() + "=" + required.valueCode());
			}
		}
		for (FilterBindingHint required : priorHints.filterBindings()) {
			boolean present = result.filterBindings().stream().anyMatch(binding -> Objects.equals(required.modelCode(),
					binding.modelCode()) && Objects.equals(required.columnName(), binding.columnName())
					&& Objects.equals(required.operator(), binding.operator()) && Objects.equals(required.value(), binding.value()));
			if (!present) {
				throw new IllegalArgumentException("LLM semantic plan dropped required literal filter binding");
			}
		}
		if (priorHints.timeBinding() != null) {
			TimeBindingHint selected = result.timeBinding();
			if (selected == null || !Objects.equals(priorHints.timeBinding().modelCode(), selected.modelCode())
					|| !Objects.equals(priorHints.timeBinding().columnName(), selected.columnName())) {
				throw new IllegalArgumentException("LLM semantic plan dropped required time binding");
			}
		}
	}

	private void assertRelationshipSelection(Set<String> modelCodes, Set<String> relationshipCodes,
			List<SemanticCatalogSnapshot.Relationship> candidates, boolean relationshipOptional) {
		if (modelCodes.size() <= 1) {
			return;
		}
		if (relationshipCodes.isEmpty()) {
			if (relationshipOptional) {
				return;
			}
			throw new IllegalArgumentException("relationshipCodes must connect all selected semantic models");
		}
		Map<String, Set<String>> adjacency = new LinkedHashMap<>();
		for (String modelCode : modelCodes) {
			adjacency.put(modelCode, new LinkedHashSet<>());
		}
		for (SemanticCatalogSnapshot.Relationship relationship : candidates) {
			if (!relationshipCodes.contains(relationship.getRelationshipCode())) {
				continue;
			}
			adjacency.computeIfAbsent(relationship.getSourceModelCode(), ignored -> new LinkedHashSet<>())
				.add(relationship.getTargetModelCode());
			adjacency.computeIfAbsent(relationship.getTargetModelCode(), ignored -> new LinkedHashSet<>())
				.add(relationship.getSourceModelCode());
		}
		Set<String> visited = new LinkedHashSet<>();
		List<String> pending = new ArrayList<>();
		pending.add(modelCodes.iterator().next());
		for (int index = 0; index < pending.size(); index++) {
			String current = pending.get(index);
			if (visited.add(current)) {
				adjacency.getOrDefault(current, Set.of()).stream().filter(modelCodes::contains).forEach(pending::add);
			}
		}
		if (!visited.containsAll(modelCodes)) {
			throw new IllegalArgumentException("relationshipCodes do not connect all selected semantic models");
		}
	}

	private List<FilterBindingHint> filterBindings(String query, JsonNode node, SemanticCandidateSet candidates,
			double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return List.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("filters must be an array");
		}
		Map<String, SemanticCatalogSnapshot.Column> allowedColumns = candidates.filterableColumns()
			.stream()
			.collect(Collectors.toMap(column -> columnKey(column.getModelCode(), column.getColumnName()),
					Function.identity()));
		Map<String, SemanticCatalogSnapshot.Column> governedTimeColumns = candidates.timeColumns()
			.stream()
			.collect(Collectors.toMap(column -> columnKey(column.getModelCode(), column.getColumnName()),
					Function.identity()));
		List<FilterBindingHint> bindings = new ArrayList<>();
		for (JsonNode item : node) {
			String modelCode = text(item, "modelCode");
			String columnName = text(item, "columnName");
			String operator = text(item, "operator").toUpperCase(Locale.ROOT);
			if (!SUPPORTED_LITERAL_FILTER_OPERATORS.contains(operator)) {
				throw new IllegalArgumentException("filters contains unsupported operator: " + operator);
			}
			SemanticCatalogSnapshot.Column column = allowedColumns.get(columnKey(modelCode, columnName));
			if (column == null) {
				SemanticCatalogSnapshot.Column timeColumn = governedTimeColumns.get(columnKey(modelCode, columnName));
				if (timeColumn != null && explicitlyMentionsColumn(query, timeColumn)) {
					column = timeColumn;
				}
			}
			if (column == null) {
				throw new IllegalArgumentException("filters contains non-candidate filterable column: " + modelCode + "."
						+ columnName);
			}
			boolean nullPredicate = "IS_NULL".equals(operator) || "IS_NOT_NULL".equals(operator);
			JsonNode valueNode = item.get("value");
			Object value;
			if (nullPredicate) {
				if (valueNode != null && !valueNode.isNull()) {
					throw new IllegalArgumentException(operator + " filter must not include a literal value");
				}
				value = null;
			}
			else {
				if (valueNode == null || valueNode.isNull()) {
					throw new IllegalArgumentException("filters.value is required");
				}
				value = literalValue(valueNode);
				validateFilterValueShape(operator, value);
				if (!literalComesFromQuestion(query, value)) {
					throw new IllegalArgumentException("filters contains a literal that is not present in the current question");
				}
				if (duplicatesPublishedEnum(candidates, modelCode, columnName, value)) {
					throw new IllegalArgumentException("filters duplicates a published enum value; use enumBindings instead");
				}
			}
			String rawText = nullPredicate ? columnName + " " + operator : literalRawText(value);
			bindings.add(new FilterBindingHint(rawText, modelCode, columnName, operator, value,
					"LLM_SEMANTIC_PLANNER", confidence));
		}
		return List.copyOf(bindings);
	}

	private Object literalValue(JsonNode valueNode) {
		if (valueNode.isTextual()) {
			return valueNode.asText();
		}
		if (valueNode.isIntegralNumber()) {
			return valueNode.longValue();
		}
		if (valueNode.isFloatingPointNumber()) {
			return valueNode.doubleValue();
		}
		if (valueNode.isBoolean()) {
			return valueNode.booleanValue();
		}
		if (valueNode.isArray()) {
			List<Object> values = new ArrayList<>();
			for (JsonNode child : valueNode) {
				if (child.isContainerNode() || child.isNull()) {
					throw new IllegalArgumentException("filters array values must contain only scalar literals");
				}
				values.add(literalValue(child));
			}
			return List.copyOf(values);
		}
		throw new IllegalArgumentException("filters.value must be a scalar literal or scalar array");
	}

	private void validateFilterValueShape(String operator, Object value) {
		if ("IN".equals(operator)) {
			if (!(value instanceof List<?> values) || values.isEmpty()) {
				throw new IllegalArgumentException("IN filter requires a non-empty literal array");
			}
			return;
		}
		if (value instanceof Collection<?>) {
			throw new IllegalArgumentException(operator + " filter requires one scalar literal");
		}
	}

	private boolean literalComesFromQuestion(String query, Object value) {
		String normalizedQuery = normalizeNaturalText(query);
		if (value instanceof Collection<?> values) {
			return values.stream().allMatch(item -> literalComesFromQuestion(query, item));
		}
		String literal = normalizeNaturalText(Objects.toString(value, ""));
		return StringUtils.hasText(literal) && normalizedQuery.contains(literal);
	}

	private boolean duplicatesPublishedEnum(SemanticCandidateSet candidates, String modelCode, String columnName,
			Object value) {
		if (value instanceof Collection<?> values) {
			return values.stream().anyMatch(item -> duplicatesPublishedEnum(candidates, modelCode, columnName, item));
		}
		String normalized = normalizeNaturalText(Objects.toString(value, ""));
		return candidates.enumValues()
			.stream()
			.filter(candidate -> Objects.equals(candidate.getModelCode(), modelCode)
					&& Objects.equals(candidate.getColumnName(), columnName))
			.anyMatch(candidate -> normalized.equals(normalizeNaturalText(candidate.getValueCode()))
					|| normalized.equals(normalizeNaturalText(candidate.getBusinessName())));
	}

	private String literalRawText(Object value) {
		if (value instanceof Collection<?> values) {
			return values.stream().map(item -> Objects.toString(item, "")).collect(Collectors.joining(","));
		}
		return Objects.toString(value, "");
	}

	private String columnKey(String modelCode, String columnName) {
		return Objects.toString(modelCode, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(columnName, "").toLowerCase(Locale.ROOT);
	}

	private String normalizeNaturalText(String value) {
		return java.text.Normalizer.normalize(Objects.toString(value, ""), java.text.Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", "");
	}

	private boolean explicitlyMentionsColumn(String query, SemanticCatalogSnapshot.Column column) {
		String normalizedQuery = normalizeNaturalText(query);
		if (!StringUtils.hasText(normalizedQuery) || column == null) {
			return false;
		}
		if (containsExplicitTerm(normalizedQuery, column.getColumnName())
				|| containsExplicitTerm(normalizedQuery, column.getBusinessName())) {
			return true;
		}
		String synonyms = Objects.toString(column.getSynonyms(), "");
		for (String synonym : synonyms.split("[,，;；|/]")) {
			if (containsExplicitTerm(normalizedQuery, synonym)) {
				return true;
			}
		}
		return false;
	}

	private boolean containsExplicitTerm(String normalizedQuery, String term) {
		String normalizedTerm = normalizeNaturalText(term);
		return StringUtils.hasText(normalizedTerm) && normalizedQuery.contains(normalizedTerm);
	}

	private List<EnumBindingHint> enumBindings(String query, JsonNode node, SemanticCandidateSet candidates,
			double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return List.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("enumBindings must be an array");
		}
		Set<String> allowed = candidates.enumValues().stream().map(this::enumKey).collect(Collectors.toSet());
		List<EnumBindingHint> bindings = new ArrayList<>();
		for (JsonNode item : node) {
			String modelCode = text(item, "modelCode");
			String columnName = text(item, "columnName");
			String valueCode = text(item, "valueCode");
			String key = enumKey(modelCode, columnName, valueCode);
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException("enumBindings contains non-candidate value: " + key);
			}
			bindings.add(new EnumBindingHint(query, modelCode, columnName, valueCode, "LLM_SEMANTIC_PLANNER",
					confidence));
		}
		return List.copyOf(bindings);
	}

	private TimeBindingHint timeBinding(String query, JsonNode node, SemanticCandidateSet candidates, double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isObject()) {
			throw new IllegalArgumentException("timeBinding must be an object or null");
		}
		String modelCode = text(node, "modelCode");
		String columnName = text(node, "columnName");
		boolean allowed = candidates.timeColumns().stream().anyMatch(column -> Objects.equals(modelCode, column.getModelCode())
				&& Objects.equals(columnName, column.getColumnName()));
		if (!allowed) {
			throw new IllegalArgumentException("timeBinding contains non-candidate time column: " + modelCode + "."
					+ columnName);
		}
		String groupGranularity = nullableText(node, "groupGranularity");
		if (StringUtils.hasText(groupGranularity)) {
			groupGranularity = groupGranularity.toUpperCase(Locale.ROOT);
			if (!SUPPORTED_TIME_GROUP_GRANULARITIES.contains(groupGranularity)) {
				throw new IllegalArgumentException("timeBinding contains unsupported groupGranularity: " + groupGranularity);
			}
		}
		return new TimeBindingHint(query, modelCode, columnName, "LLM_SEMANTIC_PLANNER", confidence,
				groupGranularity);
	}

	private ResultCompositionHint resultComposition(JsonNode node, Set<String> metricCodes) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isObject()) {
			throw new IllegalArgumentException("resultComposition must be an object or null");
		}
		return validateResultComposition(nullableText(node, "type"), nullableText(node, "calculationExpression"),
				metricCodes);
	}

	static ResultCompositionHint validateResultComposition(String type, String calculationExpression,
			Set<String> metricCodes) {
		String normalizedType = Objects.toString(type, "").trim().toUpperCase(Locale.ROOT);
		if (!"SCALAR".equals(normalizedType)) {
			throw new IllegalArgumentException("resultComposition.type must be SCALAR");
		}
		if (!StringUtils.hasText(calculationExpression)) {
			return new ResultCompositionHint("SCALAR", null);
		}
		String normalized = calculationExpression.replaceAll("\\s+", "").trim();
		String[] assignment = normalized.split("=", 2);
		if (assignment.length != 2 || !SCALAR_IDENTIFIER.matcher(assignment[0]).matches()) {
			throw new IllegalArgumentException("resultComposition.calculationExpression must assign a valid output alias");
		}
		if (metricCodes.contains(assignment[0])) {
			throw new IllegalArgumentException("resultComposition output alias must not overwrite a selected metric");
		}
		String expression = assignment[1];
		if (expression.regionMatches(true, 0, "ABS(", 0, 4) && expression.endsWith(")")) {
			expression = expression.substring(4, expression.length() - 1);
		}
		var matcher = SCALAR_BINARY_EXPRESSION.matcher(expression);
		if (!matcher.matches()) {
			throw new IllegalArgumentException(
					"resultComposition.calculationExpression supports only one binary + or - expression");
		}
		String leftMetric = matcher.group(1);
		String rightMetric = matcher.group(3);
		if (!metricCodes.contains(leftMetric) || !metricCodes.contains(rightMetric)) {
			throw new IllegalArgumentException(
					"resultComposition.calculationExpression may reference only selected metric codes");
		}
		return new ResultCompositionHint("SCALAR", normalized);
	}

	private SemanticPlanningOutcome explicitNonResolvedOutcome(String response) {
		JsonNode root = parseObject(response);
		String status = root.path("status").asText("RESOLVED").trim().toUpperCase(Locale.ROOT);
		if ("RESOLVED".equals(status)) {
			return null;
		}
		if ("NEEDS_CLARIFICATION".equals(status)) {
			JsonNode clarification = root.path("clarification");
			String issueType = nullableText(clarification, "issueType");
			String question = nullableText(clarification, "question");
			String reason = nullableText(clarification, "reason");
			List<SemanticPlanningOutcome.Option> options = new ArrayList<>();
			JsonNode optionNodes = clarification.path("options");
			if (optionNodes.isArray()) {
				for (JsonNode option : optionNodes) {
					options.add(new SemanticPlanningOutcome.Option(nullableText(option, "code"),
							nullableText(option, "label"), nullableText(option, "assetType"),
							nullableText(option, "assetKey")));
				}
			}
			if (!StringUtils.hasText(question)) {
				question = "The governed semantic candidates do not uniquely determine the requested business meaning.";
			}
			return new SemanticPlanningOutcome.ClarificationRequired(
					StringUtils.hasText(issueType) ? issueType : "SEMANTIC_AMBIGUITY", question, options, reason);
		}
		String reason = nullableText(root, "reason");
		return new SemanticPlanningOutcome.Rejected("MODEL_UNRESOLVABLE",
				StringUtils.hasText(reason) ? reason : "Semantic planner marked the question as unresolvable");
	}

	private JsonNode parseObject(String response) {
		try {
			String trimmed = Objects.toString(response, "").trim();
			if (trimmed.startsWith("```")) {
				int firstLine = trimmed.indexOf('\n');
				int closing = trimmed.lastIndexOf("```");
				if (firstLine >= 0 && closing > firstLine) {
					trimmed = trimmed.substring(firstLine + 1, closing).trim();
				}
			}
			int start = trimmed.indexOf('{');
			int end = trimmed.lastIndexOf('}');
			if (start < 0 || end < start) {
				throw new IllegalArgumentException("Semantic planner returned no JSON object");
			}
			JsonNode root = JsonUtil.getObjectMapper().readTree(trimmed.substring(start, end + 1));
			if (!root.isObject()) {
				throw new IllegalArgumentException("Semantic planner JSON root must be an object");
			}
			return root;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid semantic planner JSON", ex);
		}
	}

	private static Set<String> stringSet(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return Set.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("Semantic planner asset selection must be an array");
		}
		Set<String> values = new LinkedHashSet<>();
		for (JsonNode item : node) {
			if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
				throw new IllegalArgumentException("Semantic planner asset code must be a non-blank string");
			}
			values.add(item.asText().trim());
		}
		return Set.copyOf(values);
	}

	private void assertSubset(String field, Set<String> selected, Set<String> allowed) {
		Set<String> invalid = selected.stream().filter(value -> !allowed.contains(value))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!invalid.isEmpty()) {
			throw new IllegalArgumentException(field + " contains non-candidate assets: " + String.join(",", invalid));
		}
	}

	static ComputationIntent computationIntent(JsonNode capabilityNode, JsonNode requirementNode,
			Set<String> selectedMetricCodes) {
		Set<String> names = stringSet(capabilityNode);
		LinkedHashSet<Capability> capabilities = new LinkedHashSet<>();
		for (String name : names) {
			capabilities.add(computationCapability(name));
		}
		List<Requirement> requirements = new ArrayList<>();
		if (requirementNode != null && !requirementNode.isMissingNode() && !requirementNode.isNull()) {
			if (!requirementNode.isArray()) {
				throw new IllegalArgumentException("computationRequirements must be an array");
			}
			if (requirementNode.size() > 32) {
				throw new IllegalArgumentException("computationRequirements exceeds 32 items");
			}
			for (JsonNode item : requirementNode) {
				if (!item.isObject()) {
					throw new IllegalArgumentException("Each computation requirement must be an object");
				}
				Capability capability = computationCapability(text(item, "capability"));
				capabilities.add(capability);
				String metricCode = nullableText(item, "metricCode");
				if (StringUtils.hasText(metricCode)
						&& (selectedMetricCodes == null || !selectedMetricCodes.contains(metricCode))) {
					throw new IllegalArgumentException("Computation requirement metricCode must be selected by the semantic planner");
				}
				Integer limit = requirementLimit(item.get("limit"));
				requirements.add(new Requirement(capability, metricCode, requirementToken(item, "grain"),
						requirementToken(item, "mode"), limit, requirementToken(item, "scope"),
						requirementToken(item, "basis")));
			}
		}
		return new ComputationIntent(capabilities, requirements);
	}

	private static Capability computationCapability(String name) {
		try {
			return Capability.valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("Unsupported computation capability: " + name);
		}
	}

	private static Integer requirementLimit(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new IllegalArgumentException("Computation requirement limit must be an integer");
		}
		int limit = node.asInt();
		if (limit <= 0 || limit > 10000) {
			throw new IllegalArgumentException("Computation requirement limit must be between 1 and 10000");
		}
		return limit;
	}

	private static String requirementToken(JsonNode item, String field) {
		JsonNode node = item == null ? null : item.get(field);
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isTextual() || !StringUtils.hasText(node.asText())
				|| !COMPUTATION_REQUIREMENT_TOKEN.matcher(node.asText().trim()).matches()) {
			throw new IllegalArgumentException("Computation requirement " + field + " must be a semantic token");
		}
		return node.asText().trim();
	}

	private double confidence(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return 0.90d;
		}
		return Math.max(0.0d, Math.min(1.0d, node.asDouble()));
	}

	private static String text(JsonNode node, String field) {
		String value = node == null ? null : node.path(field).asText(null);
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Semantic planner field is required: " + field);
		}
		return value.trim();
	}

	private static String nullableText(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull() || value.isMissingNode()) {
			return null;
		}
		String text = value.asText(null);
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private String enumAssetKey(SemanticCatalogSnapshot.EnumValue value) {
		return "enum_value:" + value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode();
	}

	private String enumKey(SemanticCatalogSnapshot.EnumValue value) {
		return enumKey(value.getModelCode(), value.getColumnName(), value.getValueCode());
	}

	private String enumKey(String modelCode, String columnName, String valueCode) {
		return Objects.toString(modelCode, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(columnName, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(valueCode, "").toLowerCase(Locale.ROOT);
	}

	private Map<String, Object> mapOf(Object... values) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index + 1 < values.length; index += 2) {
			Object value = values[index + 1];
			if (value != null && (!(value instanceof String string) || StringUtils.hasText(string))) {
				result.put(Objects.toString(values[index]), value);
			}
		}
		return result;
	}

	private String safeError(String message) {
		String safe = Objects.toString(message, "invalid governed semantic selection").replaceAll("[\\r\\n]+", " ");
		return safe.length() <= 300 ? safe : safe.substring(0, 300);
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	private List<RetrievalHit> safeHits(Collection<RetrievalHit> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	private record ParsedPlanningSelection(QueryCaseHints binding, ComputationIntent computationIntent) {
	}

	public enum PlannerProfile {
		CONFIGURED,
		BASELINE,
		REASONING
	}

	public record PlanningDecision(SemanticPlanningOutcome outcome, List<ModelCallResult> modelCalls,
			PlanningSession planningSession) {
		public PlanningDecision {
			modelCalls = List.copyOf(modelCalls == null ? List.of() : modelCalls);
		}

		public PlanningDecision(SemanticPlanningOutcome outcome, List<ModelCallResult> modelCalls) {
			this(outcome, modelCalls, null);
		}
	}

	public static final class PlanningSession {

		private final long deadlineNanos;

		private final int maxModelCalls;

		private final long minimumRepairBudgetNanos;

		private int startedModelCalls;

		private PlanningSession(long totalBudgetMs, int maxModelCalls, long minimumRepairBudgetMs,
				Long runDeadlineEpochMillis) {
			long boundedBudgetMs = Math.max(1L, totalBudgetMs);
			if (runDeadlineEpochMillis != null) {
				boundedBudgetMs = Math.min(boundedBudgetMs,
						Math.max(1L, runDeadlineEpochMillis - System.currentTimeMillis()));
			}
			this.deadlineNanos = System.nanoTime() + Duration.ofMillis(boundedBudgetMs).toNanos();
			this.maxModelCalls = Math.max(1, maxModelCalls);
			this.minimumRepairBudgetNanos = Duration.ofMillis(Math.max(1L, minimumRepairBudgetMs)).toNanos();
		}

		static PlanningSession start(SemanticPlanningProperties properties, Long runDeadlineEpochMillis) {
			SemanticPlanningProperties effective = properties == null ? new SemanticPlanningProperties() : properties;
			return new PlanningSession(effective.getTotalBudgetMs(), effective.getMaxModelCalls(),
					effective.getMinimumRepairBudgetMs(), runDeadlineEpochMillis);
		}

		synchronized boolean hasCallCapacity() {
			return startedModelCalls < maxModelCalls && remainingNanos() > 0L;
		}

		synchronized Duration nextCallBudget(boolean repair) {
			if (startedModelCalls >= maxModelCalls) {
				throw new SemanticPlanningBudgetExceededException("Semantic planning model-call budget exhausted");
			}
			long remaining = remainingNanos();
			if (remaining <= 0L) {
				throw new SemanticPlanningBudgetExceededException("Semantic planning timeout budget exhausted");
			}
			if (repair && remaining < minimumRepairBudgetNanos) {
				throw new SemanticPlanningBudgetExceededException(
						"Semantic planning remaining timeout budget is insufficient for repair");
			}
			startedModelCalls++;
			return Duration.ofNanos(remaining);
		}

		synchronized int startedModelCalls() {
			return startedModelCalls;
		}

		private long remainingNanos() {
			return Math.max(0L, deadlineNanos - System.nanoTime());
		}
	}

	public static class SemanticPlanningBudgetExceededException extends IllegalStateException {

		public SemanticPlanningBudgetExceededException(String message) {
			super(message, new java.util.concurrent.TimeoutException(message));
		}
	}

}
