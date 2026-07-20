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

import cn.lgs.semevosql.dto.GraphRequest;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.service.graph.GraphService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.vo.GraphNodeResponse;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;

/**
 * Immediately resumes a clarification-unblocked interactive run after commit. The normal
 * durable recovery scanner remains the fallback when capacity or process failures prevent
 * immediate dispatch.
 */
@Slf4j
@Component
public class RuntimeClarificationResumeDispatcher {

	private final QueryRunService runService;

	private final GraphService graphService;

	private final Executor resumeExecutor;

	private final Set<String> scheduledResumes = ConcurrentHashMap.newKeySet();

	public RuntimeClarificationResumeDispatcher(QueryRunService runService, GraphService graphService,
			@Qualifier("semEvoSQLInteractiveExecutor") Executor resumeExecutor) {
		this.runService = runService;
		this.graphService = graphService;
		this.resumeExecutor = resumeExecutor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onResumeRequested(RuntimeClarificationResumeRequestedEvent event) {
		if (event == null || !StringUtils.hasText(event.runId())) {
			return;
		}
		dispatch(event.runId());
	}

	@Scheduled(fixedDelayString = "${semevosql.run.recovery-scan-ms:10000}")
	public void recoverPlanningClarifications() {
		runService.recoverable()
			.stream()
			.filter(run -> run.runType() == RunType.INTERACTIVE_QUERY)
			.filter(run -> run.status() == RunStatus.QUEUED)
			.filter(run -> "semantic-planning-clarification".equals(run.currentNode()))
			.forEach(run -> dispatch(run.runId()));
	}

	private void dispatch(String runId) {
		if (!scheduledResumes.add(runId)) {
			return;
		}
		try {
			resumeExecutor.execute(() -> {
				try {
					resume(runId);
				}
				finally {
					scheduledResumes.remove(runId);
				}
			});
		}
		catch (RuntimeException ex) {
			scheduledResumes.remove(runId);
			log.warn("Unable to schedule clarification resume for run {}; durable recovery scanner will retry: {}",
					runId, ex.getMessage());
		}
	}

	private void resume(String runId) {
		try {
			QueryRun run = runService.get(runId);
			if (run.status() != RunStatus.QUEUED || run.terminal()) {
				return;
			}
			String payload = StringUtils.hasText(run.recoveryPayload()) ? run.recoveryPayload() : run.requestPayload();
			if (!StringUtils.hasText(payload)) {
				log.warn("Clarification-unblocked run {} has no durable request payload; recovery scanner cannot resume it",
						run.runId());
				return;
			}
			GraphRequest request = JsonUtil.getObjectMapper().readValue(payload, GraphRequest.class);
			request.setRunId(run.runId());
			Sinks.Many<ServerSentEvent<GraphNodeResponse>> detachedSink = Sinks.many()
				.multicast()
				.onBackpressureBuffer(16, false);
			graphService.graphStreamProcess(detachedSink, request);
		}
		catch (Exception ex) {
			log.warn("Immediate clarification resume for run {} failed; durable recovery scanner will retry: {}", runId,
					ex.getMessage());
		}
	}

}
