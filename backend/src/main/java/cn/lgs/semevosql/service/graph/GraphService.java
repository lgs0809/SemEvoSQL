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
package cn.lgs.semevosql.service.graph;

import cn.lgs.semevosql.dto.GraphRequest;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import cn.lgs.semevosql.vo.GraphNodeResponse;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * @since 2025/10/30
 */
public interface GraphService {

	String generateSqlForProjectSource(String naturalQuery, Long projectId, Integer datasourceId,
			List<String> physicalTables, String requestId, String idempotencyKey) throws GraphRunnerException;

	/**
	 * 流式处理 SemEvoSQL 请求。
	 * @param sink 输出Sink
	 * @param graphRequest 请求体
	 */
	String graphStreamProcess(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest graphRequest);

	/**
	 * 停止指定 threadId 的流式处理
	 * @param threadId 线程ID
	 */
	void stopStreamProcessing(String threadId);

}
