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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.conversation.ProjectConversationService.MessageRole;
import cn.lgs.semevosql.conversation.ProjectConversationService.ProjectMessage;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService.EpisodeSnapshot;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import org.junit.jupiter.api.Test;

class ProjectMcpQueryFacadeTest {

    @Test
    void terminalStatusUsesReconciledAssistantMessageInsteadOfSubmissionPlaceholder() {
        ProjectConversationService conversationService = mock(ProjectConversationService.class);
        ProjectMcpQueryFacade facade = new ProjectMcpQueryFacade(null, conversationService, null, null, null, null, null,
                null, null, null, null, null, null, null);
        ProjectMcpDeployment deployment = new ProjectMcpDeployment("deployment-1", 12L, "mcp:12",
                ProjectMcpDeployment.Status.RUNNING, "http://127.0.0.1:28065/mcp", "operator", null, null, null, null);
        EpisodeSnapshot episode = new EpisodeSnapshot("episode-1", "request-1", "agent", 12L, "conversation-1", null,
                null, 101L, "state-hash", null, "question", "question", "COMPLETED", "SUCCEEDED", "idem", "fp",
                "attempt-1", null, null, null);
        QueryRun run = QueryRun.builder()
            .runId("run-1")
            .projectId(12L)
            .projectVersionId(101L)
            .episodeId("episode-1")
            .attemptId("attempt-1")
            .threadId("conversation-1")
            .status(RunStatus.SUCCEEDED)
            .build();
        ProjectMessage terminalMessage = new ProjectMessage("message-1", "conversation-1", 2L, MessageRole.ASSISTANT,
                "最终答案", "run-1", "SUCCEEDED", "{}", "idem", "fp", null, null);
        when(conversationService.synchronizeAssistantMessage(12L, "conversation-1", "run-1"))
            .thenReturn(terminalMessage);

        assertEquals("最终答案", facade.resolvedAnswer(deployment, episode, run));
        verify(conversationService).synchronizeAssistantMessage(12L, "conversation-1", "run-1");
    }
}
