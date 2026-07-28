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
package cn.lgs.semevosql.config;

import cn.lgs.semevosql.properties.CodeExecutorProperties;
import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.properties.FileStorageProperties;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.util.McpServerToolUtil;
import cn.lgs.semevosql.util.NodeBeanUtil;
import cn.lgs.semevosql.service.aimodelconfig.AiModelRegistry;
import cn.lgs.semevosql.workflow.dispatcher.*;
import cn.lgs.semevosql.workflow.node.*;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static cn.lgs.semevosql.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * SemEvoSQL 自动配置。
 *
 * @since 2025/9/28
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties({ CodeExecutorProperties.class, ConversationContextProperties.class,
		SemEvoSQLProperties.class, FileStorageProperties.class, ModelClientProperties.class,
		cn.lgs.semevosql.common.OperatorContextProperties.class })
public class SemEvoSQLConfiguration {

	@Bean
	@ConditionalOnMissingBean(RestClientCustomizer.class)
	public RestClientCustomizer restClientCustomizer(@Value("${rest.connect.timeout:600}") long connectTimeout,
			@Value("${rest.read.timeout:600}") long readTimeout) {
		return restClientBuilder -> restClientBuilder
			.requestFactory(ClientHttpRequestFactoryBuilder.reactor().withCustomizer(factory -> {
				factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
				factory.setReadTimeout(Duration.ofSeconds(readTimeout));
			}).build());
	}

