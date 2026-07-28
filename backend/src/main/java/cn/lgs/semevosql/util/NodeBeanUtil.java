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
package cn.lgs.semevosql.util;

import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.run.RunExecutionFenceService.ExecutionToken;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 管理Node Bean
 *
 * @since 2025/9/28
 */
@Component
@AllArgsConstructor
public class NodeBeanUtil {

	private final ApplicationContext context;

	private final RunExecutionFenceService executionFence;

	public <T extends NodeAction> NodeAction getNodeBean(Class<T> clazz) {
		NodeAction delegate = context.getBean(clazz);
		return state -> {
			ExecutionToken token = executionFence.assertActive(state);
			var result = delegate.apply(state);
			// A blocking node may outlive Reactor cancellation. Re-check after it returns so its late output cannot
			// advance the Graph or be rebound to a recovered attempt.
			executionFence.assertActive(token);
			return result;
		};
	}

	public <T extends NodeAction> AsyncNodeAction getNodeBeanAsync(Class<T> clazz) {
		return AsyncNodeAction.node_async(getNodeBean(clazz));
	}

	public <T extends EdgeAction> EdgeAction getEdgeBean(Class<T> clazz) {
		return context.getBean(clazz);
	}

	public <T extends EdgeAction> AsyncEdgeAction getEdgeBeanAsync(Class<T> clazz) {
		return AsyncEdgeAction.edge_async(getEdgeBean(clazz));
	}

}
