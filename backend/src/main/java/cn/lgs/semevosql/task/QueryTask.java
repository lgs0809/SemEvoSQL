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
package cn.lgs.semevosql.task;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Lightweight optional Todo for one independent answer goal.
 *
 * <p>It deliberately does not mirror Graph phases such as planning/executing/reviewing. Those phases belong to the
 * durable QueryRun/Graph state. A simple request has no QueryTask rows at all.</p>
 */
public record QueryTask(String taskId, int ordinal, String question, List<String> dependencies, TaskStatus status) {

	public QueryTask {
		if (!StringUtils.hasText(taskId)) {
			throw new IllegalArgumentException("taskId is required");
		}
		if (ordinal < 0) {
			throw new IllegalArgumentException("ordinal must be >= 0");
		}
		if (!StringUtils.hasText(question)) {
			throw new IllegalArgumentException("question is required");
		}
		taskId = taskId.trim();
		question = question.trim();
		dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
		status = status == null ? TaskStatus.PENDING : status;
	}

	public QueryTask withStatus(TaskStatus next) {
		return new QueryTask(taskId, ordinal, question, dependencies, next);
	}

	public enum TaskStatus {
		PENDING,
		ACTIVE,
		DONE,
		FAILED,
		SKIPPED
	}
}