	@Bean
	@ConditionalOnMissingBean(WebClient.Builder.class)
	public WebClient.Builder webClientBuilder(@Value("${webclient.response.timeout:600}") long responseTimeout) {

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(
					HttpClient.create().responseTimeout(Duration.ofSeconds(responseTimeout))));
	}

	@Bean
	public StateGraph nl2sqlGraph(NodeBeanUtil nodeBeanUtil, CodeExecutorProperties codeExecutorProperties)
			throws GraphStateException {

		KeyStrategyFactory keyStrategyFactory = () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			// User input and optional request-level Todo state
			keyStrategyHashMap.put(INPUT_KEY, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(ORIGINAL_REQUEST, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(REQUEST_ANALYSIS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TODO_ENABLED, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(ACTIVE_TODO_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(ACTIVE_QUERY, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PRINCIPAL_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(REQUEST_SEMANTIC_BINDINGS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(APPROVAL_REQUIRED, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TODO_BOUNDARY_DECISION, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SEMANTIC_EXECUTION_DECISION, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(ADVANCED_EXECUTION_FALLBACK, KeyStrategy.REPLACE);
			// Agent and immutable semantic project version context
			keyStrategyHashMap.put(AGENT_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PROJECT_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PROJECT_VERSION_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(DATASOURCE_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(FORCED_DATASOURCE_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(FORCED_PHYSICAL_TABLES, KeyStrategy.REPLACE);
			// Multi-turn context
			keyStrategyHashMap.put(MULTI_TURN_CONTEXT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(CONVERSATION_CONTEXT_ENVELOPE, KeyStrategy.REPLACE);
			// Intent recognition
			// QUERY_ENHANCE_NODE节点输出
			keyStrategyHashMap.put(QUERY_ENHANCE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Semantic model and deterministic Semantic Blueprint
			keyStrategyHashMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TYPED_SEMANTIC_PLAN, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(CATALOG_HASH, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(EPISODE_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(ATTEMPT_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(RUN_ID, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(RUN_DEADLINE_EPOCH_MILLIS, KeyStrategy.REPLACE);
			// EVIDENCE节点输出
			keyStrategyHashMap.put(EVIDENCE, KeyStrategy.REPLACE);
			// schema recall节点输出
			keyStrategyHashMap.put(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);
			// table relation节点输出
			keyStrategyHashMap.put(TABLE_RELATION_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TABLE_RELATION_EXCEPTION_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TABLE_RELATION_RETRY_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(DB_DIALECT_TYPE, KeyStrategy.REPLACE);
			// Feasibility Assessment 节点输出
			keyStrategyHashMap.put(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, KeyStrategy.REPLACE);
			// sql generate节点输出
			keyStrategyHashMap.put(SQL_GENERATE_SCHEMA_MISSING_ADVICE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_GENERATE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_PHYSICAL_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_DRY_PLAN_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_GENERATE_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_REGENERATE_REASON, KeyStrategy.REPLACE);
			// Semantic consistence节点输出
			keyStrategyHashMap.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Planner 节点输出
			keyStrategyHashMap.put(PLANNER_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(RECOVERED_PLANNER_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(APPROVED_PLAN_RECOVERY, KeyStrategy.REPLACE);
			// PlanExecutorNode
			keyStrategyHashMap.put(PLAN_CURRENT_STEP, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_NEXT_NODE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_VALIDATION_STATUS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_VALIDATION_ERROR, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_PARSED_OBJECT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_PARSED_OUTPUT_HASH, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_VALIDATED_OUTPUT_HASH, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_REPAIR_COUNT, KeyStrategy.REPLACE);
			// SQL Execute / post-execution review durable state
			keyStrategyHashMap.put(SQL_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_EXECUTED_QUERY_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(LAST_SQL_EXECUTED_STEP, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(LAST_SQL_RESULT_PAYLOAD, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(POST_EXECUTION_REVIEW_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(QUERY_REPAIR_BUDGET, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(RETRIEVAL_REPAIR_QUERY, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(RETRIEVAL_REPAIR_HINT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_RESULT_MEMORY_BY_STEP, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(FORCE_SEMANTIC_REPLAN, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SEMANTIC_REPLAN_FEEDBACK, KeyStrategy.REPLACE);
			// Python代码运行相关
			keyStrategyHashMap.put(SQL_RESULT_LIST_MEMORY, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_IS_SUCCESS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_TRIES_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_FALLBACK_MODE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_GENERATE_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_ANALYSIS_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Internal source-level SQL generation
			keyStrategyHashMap.put(SQL_GENERATION_ONLY, KeyStrategy.REPLACE);
			// Human Review keys
			keyStrategyHashMap.put(HUMAN_REVIEW_ENABLED, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(HUMAN_FEEDBACK_DATA, KeyStrategy.REPLACE);
			// Langfuse 追踪：threadId 透传
			keyStrategyHashMap.put(TRACE_THREAD_ID, KeyStrategy.REPLACE);
			// Final result
			keyStrategyHashMap.put(RESULT, KeyStrategy.REPLACE);
			return keyStrategyHashMap;
		};

		StateGraph stateGraph = new StateGraph(NL2SQL_GRAPH_NAME, keyStrategyFactory)
			.addNode(REQUEST_ANALYSIS_NODE, nodeBeanUtil.getNodeBeanAsync(RequestAnalysisNode.class))
			.addNode(QUERY_ENHANCE_NODE, nodeBeanUtil.getNodeBeanAsync(QueryEnhanceNode.class))
			.addNode(SCHEMA_RECALL_NODE, nodeBeanUtil.getNodeBeanAsync(SchemaRecallNode.class))
			.addNode(TABLE_RELATION_NODE, nodeBeanUtil.getNodeBeanAsync(TableRelationNode.class))
			.addNode(SEMANTIC_PLAN_NODE, nodeBeanUtil.getNodeBeanAsync(SemanticBlueprintNode.class))
			.addNode(SEMANTIC_EXECUTION_NODE, nodeBeanUtil.getNodeBeanAsync(SemanticExecutionNode.class))
			.addNode(SQL_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlGenerateNode.class))
			.addNode(PLANNER_NODE, nodeBeanUtil.getNodeBeanAsync(PlannerNode.class))
			.addNode(PLAN_EXECUTOR_NODE, nodeBeanUtil.getNodeBeanAsync(PlanExecutorNode.class))
			.addNode(SQL_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlExecuteNode.class))
			.addNode(POST_EXECUTION_REVIEW_NODE, nodeBeanUtil.getNodeBeanAsync(PostExecutionReviewNode.class))
			.addNode(PYTHON_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonGenerateNode.class))
			.addNode(PYTHON_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonExecuteNode.class))
			.addNode(PYTHON_ANALYZE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonAnalyzeNode.class))
			.addNode(REPORT_GENERATOR_NODE, nodeBeanUtil.getNodeBeanAsync(ReportGeneratorNode.class))
			.addNode(TODO_BOUNDARY_NODE, nodeBeanUtil.getNodeBeanAsync(TodoBoundaryNode.class))
			.addNode(REQUEST_SYNTHESIS_NODE, nodeBeanUtil.getNodeBeanAsync(RequestSynthesisNode.class))
			.addNode(SEMANTIC_CONSISTENCY_NODE, nodeBeanUtil.getNodeBeanAsync(SemanticConsistencyNode.class))
			.addNode(HUMAN_FEEDBACK_NODE, nodeBeanUtil.getNodeBeanAsync(HumanFeedbackNode.class));

		stateGraph.addEdge(START, REQUEST_ANALYSIS_NODE)
			.addConditionalEdges(REQUEST_ANALYSIS_NODE, edge_async(new RequestAnalysisDispatcher()),
					Map.of(SEMANTIC_PLAN_NODE, SEMANTIC_PLAN_NODE, REQUEST_SYNTHESIS_NODE, REQUEST_SYNTHESIS_NODE, END, END))
			.addConditionalEdges(QUERY_ENHANCE_NODE, edge_async(new QueryEnhanceDispatcher()),
					Map.of(SCHEMA_RECALL_NODE, SCHEMA_RECALL_NODE, END, END))
			.addConditionalEdges(SCHEMA_RECALL_NODE, edge_async(new SchemaRecallDispatcher()),
					Map.of(TABLE_RELATION_NODE, TABLE_RELATION_NODE, END, END))

			.addConditionalEdges(TABLE_RELATION_NODE, edge_async(new TableRelationDispatcher()),
					Map.of(SEMANTIC_PLAN_NODE, SEMANTIC_PLAN_NODE, PLANNER_NODE, PLANNER_NODE, END, END,
							TABLE_RELATION_NODE, TABLE_RELATION_NODE)) // retry / advanced fallback
			.addConditionalEdges(SEMANTIC_PLAN_NODE, edge_async(new SemanticBlueprintExecutionDispatcher()),
					Map.of(HUMAN_FEEDBACK_NODE, HUMAN_FEEDBACK_NODE, SEMANTIC_EXECUTION_NODE, SEMANTIC_EXECUTION_NODE))
			.addConditionalEdges(SEMANTIC_EXECUTION_NODE, edge_async(new SemanticExecutionDispatcher()),
					Map.of(POST_EXECUTION_REVIEW_NODE, POST_EXECUTION_REVIEW_NODE, QUERY_ENHANCE_NODE,
							QUERY_ENHANCE_NODE))
			// The edge from PlannerNode now goes to PlanExecutorNode for validation and
			// execution
			.addEdge(PLANNER_NODE, PLAN_EXECUTOR_NODE)
			// python nodes
			.addEdge(PYTHON_GENERATE_NODE, PYTHON_EXECUTE_NODE)
			.addConditionalEdges(PYTHON_EXECUTE_NODE, edge_async(new PythonExecutorDispatcher(codeExecutorProperties)),
					Map.of(PYTHON_ANALYZE_NODE, PYTHON_ANALYZE_NODE, END, END, PYTHON_GENERATE_NODE,
							PYTHON_GENERATE_NODE))
			.addEdge(PYTHON_ANALYZE_NODE, PLAN_EXECUTOR_NODE)
			// The dispatcher at PlanExecutorNode will decide the next step
			.addConditionalEdges(PLAN_EXECUTOR_NODE, edge_async(new PlanExecutorDispatcher()), Map.of(
					// If validation fails, go back to PlannerNode to repair
					PLANNER_NODE, PLANNER_NODE,
					// If validation passes, proceed to the correct execution node
					SQL_GENERATE_NODE, SQL_GENERATE_NODE, PYTHON_GENERATE_NODE, PYTHON_GENERATE_NODE,
					REPORT_GENERATOR_NODE, REPORT_GENERATOR_NODE,
					// If human review is enabled, go to human_feedback node
					HUMAN_FEEDBACK_NODE, HUMAN_FEEDBACK_NODE,
					// If max repair attempts are reached, end the process
					END, END))
			// Human feedback node routing
			.addConditionalEdges(HUMAN_FEEDBACK_NODE, edge_async(new HumanFeedbackDispatcher()), Map.of(
					// Natural-language rejection changes business semantics, so re-plan the Semantic Blueprint.
					SEMANTIC_PLAN_NODE, SEMANTIC_PLAN_NODE,
					// Approval continues the same Semantic Blueprint through governed compiler-first execution.
					SEMANTIC_EXECUTION_NODE, SEMANTIC_EXECUTION_NODE,
					// If max repair attempts are reached, end the process
					END, END))
			.addEdge(REPORT_GENERATOR_NODE, TODO_BOUNDARY_NODE)
			.addConditionalEdges(TODO_BOUNDARY_NODE, edge_async(new TodoBoundaryDispatcher()),
					Map.of(SEMANTIC_PLAN_NODE, SEMANTIC_PLAN_NODE, REQUEST_SYNTHESIS_NODE, REQUEST_SYNTHESIS_NODE, END, END))
			.addEdge(REQUEST_SYNTHESIS_NODE, END)
			// sql generate and sql execute node
			.addConditionalEdges(SQL_GENERATE_NODE, nodeBeanUtil.getEdgeBeanAsync(SqlGenerateDispatcher.class),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, END, END, SEMANTIC_CONSISTENCY_NODE,
							SEMANTIC_CONSISTENCY_NODE))
			.addConditionalEdges(SEMANTIC_CONSISTENCY_NODE, edge_async(new SemanticConsistenceDispatcher()),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, SQL_EXECUTE_NODE, SQL_EXECUTE_NODE, PLANNER_NODE,
							PLANNER_NODE))
			.addConditionalEdges(SQL_EXECUTE_NODE, edge_async(new SQLExecutorDispatcher()),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, PLANNER_NODE, PLANNER_NODE, POST_EXECUTION_REVIEW_NODE,
							POST_EXECUTION_REVIEW_NODE))
			.addConditionalEdges(POST_EXECUTION_REVIEW_NODE, edge_async(new PostExecutionReviewDispatcher()),
					Map.of(PLAN_EXECUTOR_NODE, PLAN_EXECUTOR_NODE, PLANNER_NODE, PLANNER_NODE, SQL_GENERATE_NODE,
							SQL_GENERATE_NODE, QUERY_ENHANCE_NODE, QUERY_ENHANCE_NODE, REPORT_GENERATOR_NODE,
							REPORT_GENERATOR_NODE, TODO_BOUNDARY_NODE, TODO_BOUNDARY_NODE, SEMANTIC_PLAN_NODE,
							SEMANTIC_PLAN_NODE));

		GraphRepresentation graphRepresentation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
				"workflow graph");

		log.info("workflow in PlantUML format as follows \n\n" + graphRepresentation.content() + "\n\n");

		return stateGraph;
	}

@Bean
	public ToolCallbackResolver toolCallbackResolver(GenericApplicationContext context) {
		List<ToolCallback> allFunctionAndToolCallbacks = new ArrayList<>(
				McpServerToolUtil.excludeMcpServerTool(context, ToolCallback.class));
		McpServerToolUtil.excludeMcpServerTool(context, ToolCallbackProvider.class)
			.stream()
			.map(pr -> List.of(pr.getToolCallbacks()))
			.forEach(allFunctionAndToolCallbacks::addAll);

		var staticToolCallbackResolver = new StaticToolCallbackResolver(allFunctionAndToolCallbacks);

		var springBeanToolCallbackResolver = SpringBeanToolCallbackResolver.builder()
			.applicationContext(context)
			.build();

		return new DelegatingToolCallbackResolver(List.of(staticToolCallbackResolver, springBeanToolCallbackResolver));
	}

	/**
	 * 动态生成 EmbeddingModel 代理 Bean，使现有消费者在模型热切换后自动获取当前激活的 EmbeddingModel。
	 */
	@Bean
	@Primary
	public EmbeddingModel embeddingModel(AiModelRegistry registry) {

		// 1. 定义目标源 (TargetSource)
		TargetSource targetSource = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return EmbeddingModel.class;
			}

			@Override
			public boolean isStatic() {
				// 关键：声明是动态的，每次都要重新获取目标
				return false;
			}

			@Override
			public Object getTarget() {
				// 每次方法调用，都去注册表拿最新的
				return registry.getEmbeddingModel();
			}

			@Override
			public void releaseTarget(Object target) {
				// 无需释放
			}
		};

		// 2. 创建代理工厂
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(targetSource);
		// 代理接口
		proxyFactory.addInterface(EmbeddingModel.class);

		// 3. 返回动态生成的代理对象
		return (EmbeddingModel) proxyFactory.getProxy();
	}

}
