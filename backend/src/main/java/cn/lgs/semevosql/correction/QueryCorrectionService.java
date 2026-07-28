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
package cn.lgs.semevosql.correction;

import cn.lgs.semevosql.clarification.ProjectSemanticAliasWorkflowService;
import cn.lgs.semevosql.clarification.RuntimePrincipalResolver;
import cn.lgs.semevosql.clarification.SemanticBindingScope;
import cn.lgs.semevosql.clarification.UserSemanticPreferenceService;
import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageResult;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Structured correction path for wrong language-to-asset bindings. */
@Service
public class QueryCorrectionService {

	private static final List<String> BINDING_TYPES = List.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN");

	private final QueryRunService runService;

	private final SemanticCatalogCache catalogCache;

	private final RuntimePrincipalResolver principalResolver;

	private final UserSemanticPreferenceService preferenceService;

	private final ProjectSemanticAliasWorkflowService projectAliasWorkflowService;

	private final QueryPatternTemplateService patternTemplateService;

	private final SemanticCorrectionProposalService proposalService;

	private final ProjectConversationService conversationService;

	private final LocalOperatorService authorization;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public QueryCorrectionService(QueryRunService runService, SemanticCatalogCache catalogCache,
			RuntimePrincipalResolver principalResolver, UserSemanticPreferenceService preferenceService,
			ProjectSemanticAliasWorkflowService projectAliasWorkflowService,
			QueryPatternTemplateService patternTemplateService, SemanticCorrectionProposalService proposalService,
			ProjectConversationService conversationService, LocalOperatorService authorization) {
		this.runService = runService;
		this.catalogCache = catalogCache;
		this.principalResolver = principalResolver;
		this.preferenceService = preferenceService;
		this.projectAliasWorkflowService = projectAliasWorkflowService;
		this.patternTemplateService = patternTemplateService;
		this.proposalService = proposalService;
		this.conversationService = conversationService;
		this.authorization = authorization;
	}

