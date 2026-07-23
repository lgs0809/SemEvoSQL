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
package cn.lgs.semevosql.external.mcp;

import cn.lgs.semevosql.clarification.RuntimeClarification;
import cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationOption;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.clarification.RuntimeClarificationService.AnswerCommand;
import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageCommand;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageResult;
import cn.lgs.semevosql.conversation.QueryApprovalMode;
import cn.lgs.semevosql.dto.GraphRequest;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService.EpisodeSnapshot;
import cn.lgs.semevosql.episode.domain.EpisodeRelationType;
import cn.lgs.semevosql.episode.domain.EpisodeTurnType;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.multisource.MultiSourceRunService.ResultArtifact;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectRuntimeProfileService;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunErrorPresenter;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.service.graph.GraphService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.vo.GraphNodeResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;

/** Stable project-scoped MCP adapter over the shared durable Episode/Run application model. */
@Service
@RequiredArgsConstructor
public class ProjectMcpQueryFacade {

    private static final List<String> CUSTOM_ANSWER_CODES = List.of("SPECIFY_TIME_RANGE", "REFRAME_IN_SCOPE", "OTHER");

    private final ProjectMcpRepository repository;

    private final ProjectConversationService conversationService;

    private final EpisodeApplicationService episodeService;

    private final RuntimeClarificationService clarificationService;

    private final QueryRunService runService;

    private final QueryRunErrorPresenter runErrorPresenter;

    private final SemEvoSQLProductionService productionService;

    private final ProjectRuntimeGate runtimeGate;

    private final ProjectRuntimeProfileService runtimeProfileService;

    private final SemanticProjectRepository projectRepository;

    private final ExecutionSnapshotService executionSnapshotService;

    private final GraphService graphService;

    private final MultiSourceRunService multiSourceRunService;

    private final JdbcTemplate jdbc;

