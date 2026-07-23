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
package cn.lgs.semevosql.vo;

import cn.lgs.semevosql.enums.TextType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphNodeResponse {

	private String agentId;

	private String threadId;

	private String runId;

	// 使用Constant常量
	private String nodeName;

	private TextType textType;

	private String text;

	@Builder.Default
	private boolean error = false;

	@Builder.Default
	private boolean complete = false;

	public static GraphNodeResponse error(String agentId, String threadId, String text) {
		return error(agentId, threadId, null, text);
	}

	public static GraphNodeResponse error(String agentId, String threadId, String runId, String text) {
		return GraphNodeResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.runId(runId)
			.text(text)
			.error(true)
			.textType(TextType.TEXT)
			.build();
	}

	public static GraphNodeResponse complete(String agentId, String threadId) {
		return complete(agentId, threadId, null);
	}

	public static GraphNodeResponse complete(String agentId, String threadId, String runId) {
		return GraphNodeResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.runId(runId)
			.complete(true)
			.textType(TextType.TEXT)
			.build();
	}

}
