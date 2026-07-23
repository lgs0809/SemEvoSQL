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
package cn.lgs.semevosql.service.mcp;

import cn.lgs.semevosql.clarification.RuntimeClarification;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.clarification.RuntimeClarificationService.AnswerCommand;
import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.conversation.ProjectConversationService.ProjectConversation;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageCommand;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageResult;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.multisource.MultiSourceRunService.ResultArtifact;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunEvent;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * Project-centered MCP facade sharing the same Conversation and Durable Run loop as the
 * browser.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "semevosql.mcp.enabled", havingValue = "true")
public class McpServerService {

	private final SemanticProjectRepository projectRepository;

	private final ProjectConversationService conversationService;

	private final QueryRunService runService;

	private final RuntimeClarificationService clarificationService;

	private final MultiSourceRunService multiSourceRunService;

	@Tool(description = "列出 SemEvoSQL 数据项目。返回项目标识、名称、业务域和当前激活发布版本。")
	public List<SemanticProject> listProjects() {
		return projectRepository.findProjects();
	}

	public record CreateConversationRequest(@JsonPropertyDescription("数据项目ID") Long projectId,
			@JsonPropertyDescription("会话标题；为空时使用新对话") String title,
			@JsonPropertyDescription("创建者标识") String createdBy) {
	}

	@Tool(description = "在已就绪的数据项目中创建问数会话。会话固定当前激活发布版本。")
	public ProjectConversation createConversation(CreateConversationRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.notNull(request.projectId(), "ProjectId cannot be null");
		Assert.hasText(request.createdBy(), "CreatedBy cannot be empty");
		return conversationService.create(request.projectId(), request.title(), request.createdBy());
	}

	public record SendProjectMessageRequest(@JsonPropertyDescription("数据项目ID") Long projectId,
			@JsonPropertyDescription("项目会话ID") String conversationId,
			@JsonPropertyDescription("本次请求主体标识") String principalId,
			@JsonPropertyDescription("自然语言问数内容") String naturalQuery,
			@JsonPropertyDescription("稳定请求ID；重试时必须复用") String requestId,
			@JsonPropertyDescription("稳定幂等键；重试时必须复用") String idempotencyKey) {
	}

	@Tool(description = "向项目会话发送问数消息。后端自动固定发布版本、选择数据源并创建可恢复 Durable Run。")
	public SendMessageResult sendProjectMessage(SendProjectMessageRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.notNull(request.projectId(), "ProjectId cannot be null");
		Assert.hasText(request.conversationId(), "ConversationId cannot be empty");
		Assert.hasText(request.principalId(), "PrincipalId cannot be empty");
		Assert.hasText(request.naturalQuery(), "Natural query cannot be empty");
		Assert.hasText(request.requestId(), "RequestId cannot be empty");
		Assert.hasText(request.idempotencyKey(), "IdempotencyKey cannot be empty");
		return conversationService.send(request.projectId(), request.conversationId(),
				new SendMessageCommand(request.naturalQuery(), request.idempotencyKey(), request.requestId()),
				request.principalId());
	}

	public record RunRequest(@JsonPropertyDescription("Durable Run ID") String runId) {
	}

	@Tool(description = "读取 Durable Run 当前状态、版本快照和错误信息。")
	public QueryRun getRun(RunRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.hasText(request.runId(), "RunId cannot be empty");
		return runService.get(request.runId());
	}

	public record RunEventsRequest(@JsonPropertyDescription("Durable Run ID") String runId,
			@JsonPropertyDescription("仅返回此序号之后的持久化事件") Long afterSequence,
			@JsonPropertyDescription("最多返回事件数，最大1000") Integer limit) {
	}

	@Tool(description = "断点读取 Durable Run 持久化事件。浏览器或MCP断线后使用最后序号继续读取。")
	public List<RunEvent> getRunEvents(RunEventsRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.hasText(request.runId(), "RunId cannot be empty");
		return runService.events(request.runId(), request.afterSequence() == null ? 0 : request.afterSequence(),
				request.limit() == null ? 200 : request.limit());
	}

	public record AnswerClarificationRequest(@JsonPropertyDescription("Durable Run ID") String runId,
			@JsonPropertyDescription("澄清问题ID") String clarificationId,
			@JsonPropertyDescription("澄清问题revision") Long revision,
			@JsonPropertyDescription("稳定幂等键") String idempotencyKey,
			@JsonPropertyDescription("选择的选项代码") String selectedOption,
			@JsonPropertyDescription("可选自定义回答") String customAnswer,
			@JsonPropertyDescription("回答者标识") String answeredBy) {
	}

	@Tool(description = "回答运行时语义澄清。回答写入同一个Run并允许其继续恢复。")
	public RuntimeClarification answerClarification(AnswerClarificationRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.hasText(request.runId(), "RunId cannot be empty");
		Assert.hasText(request.clarificationId(), "ClarificationId cannot be empty");
		Assert.notNull(request.revision(), "Revision cannot be null");
		return clarificationService.answer(request.runId(), request.clarificationId(),
				new AnswerCommand(request.revision(), request.idempotencyKey(), request.selectedOption(),
						request.customAnswer(), request.answeredBy()));
	}

	public record ResultArtifactRequest(@JsonPropertyDescription("Durable Run ID") String runId,
			@JsonPropertyDescription("结果工件ID") String artifactId) {
	}

	@Tool(description = "读取多数据源来源结果或最终合并结果工件。")
	public ResultArtifact getResultArtifact(ResultArtifactRequest request) {
		Assert.notNull(request, "Request cannot be null");
		Assert.hasText(request.runId(), "RunId cannot be empty");
		Assert.hasText(request.artifactId(), "ArtifactId cannot be empty");
		ResultArtifact artifact = multiSourceRunService.requireArtifact(request.artifactId());
		if (!request.runId().equals(artifact.runId())) {
			throw new IllegalArgumentException("Result artifact does not belong to run: " + request.runId());
		}
		return artifact;
	}

}