    /**
     * Submit a new business query, continue an input-required Episode, or reopen a completed Episode
     * for a correction. requestId is the transport-independent idempotency key.
     */
    @Transactional
    public McpQueryResult query(ProjectMcpDeployment deployment, QueryCommand command) {
        Objects.requireNonNull(deployment, "MCP deployment is required");
        String input = required(command.input(), "input");
        String requestId = StringUtils.hasText(command.requestId()) ? command.requestId().trim()
                : UUID.randomUUID().toString();
        if (StringUtils.hasText(command.episodeId()) && StringUtils.hasText(command.parentEpisodeId())) {
            throw new IllegalArgumentException("episodeId and parentEpisodeId are mutually exclusive");
        }
        String fingerprint = fingerprint(deployment.deploymentId(), requestId, input, command.episodeId(),
                command.parentEpisodeId());
        ProjectMcpRepository.ExternalQueryHandle previous = repository
            .findHandleByIdempotency(deployment.deploymentId(), requestId).orElse(null);
        if (previous != null) {
            assertSameRequest(previous, fingerprint);
            return resultForHandle(deployment, previous);
        }

        String queryId = UUID.randomUUID().toString();
        int reserved = repository.reserveHandle(queryId, deployment.deploymentId(), deployment.projectId(), requestId,
                requestId, fingerprint, input);
        if (reserved == 0) {
            ProjectMcpRepository.ExternalQueryHandle concurrent = repository
                .findHandleByIdempotency(deployment.deploymentId(), requestId)
                .orElseThrow(() -> new IllegalStateException("Idempotent MCP admission lost the conflicting handle"));
            assertSameRequest(concurrent, fingerprint);
            return resultForHandle(deployment, concurrent);
        }

        Submission submission;
        if (StringUtils.hasText(command.episodeId())) {
            submission = continueEpisode(deployment, command.episodeId().trim(), input, requestId);
        }
        else {
            submission = createEpisode(deployment, command.parentEpisodeId(), input, requestId);
        }
        repository.submitHandle(queryId, submission.conversationId(), submission.run().runId(),
                submission.run().episodeId(), submission.run().projectVersionId());
        repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(), "query",
                "ACCEPTED", "episodeId=" + submission.run().episodeId() + ", runId=" + submission.run().runId());
        return status(deployment, submission.run().episodeId());
    }

    /** Durable polling fallback for clients without MCP Tasks support. */
    @Transactional
    public McpQueryResult status(ProjectMcpDeployment deployment, String episodeId) {
        Objects.requireNonNull(deployment, "MCP deployment is required");
        EpisodeSnapshot episode = requireEpisode(deployment, required(episodeId, "episodeId"));
        QueryRun run = latestRun(episode.episodeId());
        VersionInfo version = versionInfo(episode.semanticVersionId());
        if (run.status() == RunStatus.WAITING_HUMAN) {
            RuntimeClarification clarification = clarificationService.getPending(run.runId());
            return new McpQueryResult(episode.episodeId(), "INPUT_REQUIRED", version.versionNumber(),
                    version.corpusRevision(), null, null, List.of(), null, run.runId(), run.attemptId(),
                    new ClarificationOutput(clarification.clarificationId(), clarification.question(),
                            clarification.options()),
                    null);
        }
        ResultArtifact artifact = multiSourceRunService.mergedArtifact(run.runId()).orElse(null);
        Object result = artifact == null ? null : parseJsonObject(artifact.dataJson());
        String answer = resolvedAnswer(deployment, episode, run);
        String sql = executedSql(run.runId());
        String mappedStatus = switch (run.status()) {
            case SUCCEEDED -> "COMPLETED";
            case QUEUED, RUNNING -> "RUNNING";
            case FAILED -> "FAILED";
            case CANCEL_REQUESTED -> "CANCEL_REQUESTED";
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case WAITING_HUMAN -> "INPUT_REQUIRED";
        };
        return new McpQueryResult(episode.episodeId(), mappedStatus, version.versionNumber(), version.corpusRevision(),
                answer, sql, evidence(run.runId()), result, run.runId(), run.attemptId(), null,
                errorOutput(run.errorCode(), run.errorMessage()));
    }

    private Submission createEpisode(ProjectMcpDeployment deployment, String parentEpisodeId, String input,
            String requestId) {
        String conversationId;
        SendMessageResult sent;
        SendMessageCommand message = new SendMessageCommand(input, requestId, requestId, QueryApprovalMode.AUTO_EXECUTE);
        if (StringUtils.hasText(parentEpisodeId)) {
            EpisodeSnapshot parent = requireEpisode(deployment, parentEpisodeId.trim());
            conversationId = StringUtils.hasText(parent.conversationId()) ? parent.conversationId()
                    : conversationService.create(deployment.projectId(), title(input), deployment.principalId())
                        .conversationId();
            sent = conversationService.sendAsChild(deployment.projectId(), conversationId, message,
                    deployment.principalId(), parent.episodeId(), EpisodeRelationType.NEW_GOAL);
        }
        else {
            conversationId = conversationService.create(deployment.projectId(), title(input), deployment.principalId())
                .conversationId();
            sent = conversationService.send(deployment.projectId(), conversationId, message, deployment.principalId());
        }
        QueryRun run = sent.run();
        if (run == null || !StringUtils.hasText(run.episodeId())) {
            throw new IllegalStateException("MCP query admission did not bind a durable Episode");
        }
        return new Submission(conversationId, run);
    }

    private Submission continueEpisode(ProjectMcpDeployment deployment, String episodeId, String input,
            String requestId) {
        EpisodeSnapshot episode = requireEpisode(deployment, episodeId);
        QueryRun latest = latestRun(episodeId);
        if (latest.status() == RunStatus.WAITING_HUMAN) {
            RuntimeClarification clarification = clarificationService.getPending(latest.runId());
            SelectedAnswer answer = selectAnswer(clarification, input);
            clarificationService.answer(latest.runId(), clarification.clarificationId(),
                    new AnswerCommand(clarification.revision(), requestId, answer.optionCode(), answer.customAnswer(),
                            deployment.principalId()));
            return new Submission(episode.conversationId(), runService.get(latest.runId()));
        }
        if (!latest.terminal()) {
            throw new IllegalStateException("Episode is still running; poll query_status before sending more input");
        }
        ProjectRuntimeContext context = runtimeGate.requireReadyVersion(deployment.projectId(),
                episode.semanticVersionId());
        SemanticProject project = projectRepository.findProject(deployment.projectId())
            .orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + deployment.projectId()));
        ProjectRuntimeProfile profile = runtimeProfileService.resolveOrCreate(project);
        String threadId = StringUtils.hasText(episode.conversationId()) ? episode.conversationId()
                : "mcp-episode:" + episode.episodeId();
        String runIdempotency = "mcp-episode:" + episode.episodeId() + ":" + requestId;
        GraphRequest graphRequest = GraphRequest.builder()
            .projectId(deployment.projectId())
            .agentId("project:" + deployment.projectId() + ":" + profile.getRuntimeProfileId())
            .threadId(threadId)
            .requestId(requestId)
            .idempotencyKey(runIdempotency)
            .principalId(deployment.principalId())
            .query(input)
            .humanFeedback(false)
            .build();
        String snapshot = executionSnapshotService.capture(context, profile, null, false);
        QueryRun run = runService.create(new CreateRunCommand(RunType.INTERACTIVE_QUERY, deployment.projectId(),
                episode.semanticVersionId(), threadId, requestId, runIdempotency, json(graphRequest), snapshot));
        graphRequest.setRunId(run.runId());
        SemEvoSQLProductionService.ExecutionBinding binding = productionService.createNextAttemptAndBind(run.runId(),
                episode.episodeId(), threadId, EpisodeTurnType.CORRECTION, input, requestId);
        dispatchAfterCommit(graphRequest);
        return new Submission(episode.conversationId(), binding.run());
    }

    private McpQueryResult resultForHandle(ProjectMcpDeployment deployment,
            ProjectMcpRepository.ExternalQueryHandle handle) {
        if (!Objects.equals(handle.projectId(), deployment.projectId())) {
            throw new IllegalArgumentException("MCP query handle belongs to another project");
        }
        if (!StringUtils.hasText(handle.episodeId())) {
            return new McpQueryResult(null, "RUNNING", null, null, null, null, List.of(), null, handle.runId(), null,
                    null, StringUtils.hasText(handle.lastError())
                            ? new ErrorOutput("QUERY_EXECUTION_FAILED",
                                    "Query submission failed before durable execution started.", true)
                            : null);
        }
        return status(deployment, handle.episodeId());
    }

    private EpisodeSnapshot requireEpisode(ProjectMcpDeployment deployment, String episodeId) {
        EpisodeSnapshot episode = episodeService.get(episodeId);
        if (!Objects.equals(episode.projectId(), deployment.projectId())) {
            throw new IllegalArgumentException("Episode belongs to another semantic project");
        }
        return episode;
    }

    private QueryRun latestRun(String episodeId) {
        String runId = jdbc.query("""
                SELECT run_id FROM qw_attempt
                WHERE episode_id = ? AND run_id IS NOT NULL
                ORDER BY attempt_no DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), episodeId).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Episode has no durable Run: " + episodeId));
        return runService.get(runId);
    }

    private VersionInfo versionInfo(Long semanticVersionId) {
        return jdbc.query("""
                SELECT v.version_number, r.revision_no
                FROM qw_project_version v
                LEFT JOIN qw_corpus_revision r ON r.id = v.corpus_revision_id
                WHERE v.id = ?
                """, (rs, rowNum) -> new VersionInfo(rs.getString("version_number"), nullableLong(rs, "revision_no")),
                semanticVersionId).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Semantic Version not found: " + semanticVersionId));
    }

    private SelectedAnswer selectAnswer(RuntimeClarification clarification, String input) {
        String normalized = input.trim();
        for (ClarificationOption option : clarification.options()) {
            if (equalsIgnoreCase(normalized, option.code()) || equalsIgnoreCase(normalized, option.label())
                    || equalsIgnoreCase(normalized, option.value())) {
                if (CUSTOM_ANSWER_CODES.contains(option.code())) {
                    throw new IllegalArgumentException("Clarification option " + option.code()
                            + " requires the actual custom answer rather than only the option code/label");
                }
                return new SelectedAnswer(option.code(), null);
            }
        }
        for (String code : CUSTOM_ANSWER_CODES) {
            boolean supported = clarification.options().stream().anyMatch(option -> code.equals(option.code()));
            if (supported) {
                return new SelectedAnswer(code, normalized);
            }
        }
        throw new IllegalArgumentException("Input does not match any clarification option");
    }

    String resolvedAnswer(ProjectMcpDeployment deployment, EpisodeSnapshot episode, QueryRun run) {
        if (run.terminal() && StringUtils.hasText(episode.conversationId())) {
            return conversationService
                .synchronizeAssistantMessage(deployment.projectId(), episode.conversationId(), run.runId())
                .content();
        }
        return assistantAnswer(run.runId());
    }

    private String assistantAnswer(String runId) {
        return jdbc.query("""
                SELECT content FROM qw_project_message
                WHERE run_id = ? AND role = 'ASSISTANT'
                ORDER BY sequence_no DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), runId).stream().findFirst().orElse(null);
    }

    private String executedSql(String runId) {
        return jdbc.query("""
                SELECT sql_text FROM qw_source_sub_run
                WHERE run_id = ? AND sql_text IS NOT NULL
                ORDER BY update_time DESC, datasource_id LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), runId).stream().findFirst().orElse(null);
    }

    private List<Map<String, Object>> evidence(String runId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("""
                SELECT event_type, node_name, payload_summary, sequence
                FROM qw_run_event
                WHERE run_id = ? AND event_type IN ('SEMANTIC_PLANNING_COMPLETED', 'SQL_EXECUTED', 'RUN_SUCCEEDED')
                ORDER BY sequence
                LIMIT 20
                """, runId));
        return List.copyOf(rows);
    }

    private void dispatchAfterCommit(GraphRequest graphRequest) {
        Runnable dispatch = () -> {
            Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().multicast().onBackpressureBuffer(16, false);
            graphService.graphStreamProcess(sink, graphRequest);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        }
        else {
            dispatch.run();
        }
    }

    private void assertSameRequest(ProjectMcpRepository.ExternalQueryHandle handle, String fingerprint) {
        if (!Objects.equals(handle.requestFingerprint(), fingerprint)) {
            throw new IllegalArgumentException("requestId is already bound to a different MCP query");
        }
    }

    private Object parseJsonObject(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return JsonUtil.getObjectMapper().readValue(value, Object.class);
        }
        catch (Exception ex) {
            return value;
        }
    }

    private String json(Object value) {
        try {
            return JsonUtil.getObjectMapper().writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize MCP durable request", ex);
        }
    }

    private String fingerprint(String deploymentId, String requestId, String input, String episodeId,
            String parentEpisodeId) {
        String canonical = deploymentId + "\n" + requestId + "\n" + input + "\n"
                + Objects.toString(episodeId, "") + "\n" + Objects.toString(parentEpisodeId, "");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String title(String input) {
        String normalized = input.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(80, normalized.length()));
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return right != null && left.equalsIgnoreCase(right.trim());
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private ErrorOutput errorOutput(String code, String message) {
        if (!StringUtils.hasText(code) && !StringUtils.hasText(message)) {
            return null;
        }
        QueryRunErrorPresenter.ErrorPresentation presented = runErrorPresenter.present(code);
        return new ErrorOutput(presented.code(), presented.message(), presented.retryable());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Submission(String conversationId, QueryRun run) {
    }

    private record VersionInfo(String versionNumber, Long corpusRevision) {
    }

    private record SelectedAnswer(String optionCode, String customAnswer) {
    }

    public record QueryCommand(String input, String episodeId, String parentEpisodeId, String requestId) {
    }

    public record ClarificationOutput(String id, String question, List<ClarificationOption> options) {
    }

    public record ErrorOutput(String code, String message, boolean retryable) {
    }

    public record McpQueryResult(String episodeId, String status, String semanticVersion, Long corpusRevision,
            String answer, String sql, List<Map<String, Object>> evidence, Object result, String runId, String attemptId,
            ClarificationOutput clarification, ErrorOutput error) {
    }
}
