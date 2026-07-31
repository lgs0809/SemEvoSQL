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
package cn.lgs.semevosql.conversation;

import cn.lgs.semevosql.dto.GraphRequest;
import cn.lgs.semevosql.common.BlockingExecutionGuard;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.clarification.RuntimePrincipalResolver;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService.BindingContext;
import cn.lgs.semevosql.clarification.UserSemanticPreferenceService;
import cn.lgs.semevosql.episode.domain.EpisodeRelationType;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.EpisodeRequest;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectRuntimeProfileService;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryExecutionExplanationService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunErrorPresenter;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.task.QueryTask;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.service.graph.GraphService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.vo.GraphNodeResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectConversationService {

	private final JdbcTemplate jdbcTemplate;

	private final SemanticProjectRepository projectRepository;

	private final ProjectRuntimeGate runtimeGate;

	private final ProjectRuntimeProfileService runtimeProfileService;

	private final GraphService graphService;

	private final QueryRunService runService;

	private final QueryRunErrorPresenter runErrorPresenter;

	private final SemEvoSQLProductionService productionService;

	private final MultiSourceRunService multiSourceRunService;

	private final ExecutionSnapshotService executionSnapshotService;

	private final QueryExecutionExplanationService executionExplanationService;

	private final RuntimeSemanticBindingService runtimeSemanticBindingService;

	private final RuntimeClarificationService runtimeClarificationService;

	private final RuntimePrincipalResolver runtimePrincipalResolver;

	private final UserSemanticPreferenceService userSemanticPreferenceService;

	private final QueryTaskRepository queryTaskRepository;

	@Transactional
	public ProjectConversation create(Long projectId, String title, String createdBy) {
		ProjectRuntimeContext context = runtimeGate.requireReadyByProject(projectId);
		String conversationId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO qw_project_conversation
				(conversation_id, project_id, project_version_id, title, status, created_by)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?)
				""", conversationId, projectId, context.projectVersionId(), normalizeTitle(title),
				required(createdBy, "createdBy"));
		return get(projectId, conversationId);
	}

	public List<ProjectConversation> list(Long projectId) {
		projectRepository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
		return jdbcTemplate.query("""
				SELECT * FROM qw_project_conversation
				WHERE project_id = ? AND status <> 'DELETED'
				ORDER BY update_time DESC, conversation_id
				""", CONVERSATION_MAPPER, projectId);
	}

	@Transactional
	public ConversationView view(Long projectId, String conversationId) {
		ProjectConversation conversation = get(projectId, conversationId);
		List<ProjectMessage> messages = jdbcTemplate.query("""
				SELECT * FROM qw_project_message WHERE conversation_id = ? ORDER BY sequence_no
				""", MESSAGE_MAPPER, conversationId);
		boolean reconciled = reconcileAssistantMessages(projectId, conversationId, messages);
		if (reconciled) {
			messages = jdbcTemplate.query("""
					SELECT * FROM qw_project_message WHERE conversation_id = ? ORDER BY sequence_no
					""", MESSAGE_MAPPER, conversationId);
		}
		return new ConversationView(conversation, messages);
	}

	@Transactional
	public ProjectConversation rename(Long projectId, String conversationId, long revision, String title) {
		ProjectConversation conversation = get(projectId, conversationId);
		if (conversation.revision() != revision) {
			throw new IllegalStateException("Conversation revision conflict: " + conversationId);
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_project_conversation
				SET title = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE conversation_id = ? AND project_id = ? AND revision = ? AND status = 'ACTIVE'
				""", normalizeTitle(title), conversationId, projectId, revision);
		if (updated != 1) {
			throw new IllegalStateException("Conversation changed concurrently: " + conversationId);
		}
		return get(projectId, conversationId);
	}

	@Transactional
	public ProjectConversation archive(Long projectId, String conversationId, long revision) {
		ProjectConversation conversation = get(projectId, conversationId);
		if (conversation.revision() != revision) {
			throw new IllegalStateException("Conversation revision conflict: " + conversationId);
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_project_conversation
				SET status = 'ARCHIVED', revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE conversation_id = ? AND project_id = ? AND revision = ? AND status = 'ACTIVE'
				""", conversationId, projectId, revision);
		if (updated != 1) {
			throw new IllegalStateException("Conversation changed concurrently: " + conversationId);
		}
		return get(projectId, conversationId);
	}

	@Transactional
	public SendMessageResult send(Long projectId, String conversationId, SendMessageCommand command,
			String principalId) {
		return send(projectId, conversationId, command, null, required(principalId, "principalId"), null, null);
	}

	@Transactional
	public SendMessageResult sendAsChild(Long projectId, String conversationId, SendMessageCommand command,
			String principalId, String parentEpisodeId, EpisodeRelationType relationType) {
		if (!StringUtils.hasText(parentEpisodeId) || relationType == null) {
			throw new IllegalArgumentException("Child Episode requires parentEpisodeId and relationType");
		}
		return send(projectId, conversationId, command, null, required(principalId, "principalId"), parentEpisodeId,
				relationType);
	}

	@Transactional
	public SendMessageResult sendWithBindings(Long projectId, String conversationId, SendMessageCommand command,
			BindingContext forcedBindings, String principalId) {
		if (forcedBindings == null || forcedBindings.empty()) {
			throw new IllegalArgumentException("forcedBindings must contain at least one governed semantic binding");
		}
		return send(projectId, conversationId, command, forcedBindings, required(principalId, "principalId"), null, null);
	}

	private SendMessageResult send(Long projectId, String conversationId, SendMessageCommand command,
			BindingContext forcedBindings, String principalId, String parentEpisodeId, EpisodeRelationType relationType) {
		BlockingExecutionGuard.assertBlockingAllowed("project-conversation.send");
		ProjectConversation conversation = lock(projectId, conversationId);
		if (conversation.status() != ConversationStatus.ACTIVE) {
			throw new IllegalStateException("Only an ACTIVE conversation accepts new messages");
		}
		ProjectRuntimeContext context = runtimeGate.requireReadyByProject(projectId);
		String question = required(command.content(), "content");
		String idempotencyKey = required(command.idempotencyKey(), "idempotencyKey");
		String requestId = command.requestId() == null || command.requestId().isBlank() ? idempotencyKey
				: command.requestId().trim();
		QueryApprovalMode approvalMode = command.effectiveApprovalMode();
		String requestFingerprint = requestFingerprint(conversationId, context.projectVersionId(), question, approvalMode);
		Optional<ProjectMessage> existing = jdbcTemplate.query("""
				SELECT * FROM qw_project_message WHERE conversation_id = ? AND idempotency_key = ?
				""", MESSAGE_MAPPER, conversationId, idempotencyKey).stream().findFirst();
		if (existing.isPresent()) {
			if (!Objects.equals(existing.get().requestFingerprint(), requestFingerprint)) {
				throw new IllegalStateException("Message idempotencyKey is already bound to a different request");
			}
			QueryRun run = existing.get().runId() == null ? null : runService.get(existing.get().runId());
			return new SendMessageResult(existing.get(), run);
		}
		assertNoActiveRun(conversationId);

		String durableIdempotencyKey = "conversation:" + conversationId + ":" + idempotencyKey;
		SemanticProject project = projectRepository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
		ProjectRuntimeProfile runtimeProfile = runtimeProfileService.resolveOrCreate(project);
		boolean approvalRequired = approvalMode == QueryApprovalMode.REQUIRE_APPROVAL;
		GraphRequest graphRequest = GraphRequest.builder()
			.projectId(projectId)
			.agentId("project:" + projectId + ":" + runtimeProfile.getRuntimeProfileId())
			.threadId(conversationId)
			.requestId(requestId)
			.idempotencyKey(durableIdempotencyKey)
			.principalId(principalId)
			.query(question)
			.humanFeedback(approvalRequired)
			.build();
		String executionSnapshot = executionSnapshotService.capture(context, runtimeProfile, null, approvalRequired);
		QueryRun run = runService.create(new CreateRunCommand(RunType.INTERACTIVE_QUERY, projectId,
				context.projectVersionId(), conversationId, requestId, durableIdempotencyKey, json(graphRequest),
				executionSnapshot));
		graphRequest.setRunId(run.runId());
		var episodeBinding = productionService.createFirstAttemptAndBind(run.runId(), conversationId,
				new EpisodeRequest(requestId, graphRequest.getAgentId(), projectId, context.projectVersionId(), null,
						context.catalogHash(), conversationId, parentEpisodeId, relationType, question, question, null, "v1"));
		run = episodeBinding.run();
		persistRequestBindings(run.runId(), forcedBindings);

		long sequence = nextSequence(conversationId);
		String userMessageId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO qw_project_message
				(message_id, conversation_id, sequence_no, role, content, run_id, status, idempotency_key,
				 request_fingerprint, metadata_json)
				VALUES (?, ?, ?, 'USER', ?, ?, 'ACCEPTED', ?, ?, ?)
				""", userMessageId, conversationId, sequence, question, run.runId(), idempotencyKey, requestFingerprint,
				json(Map.of("requestId", requestId, "approvalMode", approvalMode)));
		String assistantMessageId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO qw_project_message
				(message_id, conversation_id, sequence_no, role, content, run_id, status, metadata_json)
				VALUES (?, ?, ?, 'ASSISTANT', '任务已提交，结果由持久化 Run 事件持续更新。', ?, 'RUNNING', ?)
				""", assistantMessageId, conversationId, sequence + 1, run.runId(), json(Map.of("eventApi",
					"/api/semevosql/runs/" + run.runId() + "/events", "approvalMode", approvalMode)));
		jdbcTemplate.update("""
				UPDATE qw_project_conversation
				SET project_version_id = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE conversation_id = ?
				""", context.projectVersionId(), conversationId);
		startGraphAfterCommit(graphRequest);
		return new SendMessageResult(requireMessage(userMessageId), runService.get(run.runId()));
	}

	private void persistRequestBindings(String runId, BindingContext forcedBindings) {
		if (forcedBindings == null || forcedBindings.empty()) {
			return;
		}
		runService.appendEvent(runId, "REQUEST_SEMANTIC_BINDINGS", "request-bootstrap", json(forcedBindings),
				"Explicit governed semantic bindings attached to request", "request-semantic-bindings:" + runId);
	}

	@Transactional
	public QueryRun resumePlanningClarification(String runId) {
		BlockingExecutionGuard.assertBlockingAllowed("project-conversation.resume-planning-clarification");
		QueryRun run = runService.get(runId);
		if (run.status() != QueryRun.RunStatus.QUEUED) {
			throw new IllegalStateException("Run is not queued after semantic planning clarification");
		}
		lock(run.projectId(), run.threadId());
		runtimeGate.requireReadyVersion(run.projectId(), run.projectVersionId());
		try {
			GraphRequest graphRequest = JsonUtil.getObjectMapper().readValue(run.requestPayload(), GraphRequest.class);
			graphRequest.setRunId(runId);
			startGraphAfterCommit(graphRequest);
			return runService.get(runId);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Planning clarification Run has no valid durable Graph request payload", ex);
		}
	}

	@Transactional
	public SendMessageResult rerunWithBinding(Long projectId, String conversationId, String originalRunId,
			String rawExpression, String assetType, String assetKey, String businessLabel, String idempotencyKey,
			String requestId, String principalId) {
		QueryRun original = runService.get(originalRunId);
		if (!original.terminal() || !Objects.equals(original.projectId(), projectId)
				|| !Objects.equals(original.threadId(), conversationId)) {
			throw new IllegalStateException("Correction can only rerun a terminal Run from the same conversation");
		}
		String originalQuestion = jdbcTemplate.query("""
				SELECT content FROM qw_project_message
				WHERE run_id = ? AND role = 'USER' ORDER BY sequence_no DESC LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), originalRunId)
			.stream()
			.findFirst()
			.orElseThrow(
					() -> new IllegalArgumentException("Original user message not found for Run: " + originalRunId));
		BindingContext binding = runtimeSemanticBindingService.explicit(projectId, original.projectVersionId(),
				rawExpression, assetType, assetKey, businessLabel);
		QueryApprovalMode approvalMode = executionSnapshotService.readTyped(original.executionSnapshot())
			.map(snapshot -> snapshot.humanReviewEnabled() ? QueryApprovalMode.REQUIRE_APPROVAL
					: QueryApprovalMode.AUTO_EXECUTE)
			.orElse(QueryApprovalMode.REQUIRE_APPROVAL);
		return send(projectId, conversationId,
				new SendMessageCommand(originalQuestion, idempotencyKey, requestId, approvalMode), binding,
				required(principalId, "principalId"), null, null);
	}

	@Transactional
	public QueryRun submitHumanReview(Long projectId, String conversationId, String runId, HumanReviewCommand command) {
		ProjectConversation conversation = get(projectId, conversationId);
		QueryRun run = runService.get(runId);
		if (!Objects.equals(run.projectId(), projectId) || !Objects.equals(run.threadId(), conversationId)) {
			throw new IllegalArgumentException("Run does not belong to the project conversation: " + runId);
		}
		if (run.status() != QueryRun.RunStatus.WAITING_HUMAN) {
			throw new IllegalStateException("Run is not waiting for human review");
		}
		String idempotencyKey = required(command.idempotencyKey(), "idempotencyKey");
		String feedback = command.feedback() == null ? "" : command.feedback().trim();
		if (!command.approved() && feedback.isBlank()) {
			throw new IllegalArgumentException("feedback is required when rejecting a plan");
		}
		GraphRequest graphRequest = GraphRequest.builder()
			.projectId(projectId)
			.threadId(conversationId)
			.principalId(runtimePrincipalResolver.resolve(run))
			.runId(runId)
			.requestId(run.requestId())
			.idempotencyKey(idempotencyKey)
			.query(null)
			.humanFeedback(true)
			.humanFeedbackContent(command.approved() && feedback.isBlank() ? "批准执行计划" : feedback)
			.rejectedPlan(!command.approved())
			.build();
		startGraphAfterCommit(graphRequest);
		return runService.get(runId);
	}

	@Transactional
	public ProjectMessage synchronizeAssistantMessage(Long projectId, String conversationId, String runId) {
		get(projectId, conversationId);
		QueryRun run = runService.get(runId);
		var artifact = multiSourceRunService.mergedArtifact(runId).orElse(null);
		String status = run.status().name();
		String content;
		if (run.status() == QueryRun.RunStatus.WAITING_HUMAN) {
			content = waitingHumanContent(run);
		}
		else if (run.terminal()) {
			content = queryTaskRepository.enabled(runId) ? requestSynthesis(run.runId()).orElseGet(() -> terminalContent(run))
					: artifact == null ? terminalContent(run) : "查询已完成，返回 " + artifact.rowCount() + " 行结果。";
		}
		else {
			content = queryTaskRepository.enabled(runId) ? requestProgressContent(run.runId())
					: "任务正在运行，可通过 Run 事件接口断点续传。";
		}
		LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("eventApi", "/api/semevosql/runs/" + runId + "/events");
		metadata.put("executionExplanation", executionExplanationService.explain(run));
		if (queryTaskRepository.enabled(runId)) {
			metadata.put("queryTasks", queryTaskRepository.list(runId));
		}
		metadata.put("approvalMode", requestApprovalMode(run));
		var upgradePrompts = userSemanticPreferenceService.finalizeSuccessfulRun(runId);
		if (!upgradePrompts.isEmpty()) {
			metadata.put("semanticPreferenceUpgradePrompts", upgradePrompts);
		}
		if (artifact != null) {
			metadata.put("artifactId", artifact.artifactId());
			metadata.put("artifactApi",
					"/api/semevosql/runs/" + runId + "/artifacts/" + artifact.artifactId());
			metadata.put("rowCount", artifact.rowCount());
		}
		jdbcTemplate.update("""
				UPDATE qw_project_message
				SET status = ?, content = ?, metadata_json = ?, update_time = CURRENT_TIMESTAMP
				WHERE conversation_id = ? AND run_id = ? AND role = 'ASSISTANT'
				""", status, content, json(metadata), conversationId, runId);
		return jdbcTemplate.query("""
				SELECT * FROM qw_project_message
				WHERE conversation_id = ? AND run_id = ? AND role = 'ASSISTANT'
				""", MESSAGE_MAPPER, conversationId, runId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Assistant message not found for run: " + runId));
	}

	private boolean reconcileAssistantMessages(Long projectId, String conversationId, List<ProjectMessage> messages) {
		boolean changed = false;
		for (ProjectMessage message : messages) {
			if (message.role() != MessageRole.ASSISTANT || message.runId() == null || message.runId().isBlank()) {
				continue;
			}
			QueryRun run = runService.get(message.runId());
			if (!Objects.equals(run.projectId(), projectId) || !Objects.equals(run.threadId(), conversationId)) {
				throw new IllegalStateException("Conversation message is bound to an unrelated Run: " + message.runId());
			}
			if (!run.status().name().equals(message.status())) {
				synchronizeAssistantMessage(projectId, conversationId, run.runId());
				changed = true;
			}
		}
		return changed;
	}

	private String waitingHumanContent(QueryRun run) {
		try {
			var clarification = runtimeClarificationService.getPending(run.runId());
			if (clarification != null && StringUtils.hasText(clarification.question())) {
				return clarification.question();
			}
		}
		catch (IllegalArgumentException noPendingClarification) {
			// WAITING_HUMAN may represent request-level approval instead of semantic clarification.
		}
		try {
			RunEvent understanding = runService.latestEvent(run.runId(), "QUERY_UNDERSTANDING_READY");
			if (understanding != null && StringUtils.hasText(understanding.payload())) {
				return understanding.payload() + "\n\n请批准执行，或直接用自然语言说明需要修改的业务口径。";
			}
		}
		catch (IllegalArgumentException missingUnderstanding) {
			// Fall through to the generic durable waiting message.
		}
		return "任务已暂停，等待运行时语义澄清或查询理解审批。";
	}

	private String requestProgressContent(String runId) {
		List<QueryTask> tasks = queryTaskRepository.list(runId);
		long completed = tasks.stream().filter(task -> task.status() == QueryTask.TaskStatus.DONE).count();
		return "查询任务正在串行执行：已完成 " + completed + "/" + tasks.size() + "。可通过 Run 事件接口断点续传。";
	}

	private Optional<String> requestSynthesis(String runId) {
		try {
			RunEvent event = runService.latestEvent(runId, "REQUEST_SYNTHESIS");
			return event == null || !StringUtils.hasText(event.payload()) ? Optional.empty() : Optional.of(event.payload().trim());
		}
		catch (IllegalArgumentException missing) {
			return Optional.empty();
		}
	}

	private QueryApprovalMode requestApprovalMode(QueryRun run) {
		try {
			GraphRequest request = JsonUtil.getObjectMapper().readValue(run.requestPayload(), GraphRequest.class);
			return request.isHumanFeedback() ? QueryApprovalMode.REQUIRE_APPROVAL : QueryApprovalMode.AUTO_EXECUTE;
		}
		catch (Exception ex) {
			log.warn("Unable to read request approval mode for run {}: {}", run.runId(), ex.getMessage());
			return QueryApprovalMode.REQUIRE_APPROVAL;
		}
	}

	private String terminalContent(QueryRun run) {
		if (run.status() == QueryRun.RunStatus.SUCCEEDED) {
			Optional<String> report = terminalReport(run.runId());
			if (report.isPresent()) {
				return report.get();
			}
		}
		return terminalSummary(run);
	}

	private Optional<String> terminalReport(String runId) {
		StringBuilder report = new StringBuilder();
		long afterSequence = 0L;
		while (true) {
			List<RunEvent> events = runService.events(runId, afterSequence, 1000);
			if (events.isEmpty()) {
				break;
			}
			for (RunEvent event : events) {
				afterSequence = Math.max(afterSequence, event.sequence());
				if (!"NODE_OUTPUT".equals(event.eventType()) || !"ReportGeneratorNode".equals(event.nodeName())
						|| event.payload() == null || event.payload().isBlank()) {
					continue;
				}
				try {
					var payload = JsonUtil.getObjectMapper().readTree(event.payload());
					if ("MARK_DOWN".equals(payload.path("textType").asText())) {
						report.append(payload.path("text").asText(""));
					}
				}
				catch (Exception ex) {
					log.warn("Unable to parse durable report event {} for run {}: {}", event.sequence(), runId,
							ex.getMessage());
				}
			}
			if (events.size() < 1000) {
				break;
			}
		}
		String content = report.toString().trim();
		return content.isEmpty() ? Optional.empty() : Optional.of(content);
	}

	private ProjectConversation get(Long projectId, String conversationId) {
		return jdbcTemplate.query("""
				SELECT * FROM qw_project_conversation WHERE project_id = ? AND conversation_id = ?
				""", CONVERSATION_MAPPER, projectId, conversationId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Project conversation not found: " + conversationId));
	}

	private ProjectConversation lock(Long projectId, String conversationId) {
		return jdbcTemplate.query("""
				SELECT * FROM qw_project_conversation WHERE project_id = ? AND conversation_id = ? FOR UPDATE
				""", CONVERSATION_MAPPER, projectId, conversationId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Project conversation not found: " + conversationId));
	}

	private ProjectMessage requireMessage(String messageId) {
		return jdbcTemplate.query("SELECT * FROM qw_project_message WHERE message_id = ?", MESSAGE_MAPPER, messageId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Project message not found: " + messageId));
	}

	private long nextSequence(String conversationId) {
		Long sequence = jdbcTemplate.queryForObject("""
				SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM qw_project_message WHERE conversation_id = ?
				""", Long.class, conversationId);
		return sequence == null ? 1 : sequence;
	}

	private String terminalSummary(QueryRun run) {
		if (run.status() == QueryRun.RunStatus.SUCCEEDED) {
			return "任务已完成。请读取持久化 Run 事件和结果工件。";
		}
		if (run.status() == QueryRun.RunStatus.CANCELLED) {
			return "任务已取消。";
		}
		return runErrorPresenter.present(run).message();
	}

	private void startGraphAfterCommit(GraphRequest request) {
		Runnable start = () -> {
			try {
				Sinks.Many<ServerSentEvent<GraphNodeResponse>> detachedSink = Sinks.many().replay().limit(1);
				graphService.graphStreamProcess(detachedSink, request);
			}
			catch (RuntimeException ex) {
				log.error("Unable to start durable conversation run {}", request.getRunId(), ex);
				QueryRun run = runService.get(request.getRunId());
				if (!run.terminal() && run.status() != QueryRun.RunStatus.WAITING_HUMAN) {
					runService.transition(run.runId(), QueryRun.RunStatus.FAILED, "conversation-dispatch",
							"GRAPH_DISPATCH_FAILED", ex.getMessage());
					runService.appendEvent(run.runId(), "RUN_FAILED", "conversation-dispatch", null,
							"Unable to dispatch durable graph execution: " + ex.getMessage(),
							"run-failed:conversation-dispatch:" + run.runId());
				}
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			start.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				start.run();
			}
		});
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize conversation metadata", ex);
		}
	}

	private void assertNoActiveRun(String conversationId) {
		List<String> activeRunIds = jdbcTemplate.queryForList("""
				SELECT run_id FROM qw_query_run
				WHERE thread_id = ? AND status IN ('QUEUED','RUNNING','WAITING_HUMAN','CANCEL_REQUESTED')
				ORDER BY create_time
				""", String.class, conversationId);
		if (!activeRunIds.isEmpty()) {
			throw new IllegalStateException("Conversation already has an active run: " + activeRunIds.get(0));
		}
	}

	private String requestFingerprint(String conversationId, Long projectVersionId, String question,
			QueryApprovalMode approvalMode) {
		// Preserve the historical normal-query fingerprint shape so pre-upgrade retries remain idempotent.
		// The fixed sentinel exists only for hash compatibility and is not a runtime mode.
		String base = String.join("\n", conversationId, String.valueOf(projectVersionId), question, Boolean.FALSE.toString());
		return sha256(approvalMode == QueryApprovalMode.REQUIRE_APPROVAL ? base : base + "\n" + approvalMode.name());
	}

	private String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private String normalizeTitle(String title) {
		return title == null || title.isBlank() ? "新对话" : title.trim();
	}

	private String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private static final RowMapper<ProjectConversation> CONVERSATION_MAPPER = (rs, rowNum) -> new ProjectConversation(
			rs.getString("conversation_id"), rs.getLong("project_id"), rs.getLong("project_version_id"),
			rs.getString("title"), ConversationStatus.valueOf(rs.getString("status")), rs.getString("created_by"),
			rs.getLong("revision"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

	private static final RowMapper<ProjectMessage> MESSAGE_MAPPER = (rs, rowNum) -> new ProjectMessage(
			rs.getString("message_id"), rs.getString("conversation_id"), rs.getLong("sequence_no"),
			MessageRole.valueOf(rs.getString("role")), rs.getString("content"), rs.getString("run_id"),
			rs.getString("status"), rs.getString("metadata_json"), rs.getString("idempotency_key"),
			rs.getString("request_fingerprint"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

	private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public enum ConversationStatus {

		ACTIVE, ARCHIVED, DELETED

	}

	public enum MessageRole {

		USER, ASSISTANT, SYSTEM

	}

	public record ProjectConversation(String conversationId, Long projectId, Long projectVersionId, String title,
			ConversationStatus status, String createdBy, long revision, LocalDateTime createTime,
			LocalDateTime updateTime) {
	}

	public record ProjectMessage(String messageId, String conversationId, long sequenceNo, MessageRole role,
			String content, String runId, String status, String metadataJson, String idempotencyKey,
			String requestFingerprint, LocalDateTime createTime, LocalDateTime updateTime) {
	}

	public record ConversationView(ProjectConversation conversation, List<ProjectMessage> messages) {
	}

	public record SendMessageCommand(String content, String idempotencyKey, String requestId,
			QueryApprovalMode approvalMode) {
		public SendMessageCommand(String content, String idempotencyKey, String requestId) {
			this(content, idempotencyKey, requestId, null);
		}

		public QueryApprovalMode effectiveApprovalMode() {
			return approvalMode == null ? QueryApprovalMode.REQUIRE_APPROVAL : approvalMode;
		}
	}

	public record HumanReviewCommand(boolean approved, String feedback, String idempotencyKey) {
	}

	public record SendMessageResult(ProjectMessage userMessage, QueryRun run) {
	}

}