	public CorrectionOptions options(String runId, String assetType) {
		QueryRun run = runService.get(runId);
		String type = normalizeBindingType(assetType);
		SemanticCatalogSnapshot catalog = catalogCache.get(run.projectId(), run.projectVersionId());
		List<CorrectionOption> options = new ArrayList<>();
		if ("METRIC".equals(type)) {
			catalog.getMetrics()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.forEach(value -> options.add(new CorrectionOption("METRIC", value.getMetricCode(),
						value.getBusinessName(), value.getModelCode())));
		}
		else if ("DIMENSION".equals(type)) {
			catalog.getDimensions()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.forEach(value -> options.add(new CorrectionOption("DIMENSION", value.getDimensionCode(),
						value.getBusinessName(), value.getModelCode())));
		}
		else if ("TIME_COLUMN".equals(type)) {
			catalog.getColumns()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> value.getRole() == cn.lgs.semevosql.semantic.domain.SemanticColumnRole.TIME)
				.forEach(value -> options.add(new CorrectionOption("TIME_COLUMN",
						value.getModelCode() + ":" + value.getColumnName(), value.getBusinessName(), value.getModelCode())));
		}
		else {
			catalog.getEnumValues()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.forEach(value -> options.add(new CorrectionOption("ENUM_VALUE",
						value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode(),
						value.getBusinessName(), value.getModelCode())));
		}
		return new CorrectionOptions(runId, type, options.stream().limit(200).toList());
	}

	@Transactional
	public CorrectionResult correctBinding(Long projectId, String conversationId, String runId,
			BindingCorrectionCommand command, OperatorContext operator) {
		QueryRun original = runService.get(runId);
		if (!original.terminal() || !Objects.equals(original.projectId(), projectId)
				|| !Objects.equals(original.threadId(), conversationId)) {
			throw new IllegalStateException(
					"Correction target must be a terminal Run from the same project conversation");
		}
		SemanticBindingScope scope = command.scope() == null ? SemanticBindingScope.QUERY : command.scope();
		String principal = principalResolver.resolve(original);
		if (scope == SemanticBindingScope.PROJECT) {
			authorization.require(operator, "propose PROJECT semantic binding correction");
		}
		else {
			authorization.require(operator, "correct query semantic binding");
			requireRunOwner(principal, operator);
		}
		String rawExpression = required(command.rawExpression(), "rawExpression");
		String assetType = normalizeBindingType(command.assetType());
		String assetKey = required(command.assetKey(), "assetKey");
		String businessLabel = required(command.businessLabel(), "businessLabel");
		patternTemplateService.invalidateByRun(runId, "User corrected the semantic binding used by this Run");
		String candidateId = null;
		if (scope == SemanticBindingScope.USER) {
			requireDurablePrincipal(principal);
			requireRunOwner(principal, operator);
			preferenceService.save(projectId, principal, rawExpression, assetType, assetKey, businessLabel);
			preferenceService.invalidateRunUsage(runId, null);
		}
		else {
			preferenceService.invalidateRunUsage(runId, null);
			if (scope == SemanticBindingScope.PROJECT) {
				requireDurablePrincipal(principal);
				candidateId = projectAliasWorkflowService
					.proposeAlias(projectId, rawExpression, assetType, assetKey, businessLabel, operator)
					.candidateId();
			}
		}
		String idempotencyKey = StringUtils.hasText(command.idempotencyKey()) ? command.idempotencyKey().trim()
				: "correction:" + runId + ":" + UUID.randomUUID();
		SendMessageResult rerun = conversationService.rerunWithBinding(projectId, conversationId, runId, rawExpression,
				assetType, assetKey, businessLabel, idempotencyKey, "correction-" + UUID.randomUUID(),
				operator.operator());
		LinkedHashMap<String, Object> event = new LinkedHashMap<>();
		event.put("rawExpression", rawExpression);
		event.put("assetType", assetType);
		event.put("assetKey", assetKey);
		event.put("businessLabel", businessLabel);
		event.put("scope", scope.name());
		event.put("rerunId", rerun.run().runId());
		if (candidateId != null) event.put("candidateId", candidateId);
		runService.appendEvent(runId, "QUERY_BINDING_CORRECTED", "query-diagnosis", json(event),
				"User confirmed a semantic binding correction", idempotencyKey + ":diagnosis");
		return new CorrectionResult(runId, rerun.run().runId(), scope, assetType, assetKey, businessLabel, candidateId);
	}

	@Transactional
	public SemanticCorrectionProposalService.ProposalResult proposeDefinition(Long projectId, String conversationId,
			String runId, DefinitionCorrectionCommand command, OperatorContext operator) {
		authorization.require(operator, "propose project semantic definition correction");
		QueryRun original = runService.get(runId);
		if (!original.terminal() || !Objects.equals(original.projectId(), projectId)
				|| !Objects.equals(original.threadId(), conversationId)) {
			throw new IllegalStateException(
					"Correction target must be a terminal Run from the same project conversation");
		}
		String principal = principalResolver.resolve(original);
		preferenceService.invalidateRunUsage(runId, null);
		patternTemplateService.invalidateByRun(runId, "User corrected a project semantic definition used by this Run");
		SemanticCorrectionProposalService.ProposalResult proposal = proposalService.propose(original, command.category(),
				command.correctionText(), principal);
		LinkedHashMap<String, Object> event = new LinkedHashMap<>();
		event.put("candidateId", proposal.candidateId());
		event.put("category", command.category());
		event.put("assetType", proposal.assetType());
		event.put("assetKey", proposal.assetKey());
		runService.appendEvent(runId, "SEMANTIC_DEFINITION_CORRECTION_PROPOSED", "query-diagnosis", json(event),
				"User proposed a governed semantic definition correction", "definition-correction:" + proposal.candidateId());
		return proposal;
	}

	private static String normalizeBindingType(String value) {
		String type = required(value, "assetType").toUpperCase(java.util.Locale.ROOT);
		if (!BINDING_TYPES.contains(type)) {
			throw new IllegalArgumentException("Only language binding corrections are supported here: " + type);
		}
		return type;
	}

	private static void requireDurablePrincipal(String principal) {
		if (!StringUtils.hasText(principal) || RuntimePrincipalResolver.ANONYMOUS.equals(principal)) {
			throw new IllegalStateException("A stable authenticated userId is required for durable correction scope");
		}
	}

	private static void requireRunOwner(String principal, OperatorContext operator) {
		if (operator == null) {
			throw new SecurityException("A server-resolved OperatorContext is required for query correction");
		}
		if (!Objects.equals(principal, operator.operator())) {
			throw new SecurityException("QUERY/USER semantic correction can only be changed by the Run owner");
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize query correction evidence", ex);
		}
	}

	private static String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	public record CorrectionOption(String assetType, String assetKey, String businessLabel, String modelCode) {
	}

	public record CorrectionOptions(String runId, String assetType, List<CorrectionOption> options) {
	}

	public record BindingCorrectionCommand(String rawExpression, String assetType, String assetKey,
			String businessLabel, SemanticBindingScope scope, String idempotencyKey) {
	}

	public record DefinitionCorrectionCommand(String category, String correctionText) {
	}

	public record CorrectionResult(String originalRunId, String rerunId, SemanticBindingScope scope, String assetType,
			String assetKey, String businessLabel, String candidateId) {
	}

}
